/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import android.content.Context
import android.util.LruCache
import com.metrolist.music.constants.LyricsProviderOrderKey
import com.metrolist.music.constants.EnableAmllKey
import com.metrolist.music.constants.EnableBetterLyricsKey
import com.metrolist.music.constants.EnableKugouKey
import com.metrolist.music.constants.EnableLrcLibKey
import com.metrolist.music.constants.EnableLrcMuxKey
import com.metrolist.music.constants.EnableLrcRedKey
import com.metrolist.music.constants.EnableLyricsPlus
import com.metrolist.music.constants.EnablePaxsenixKey
import com.metrolist.music.constants.EnableUnisonKey
import com.metrolist.music.constants.EnableZemerKey
import com.metrolist.music.utils.get
import com.metrolist.music.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.utils.NetworkConnectivityObserver
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.reportException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

private const val TAG = "LyricsHelper"
private const val PROVIDER_NONE = ""

class LyricsHelper
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val networkConnectivity: NetworkConnectivityObserver,
) {
    val preferred =
        context.dataStore.data
            .map { preferences ->
                resolveLyricsProviders(preferences)
            }.distinctUntilChanged()

    private val cache = LruCache<String, List<LyricsResult>>(MAX_CACHE_SIZE)
    private var currentLyricsJob: Job? = null

    /** The player and the playback service both ask when a song starts; they share one search. */
    suspend fun getLyrics(mediaMetadata: MediaMetadata): LyricsWithProvider {
        val search = searches.computeIfAbsent(mediaMetadata.id) { id ->
            searchScope.async(start = CoroutineStart.LAZY) { searchLyrics(mediaMetadata) }.also { search ->
                search.invokeOnCompletion { searches.remove(id, search) }
            }
        }
        search.start()
        return search.await()
    }

    private suspend fun searchLyrics(mediaMetadata: MediaMetadata): LyricsWithProvider {
        currentLyricsJob?.cancel()

        val cached = cache.get(mediaMetadata.id)?.firstOrNull()
        if (cached != null) {
            return LyricsWithProvider(cached.lyrics, cached.providerName)
        }

        val orderedProviders = context.dataStore.data
            .map { preferences -> resolveLyricsProviders(preferences) }
            .first()

        val isNetworkAvailable = try {
            networkConnectivity.isCurrentlyConnected()
        } catch (e: Exception) {
            true
        }

        if (!isNetworkAvailable) {
            return LyricsWithProvider(LYRICS_NOT_FOUND, PROVIDER_NONE)
        }

        val cleanedTitle = LyricsUtils.cleanTitleForSearch(mediaMetadata.title)
        val artists = mediaMetadata.artists.joinToString { it.name }
        val enabledProviders = orderedProviders.filter { it.isEnabled(context) }
        Timber.tag(TAG).d("Fetching lyrics for: $cleanedTitle by $artists from ${enabledProviders.joinToString { it.name }}")

        val best = searchBestLyrics(
            context = context,
            providers = enabledProviders,
            id = mediaMetadata.id,
            title = cleanedTitle,
            artists = artists,
            duration = mediaMetadata.duration,
            album = mediaMetadata.album?.title,
        )
        return best?.let { LyricsWithProvider(it.lyrics, it.provider) }
            ?: LyricsWithProvider(LYRICS_NOT_FOUND, PROVIDER_NONE)
    }

    suspend fun getAllLyrics(
        mediaId: String,
        songTitle: String,
        songArtists: String,
        duration: Int,
        album: String? = null,
        callback: (LyricsResult) -> Unit,
    ) {
        currentLyricsJob?.cancel()

        val cacheKey = "$songArtists-$songTitle".replace(" ", "")
        cache.get(cacheKey)?.let { results ->
            results.forEach { callback(it) }
            return
        }

        val isNetworkAvailable = try {
            networkConnectivity.isCurrentlyConnected()
        } catch (e: Exception) {
            true
        }

        if (!isNetworkAvailable) return

        val allResult = mutableListOf<LyricsResult>()
        currentLyricsJob = CoroutineScope(SupervisorJob()).launch {
            val cleanedTitle = LyricsUtils.cleanTitleForSearch(songTitle)
            val allProviders = context.dataStore.data
                .map { preferences -> resolveLyricsProviders(preferences) }
                .first()
            val enabledProviders = allProviders.filter { it.isEnabled(context) }

            val otherProviders = enabledProviders.filter { it.name != "LyricsPlus" }
            val lyricsPlusProvider = enabledProviders.find { it.name == "LyricsPlus" }

            val callbackMutex = Any()

            val otherJobs = otherProviders.map { provider ->
                launch {
                    try {
                        provider.getAllLyrics(context, mediaId, cleanedTitle, songArtists, duration, album) found@{ lyrics ->
                            val filteredLyrics = normalizeLyrics(lyrics) ?: return@found
                            val result = LyricsResult(provider.name, filteredLyrics)
                            synchronized(callbackMutex) {
                                allResult += result
                                callback(result)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        reportException(e)
                    }
                }
            }
            otherJobs.forEach { it.join() }

            val otherLyricsCount = allResult.count { it.providerName != "LyricsPlus" }
            if (lyricsPlusProvider != null && otherLyricsCount <= 2) {
                launch {
                    try {
                        lyricsPlusProvider.getAllLyrics(context, mediaId, cleanedTitle, songArtists, duration, album) found@{ lyrics ->
                            val filteredLyrics = normalizeLyrics(lyrics) ?: return@found
                            val result = LyricsResult(lyricsPlusProvider.name, filteredLyrics)
                            synchronized(callbackMutex) {
                                allResult += result
                                callback(result)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        reportException(e)
                    }
                }.join()
            }

            cache.put(cacheKey, allResult)
        }

        currentLyricsJob?.join()
    }

    private fun resolveLyricsProviders(preferences: androidx.datastore.preferences.core.Preferences): List<LyricsProvider> {
        val providerOrder = preferences[LyricsProviderOrderKey].orEmpty()
        if (providerOrder.isNotBlank()) {
            return LyricsProviderRegistry.getOrderedProviders(providerOrder)
        }

        return LyricsProviderRegistry.getDefaultProviderOrder()
            .mapNotNull { LyricsProviderRegistry.getProviderByName(it) }
    }

    companion object {
        private const val MAX_CACHE_SIZE = 3

        // Shared across instances, since each caller is injected its own helper.
        private val searchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val searches = ConcurrentHashMap<String, Deferred<LyricsWithProvider>>()
    }
}

data class LyricsResult(
    val providerName: String,
    val lyrics: String,
)

data class LyricsWithProvider(
    val lyrics: String,
    val provider: String,
)

// The providers are shared with the watch app, so whether each is switched on comes from this app's settings.
private fun LyricsProvider.isEnabled(context: Context): Boolean =
    when (this) {
        BetterLyricsProvider -> context.dataStore[EnableBetterLyricsKey] ?: true
        AmllLyricsProvider -> context.dataStore[EnableAmllKey] ?: true
        UnisonLyricsProvider -> context.dataStore[EnableUnisonKey] ?: true
        LrcLibLyricsProvider -> context.dataStore[EnableLrcLibKey] ?: true
        KuGouLyricsProvider -> context.dataStore[EnableKugouKey] ?: true
        PaxsenixLyricsProvider -> context.dataStore[EnablePaxsenixKey] ?: true
        LyricsPlusProvider -> context.dataStore[EnableLyricsPlus] ?: false
        ZemerLyricsProvider -> context.dataStore[EnableZemerKey] ?: true
        LrcMuxLyricsProvider -> context.dataStore[EnableLrcMuxKey] ?: true
        LrcRedLyricsProvider -> context.dataStore[EnableLrcRedKey] ?: true
        // YouTube and YouTube subtitles have no switch.
        else -> true
    }