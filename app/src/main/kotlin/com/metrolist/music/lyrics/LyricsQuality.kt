/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

/** Ordered worst to best, so `maxOf` picks the better answer. */
enum class LyricsQuality { NONE, PLAIN, LINE_SYNCED, WORD_SYNCED }

private val LINE_TIMESTAMP = Regex("""\[\d{1,2}:\d{2}(?:\.\d{1,3})?]""")
private val ANY_MARKUP = Regex("""\[[^\]]*]|\{[^}]*}|<[^>]*>""")

/** A note saying the song has no words, e.g. "纯音乐，请欣赏" or "(Instrumental)". */
private val NO_WORDS_NOTE = Regex(
    """^[\s\p{Punct}♪♫，。！～·]*(?:instrumental|纯音乐[\s，,]*请欣赏|此歌曲为没有填词的纯音乐[\s，,]*请您欣赏)?[\s\p{Punct}♪♫，。！～·]*$""",
    RegexOption.IGNORE_CASE,
)

private fun textLines(lyrics: String): List<String> =
    lyrics.lines().map { it.replace(ANY_MARKUP, "").trim() }.filter { line -> line.any(Char::isLetterOrDigit) }

/**
 * Cleans a provider's answer, or returns null when it is no answer at all: blank, only credits,
 * only a "no words" note, or synced-looking text that does not parse. LRC whose text lines share
 * fewer than two timestamps has no real timing and is turned into plain text, so it cannot outrank
 * a genuine sync.
 */
fun normalizeLyrics(raw: String): String? {
    val lyrics = LyricsUtils.filterLyricsCreditLines(raw).trim()
    val text = textLines(lyrics)
    if (text.isEmpty() || NO_WORDS_NOTE.matches(text.joinToString(" "))) return null
    if (!LINE_TIMESTAMP.containsMatchIn(lyrics)) return lyrics

    val timedLines = lyrics.lines().filter { line -> line.replace(ANY_MARKUP, "").any(Char::isLetterOrDigit) }
    val stamps = timedLines.flatMap { line -> LINE_TIMESTAMP.findAll(line).map { it.value } }.toSet()
    if (stamps.size < 2 && text.size > 1) return text.joinToString("\n")

    return lyrics.takeIf { LyricsUtils.parseLyrics(it).any { entry -> entry.text.isNotBlank() } }
}

/** Measures already normalized lyrics. */
fun lyricsQuality(lyrics: String): LyricsQuality {
    if (lyrics.isBlank()) return LyricsQuality.NONE
    if (!LINE_TIMESTAMP.containsMatchIn(lyrics)) return LyricsQuality.PLAIN
    val entries = LyricsUtils.parseLyrics(lyrics)
    return when {
        entries.any { !it.words.isNullOrEmpty() } -> LyricsQuality.WORD_SYNCED
        entries.isNotEmpty() -> LyricsQuality.LINE_SYNCED
        else -> LyricsQuality.NONE
    }
}

internal data class LyricsCandidate(
    val provider: String,
    val lyrics: String,
    val quality: LyricsQuality,
    /** Position in the user's provider order; lower is preferred. */
    val rank: Int,
)

/** Better timing wins; between equal timing the user's order wins, never whichever answered first. */
internal fun Collection<LyricsCandidate>.best(): LyricsCandidate? =
    maxWithOrNull(compareBy<LyricsCandidate> { it.quality }.thenByDescending { it.rank })

/** Nothing still out can beat [best]: word timing is the ceiling, and only higher ranks win ties. */
internal fun isSettled(best: LyricsCandidate?, pendingRanks: Collection<Int>): Boolean =
    best?.quality == LyricsQuality.WORD_SYNCED && pendingRanks.none { it < best.rank }
