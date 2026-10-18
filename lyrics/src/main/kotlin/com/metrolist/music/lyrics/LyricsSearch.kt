/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

private const val TAG = "LyricsSearch"
private const val MAX_LYRICS_FETCH_MS = 25000L
private const val PER_PROVIDER_TIMEOUT_MS = 8000L
/** How long the top source is asked alone before the rest are asked too. */
private const val LEAD_HOLD_MS = 1000L

/**
 * Asks [providers] (already in the user's order, already filtered to the enabled ones) for a song and returns the
 * best answer: better timing wins, ties go to the earlier provider. [title] should already be cleaned for search.
 */
@OptIn(ExperimentalCoroutinesApi::class)
suspend fun searchBestLyrics(
    context: Context,
    providers: List<LyricsProvider>,
    id: String,
    title: String,
    artists: String,
    duration: Int,
    album: String?,
): LyricsCandidate? {
    suspend fun ask(rank: Int): LyricsCandidate? {
        val provider = providers[rank]
        val started = System.currentTimeMillis()
        val candidate = try {
            withTimeoutOrNull(PER_PROVIDER_TIMEOUT_MS) {
                provider.getLyrics(context, id, title, artists, duration, album)
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
            if (providers.isNotEmpty()) launchAsk(0)
            var fannedOut = providers.size <= 1
            fun fanOut() {
                (1 until providers.size).forEach(::launchAsk)
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
    return best
}
