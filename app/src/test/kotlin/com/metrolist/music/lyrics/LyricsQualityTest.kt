package com.metrolist.music.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LyricsQualityTest {

    @Test
    fun measuresTimingQuality() {
        assertEquals(LyricsQuality.PLAIN, lyricsQuality("First line\nSecond line"))
        assertEquals(LyricsQuality.LINE_SYNCED, lyricsQuality("[00:01.00]First line\n[00:03.00]Second line"))
        assertEquals(
            LyricsQuality.WORD_SYNCED,
            lyricsQuality("[00:01.00]<00:01.00>First <00:01.50>line\n[00:03.00]<00:03.00>Second <00:03.40>line"),
        )
    }

    @Test
    fun noWordsNotesAndBlankAnswersAreMisses() {
        assertNull(normalizeLyrics(""))
        assertNull(normalizeLyrics("[00:00.00]纯音乐，请欣赏"))
        assertNull(normalizeLyrics("[00:00.00](Instrumental)"))
        assertNull(normalizeLyrics("[00:00.00]♪\n[00:05.00]♪"))
    }

    @Test
    fun fakeTimingBecomesPlainText() {
        val normalized = normalizeLyrics("[00:00.00]First line\n[00:00.00]Second line")
        assertEquals("First line\nSecond line", normalized)
        assertEquals(LyricsQuality.PLAIN, lyricsQuality(normalized!!))
    }

    @Test
    fun betterTimingWinsAndOrderBreaksTies() {
        val plainFirst = LyricsCandidate("A", "x", LyricsQuality.PLAIN, rank = 0)
        val wordThird = LyricsCandidate("C", "x", LyricsQuality.WORD_SYNCED, rank = 2)
        val wordSecond = LyricsCandidate("B", "x", LyricsQuality.WORD_SYNCED, rank = 1)
        assertEquals("C", listOf(plainFirst, wordThird).best()?.provider)
        assertEquals("B", listOf(plainFirst, wordThird, wordSecond).best()?.provider)
    }

    @Test
    fun settlesOnlyWhenNothingRankedAboveIsPending() {
        val word = LyricsCandidate("B", "x", LyricsQuality.WORD_SYNCED, rank = 1)
        assertFalse(isSettled(word, pendingRanks = listOf(0)))
        assertTrue(isSettled(word, pendingRanks = listOf(2, 3)))
        assertFalse(isSettled(word.copy(quality = LyricsQuality.LINE_SYNCED), pendingRanks = emptyList()))
    }
}
