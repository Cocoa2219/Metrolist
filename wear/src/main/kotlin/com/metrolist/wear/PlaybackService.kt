package com.metrolist.wear

import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.metrolist.extraction.CachedStreamUrl
import com.metrolist.extraction.InnerTubeXPlayer
import com.metrolist.extraction.StreamUrlCache
import com.metrolist.extraction.withResolvedStream
import com.metrolist.innertubex.extraction.AudioQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import timber.log.Timber

class PlaybackService : MediaSessionService() {
    private lateinit var session: MediaSession
    private val streamUrls = StreamUrlCache()
    private val retriedAfterRejection = mutableSetOf<String>()
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private var networkRequested = false

    // Wear OS sends traffic over the phone's slow Bluetooth proxy unless an app asks for Wi-Fi or LTE.
    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                connectivity.bindProcessToNetwork(network)
            }

            override fun onLost(network: Network) {
                if (connectivity.boundNetworkForProcess == network) connectivity.bindProcessToNetwork(null)
            }
        }

    override fun onCreate() {
        super.onCreate()
        val player =
            ExoPlayer.Builder(this)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory()))
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true,
                )
                .setHandleAudioBecomingNoisy(true)
                // Load in bursts (up to 6 min ahead, refill below 2 min) so the radio can sleep between
                // them; a song usually arrives in one go.
                .setLoadControl(
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(120_000, 360_000, 750, 2_000)
                        .setPrioritizeTimeOverSizeThresholds(true)
                        .build(),
                )
                // CPU wake lock only: the Wi-Fi/LTE request below keeps the network up, and skipping the
                // high-performance Wi-Fi lock lets the radio power-save between bursts.
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .build()
        player.addListener(PlayerListener(player))
        session =
            MediaSession.Builder(this, player)
                .setCallback(SessionCallback)
                .setSessionActivity(
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
                .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onDestroy() {
        releaseNetwork()
        session.player.release()
        session.release()
        super.onDestroy()
    }

    private fun dataSourceFactory() =
        ResolvingDataSource.Factory(
            CacheDataSource.Factory()
                .setCache((application as WearApp).playerCache)
                .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(OkHttpClient())),
        ) { dataSpec ->
            val videoId = dataSpec.key ?: error("No media id")
            streamUrls[videoId]?.let { return@Factory dataSpec.withResolvedStream(it) }

            val generation = streamUrls.generation(videoId)
            val quality = if (connectivity.isActiveNetworkMetered) AudioQuality.LOW else AudioQuality.AUTO
            val playback =
                runBlocking(Dispatchers.IO) {
                    InnerTubeXPlayer.playerResponseForPlayback(videoId, audioQuality = quality)
                }.getOrThrow()
            val stream =
                CachedStreamUrl(
                    url = playback.streamUrl,
                    requestHeaders = playback.streamHeaders,
                    clientName = playback.streamClient,
                    requireBoundedRange = playback.requireBoundedRange,
                    rangeChunkSizeBytes = playback.rangeChunkSizeBytes,
                    useRangeChunks = playback.useRangeChunks,
                )
            streamUrls.put(
                videoId,
                stream.url,
                stream.requestHeaders,
                stream.clientName,
                playback.streamExpiresInSeconds,
                stream.requireBoundedRange,
                stream.rangeChunkSizeBytes,
                stream.useRangeChunks,
                expectedGeneration = generation,
            )
            Timber.i("Resolved $videoId via ${stream.clientName}")
            dataSpec.withResolvedStream(stream)
        }

    private fun requestNetwork() {
        if (networkRequested) return
        networkRequested = true
        val request =
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build()
        connectivity.requestNetwork(request, networkCallback)
    }

    private fun releaseNetwork() {
        if (!networkRequested) return
        networkRequested = false
        connectivity.unregisterNetworkCallback(networkCallback)
        connectivity.bindProcessToNetwork(null)
    }

    private inner class PlayerListener(private val player: ExoPlayer) : Player.Listener {
        override fun onEvents(
            player: Player,
            events: Player.Events,
        ) {
            val wantsPlayback =
                player.playWhenReady && player.playbackState != Player.STATE_IDLE && player.playbackState != Player.STATE_ENDED
            if (wantsPlayback) requestNetwork() else releaseNetwork()
        }

        override fun onMediaItemTransition(
            mediaItem: MediaItem?,
            reason: Int,
        ) {
            retriedAfterRejection.clear()
        }

        // Stream URLs expire after a few hours, so a paused song can come back with 403; re-resolve once.
        override fun onPlayerError(error: PlaybackException) {
            val videoId = player.currentMediaItem?.mediaId ?: return
            val code = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            Timber.w(error, "Playback failed for $videoId (http=$code)")
            if (code != 403 && code != 410) return
            if (!retriedAfterRejection.add(videoId)) return
            streamUrls.clientName(videoId)?.let { InnerTubeXPlayer.markStreamClientFailed(videoId, it) }
            streamUrls.invalidate(videoId)
            player.prepare()
            player.play()
        }
    }

    // Controllers can't send playable URIs, so items arrive as bare video ids and get one here.
    private object SessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(
                mediaItems.mapTo(mutableListOf()) {
                    it.buildUpon()
                        .setUri("https://music.youtube.com/watch?v=${it.mediaId}")
                        .setCustomCacheKey(it.mediaId)
                        .build()
                },
            )
    }
}
