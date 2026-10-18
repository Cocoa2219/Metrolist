/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import kotlinx.coroutines.flow.MutableStateFlow

data class WordTimestamp(
    val text: String,
    val startTime: Double,
    val endTime: Double,
    val hasTrailingSpace: Boolean = true,
    /** The word's romanization as the lyrics source supplied it. */
    val phonetic: String? = null,
)

data class LyricsEntry(
    val time: Long,
    val text: String,
    val words: List<WordTimestamp>? = null,
    val romanizedTextFlow: MutableStateFlow<String?> = MutableStateFlow(null),
    val translatedTextFlow: MutableStateFlow<String?> = MutableStateFlow(null),
    val agent: String? = null,
    val isBackground: Boolean = false,
    /** Translation and romanization as the lyrics source supplied them (TTML), kept across resets. */
    val sourceTranslation: String? = null,
    val sourceRomanization: String? = null,
) : Comparable<LyricsEntry> {
    override fun compareTo(other: LyricsEntry): Int = (time - other.time).toInt()

    companion object {
        val HEAD_LYRICS_ENTRY = LyricsEntry(0L, "")
    }
}
