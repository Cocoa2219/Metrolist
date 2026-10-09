/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import android.content.Context
import android.util.LruCache
import com.metrolist.music.constants.LyricsProviderOrderKey
import com.metrolist.music.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.utils.NetworkConnectivityObserver
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.reportException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject

private const val TAG = "LyricsHelper"
private const val MAX_LYRICS_FETCH_MS = 25000L
private const val PER_PROVIDER_TIMEOUT_MS = 8000L
/** How long the top source is asked alone before the rest are asked too. */
private const val LEAD_HOLD_MS = 1000L
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

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun getLyrics(mediaMetadata: MediaMetadata): LyricsWithProvider {
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

        suspend fun ask(rank: Int): LyricsCandidate? {
            val provider = enabledProviders[rank]
            val started = System.currentTimeMillis()
            val candidate = try {
                withTimeoutOrNull(PER_PROVIDER_TIMEOUT_MS) {
                    provider.getLyrics(context, mediaMetadata.id, cleanedTitle, artists, mediaMetadata.duration, mediaMetadata.album?.title)
                }?.getOrNull()
                    ?.let(::normalizeLyrics)
                    ?.let { LyricsCandidate(provider.name, it, lyricsQuality(it), rank) }
                    ?.takeIf { it.quality != LyricsQuality.NONE }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w("${provider.name} threw: ${e.message}")
                null
            }
            Timber.tag(TAG).d("${provider.name}: ${candidate?.quality ?: "miss"} in ${System.currentTimeMillis() - started}ms")
            return candidate
        }

        // Kept outside the overall timeout so a slow walk still returns the best answer so far.
        val candidates = mutableListOf<LyricsCandidate>()
        withTimeoutOrNull(MAX_LYRICS_FETCH_MS) {
            coroutineScope {
                val landed = Channel<Pair<Int, LyricsCandidate?>>(Channel.UNLIMITED)
                val jobs = mutableListOf<Job>()
                val pending = mutableSetOf<Int>()
                fun launchAsk(rank: Int) {
                    pending += rank
                    jobs += launch { landed.send(rank to ask(rank)) }
                }

                // The top source leads alone, since one request usually settles a song; the rest are
                // asked in parallel only if it misses, answers without word timing, or is slow.
                if (enabledProviders.isNotEmpty()) launchAsk(0)
                var fannedOut = enabledProviders.size <= 1
                fun fanOut() {
                    (1 until enabledProviders.size).forEach(::launchAsk)
                    fannedOut = true
                }

                while (!isSettled(candidates.best(), pending)) {
                    if (!fannedOut && pending.isEmpty()) fanOut()
                    if (pending.isEmpty()) break
                    val next = if (fannedOut) {
                        landed.receive()
                    } else {
                        select<Pair<Int, LyricsCandidate?>?> {
                            landed.onReceive { it }
                            onTimeout(LEAD_HOLD_MS) { null }
                        }
                    }
                    if (next == null) {
                        fanOut()
                        continue
                    }
                    pending -= next.first
                    next.second?.let(candidates::add)
                }
                jobs.forEach { it.cancel() }
            }
        }

        val best = candidates.best()
        Timber.tag(TAG).i("Picked ${best?.provider ?: "nothing"} (${best?.quality ?: LyricsQuality.NONE})")
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
