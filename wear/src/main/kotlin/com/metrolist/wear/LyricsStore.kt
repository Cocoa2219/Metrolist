package com.metrolist.wear

import android.content.Context
import android.util.LruCache
import com.metrolist.music.lyrics.LyricsCandidate
import com.metrolist.music.lyrics.LyricsPlusProvider
import com.metrolist.music.lyrics.LyricsProviderRegistry
import com.metrolist.music.lyrics.LyricsQuality
import com.metrolist.music.lyrics.LyricsUtils
import com.metrolist.music.lyrics.lyricsQuality
import com.metrolist.music.lyrics.normalizeLyrics
import com.metrolist.music.lyrics.searchBestLyrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A line as the watch shows it; [timeMs] is null for lyrics without timing. */
data class LyricLine(val timeMs: Long?, val text: String, val romanized: String?)

sealed interface WearLyrics {
    data object Loading : WearLyrics

    data object NotFound : WearLyrics

    data class Found(val lines: List<LyricLine>, val synced: Boolean, val provider: String) : WearLyrics
}

// Japanese only for now; the language list becomes a setting with the Settings screen.
private val RomanizedLanguages = listOf("Japanese")
private const val CACHED_SONGS = 8
private const val SOURCE_TIMEOUT_MS = 8_000L
const val LYRICS_OFFSET_STEP_MS = 500L

/**
 * Lyrics for the playing song plus what the lyrics menu changes. Owned by the app because the lyrics screen and
 * its menu come and go, while the cache, per-song timing offsets and the chosen source should not.
 */
class LyricsStore(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("lyrics", Context.MODE_PRIVATE)
    private val cache = LruCache<String, WearLyrics>(CACHED_SONGS)
    private var requested: String? = null

    private val _lyrics = MutableStateFlow<WearLyrics>(WearLyrics.Loading)
    val lyrics: StateFlow<WearLyrics> = _lyrics

    private val _romanize = MutableStateFlow(prefs.getBoolean(KEY_ROMANIZE, true))
    val romanize: StateFlow<Boolean> = _romanize

    private val _offsetMs = MutableStateFlow(0L)
    /** How much later than their timestamps the current song's lines are shown; positive means later. */
    val offsetMs: StateFlow<Long> = _offsetMs

    fun setRomanize(on: Boolean) {
        _romanize.value = on
        prefs.edit().putBoolean(KEY_ROMANIZE, on).apply()
    }

    fun shiftOffset(
        song: NowPlaying,
        byMs: Long,
    ) {
        val id = song.mediaId ?: return
        val offset = _offsetMs.value + byMs
        _offsetMs.value = offset
        prefs.edit().apply { if (offset == 0L) remove(offsetKey(id)) else putLong(offsetKey(id), offset) }.apply()
    }

    /** Fetches once per song; the duration has to be known since providers match on it. */
    fun load(song: NowPlaying) {
        val id = song.mediaId ?: return
        if (song.durationMs <= 0 || id == requested) return
        requested = id
        _offsetMs.value = prefs.getLong(offsetKey(id), 0L)
        cache.get(id)?.let {
            _lyrics.value = it
            return
        }
        _lyrics.value = WearLyrics.Loading
        scope.launch(Dispatchers.IO) {
            val best =
                searchBestLyrics(context, providers(), id, cleanTitle(song), song.artist, durationSeconds(song), album = null)
            publish(id, best?.let { toWearLyrics(it) } ?: WearLyrics.NotFound)
        }
    }

    private var sources: Pair<String, List<LyricsCandidate>>? = null

    /** Every provider's answer for [song], best timing first, for picking another source by hand. */
    suspend fun allSources(song: NowPlaying): List<LyricsCandidate> {
        val id = song.mediaId ?: return emptyList()
        sources?.takeIf { it.first == id }?.let { return it.second }
        return fetchSources(id, song).also { sources = id to it }
    }

    private suspend fun fetchSources(
        id: String,
        song: NowPlaying,
    ): List<LyricsCandidate> =
        coroutineScope {
            providers().mapIndexed { rank, provider ->
                async(Dispatchers.IO) {
                    runCatching {
                        withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                            provider.getLyrics(context, id, cleanTitle(song), song.artist, durationSeconds(song), null)
                        }?.getOrNull()
                    }.getOrNull()
                        ?.let(::normalizeLyrics)
                        ?.let { LyricsCandidate(provider.name, it, lyricsQuality(it), rank) }
                        ?.takeIf { it.quality != LyricsQuality.NONE }
                }
            }.awaitAll().filterNotNull().sortedWith(compareByDescending<LyricsCandidate> { it.quality }.thenBy { it.rank })
        }

    fun choose(
        song: NowPlaying,
        source: LyricsCandidate,
    ) {
        val id = song.mediaId ?: return
        scope.launch(Dispatchers.Default) { publish(id, toWearLyrics(source)) }
    }

    private fun publish(
        id: String,
        lyrics: WearLyrics,
    ) {
        cache.put(id, lyrics)
        if (requested == id) _lyrics.value = lyrics
    }

    private suspend fun toWearLyrics(source: LyricsCandidate): WearLyrics {
        val synced = source.quality != LyricsQuality.PLAIN
        val raw =
            if (synced) {
                LyricsUtils.parseLyrics(source.lyrics).filter { !it.isBackground && it.text.isNotBlank() }
                    .map { Triple(it.time, it.text, it.sourceRomanization) }
            } else {
                source.lyrics.lines().filter(String::isNotBlank).map { Triple(null, it, null) }
            }
        val wholeSong = raw.joinToString("\n") { it.second }
        val lines =
            raw.map { (time, text, sourceRomanization) ->
                val romanized =
                    LyricsUtils.romanize(wholeSong, text, RomanizedLanguages, romanizeCyrillicByLine = false, preferred = sourceRomanization)
                        ?.takeIf { it.isNotBlank() && it != text }
                LyricLine(time, text, romanized)
            }
        return if (lines.isEmpty()) WearLyrics.NotFound else WearLyrics.Found(lines, synced, source.provider)
    }

    // The phone app's default order, with LyricsPlus off as it is there by default.
    private fun providers() =
        LyricsProviderRegistry.getDefaultProviderOrder()
            .mapNotNull(LyricsProviderRegistry::getProviderByName)
            .filter { it != LyricsPlusProvider }

    private fun cleanTitle(song: NowPlaying) = LyricsUtils.cleanTitleForSearch(song.title)

    private fun durationSeconds(song: NowPlaying) = (song.durationMs / 1000).toInt()

    private fun offsetKey(id: String) = "offset:$id"

    private companion object {
        const val KEY_ROMANIZE = "romanize"
    }
}
