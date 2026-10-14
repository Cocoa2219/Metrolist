/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import com.metrolist.innertube.YouTube
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

data class EnglishSongName(val title: String, val artist: String, val album: String?)

/**
 * Apple's catalog lists most K-pop and J-pop under English titles and artists, which YouTube Music
 * also has. Sources searching that catalog retry with them, so they are fetched once per song.
 */
object EnglishSongNames {
    private const val MAX_ENTRIES = 32
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lookups = object : LinkedHashMap<String, Deferred<EnglishSongName?>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Deferred<EnglishSongName?>>) =
            size > MAX_ENTRIES
    }

    /** The English names, or null when YouTube Music has none that differ from [title] and [artist]. */
    suspend fun get(videoId: String, title: String, artist: String): EnglishSongName? {
        val lookup = synchronized(lookups) {
            lookups.getOrPut(videoId) {
                scope.async(start = CoroutineStart.LAZY) {
                    YouTube.englishSong(videoId).getOrNull()?.let { song ->
                        EnglishSongName(
                            LyricsUtils.cleanTitleForSearch(song.title),
                            song.artists.joinToString { it.name },
                            song.album?.name,
                        )
                    }
                }
            }
        }
        return lookup.await()?.takeUnless { it.title == title && it.artist == artist }
    }
}
