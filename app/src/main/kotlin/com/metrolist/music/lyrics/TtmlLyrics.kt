/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.core.parser.TTMLParser
import kotlinx.coroutines.flow.MutableStateFlow
import timber.log.Timber

/** Whether stored lyrics are TTML rather than Metrolist's LRC dialect. */
fun isTtml(lyrics: String): Boolean {
    val start = lyrics.trimStart().removePrefix("\uFEFF")
    return start.startsWith("<tt") || (start.startsWith("<?xml") && start.contains("<tt"))
}

/**
 * TTML is stored as the source sent it and parsed with Accompanist's parser, which keeps the
 * source's translations and per-word romanization (Apple's `itunes:` metadata).
 */
object TtmlLyrics {
    private val parser = TTMLParser()

    // lrc.red keys lines with lrc:key; Apple and the parser use itunes:key.
    private val LRC_KEY = Regex("""\slrc:key=""")

    // Machine-made romanizations (e.g. Apple's syllable-split Korean) read worse than Metrolist's.
    private val GENERATED_TRANSLITERATION =
        Regex("""<transliteration\b[^>]*automaticallyCreated="true"[^>]*>.*?</transliteration>""", RegexOption.DOT_MATCHES_ALL)

    fun parse(ttml: String): List<LyricsEntry> {
        val source = ttml.replace(LRC_KEY, " itunes:key=").replace(GENERATED_TRANSLITERATION, "")
        val lyrics = runCatching { parser.parse(source) }
            .onFailure { Timber.w(it, "Failed to parse TTML") }
            .getOrNull() ?: return emptyList()
        return buildList {
            for (line in lyrics.lines) when (line) {
                is KaraokeLine.MainKaraokeLine -> {
                    val agent = if (line.alignment == KaraokeAlignment.End) "v2" else "v1"
                    add(line.toEntry(agent, isBackground = false))
                    line.accompanimentLines?.forEach { add(it.toEntry(agent, isBackground = true)) }
                }
                is KaraokeLine -> add(line.toEntry("v1", isBackground = false))
                is SyncedLine -> add(
                    entry(line.start, line.content.trim(), null, "v1", false, line.translation, line.phonetic),
                )
            }
        }.sortedBy { it.time }
    }

    private fun KaraokeLine.toEntry(agent: String, isBackground: Boolean): LyricsEntry {
        val words = syllables.map { it.toWord() }
        return entry(
            start, words.joinToString("") { word -> if (word.hasTrailingSpace) "${word.text} " else word.text }.trim(),
            words, agent, isBackground, translation, phonetic ?: joinedPhonetic(syllables),
        )
    }

    private fun KaraokeSyllable.toWord() = WordTimestamp(
        text = content.trimEnd(),
        startTime = start / 1000.0,
        endTime = end / 1000.0,
        hasTrailingSpace = content.lastOrNull()?.isWhitespace() == true,
        // Some sources "transliterate" lines of another language as the text itself.
        phonetic = phonetic?.takeIf { it.isNotBlank() && it.trim() != content.trim() },
    )

    private fun joinedPhonetic(syllables: List<KaraokeSyllable>): String? = buildString {
        for (syllable in syllables) {
            val caption = syllable.phonetic?.takeIf { it.isNotBlank() && it.trim() != syllable.content.trim() } ?: continue
            if (isNotEmpty()) append(syllable.phoneticSeparatorBefore.ifEmpty { " " })
            append(caption)
        }
    }.ifBlank { null }

    private fun entry(
        start: Int, text: String, words: List<WordTimestamp>?, agent: String, isBackground: Boolean,
        translation: String?, romanization: String?,
    ): LyricsEntry {
        val sourceTranslation = translation?.takeIf { it.isNotBlank() }
        return LyricsEntry(
            time = start.toLong(),
            text = text,
            words = words?.takeIf { it.isNotEmpty() },
            translatedTextFlow = MutableStateFlow(sourceTranslation),
            agent = agent,
            isBackground = isBackground,
            sourceTranslation = sourceTranslation,
            sourceRomanization = romanization?.takeIf { it.isNotBlank() && it.trim() != text },
        )
    }
}
