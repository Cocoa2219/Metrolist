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

    // A line's transliteration nests its background vocals like the body does, and the parser
    // appends them to the main line's last syllable caption. They are dropped there; background
    // lines in a script needing romanization get Metrolist's own.
    private val TRANSLITERATIONS = Regex("""<transliterations\b.*?</transliterations>""", RegexOption.DOT_MATCHES_ALL)
    private val BACKGROUND_SPAN =
        Regex("""<span\b[^>]*ttm:role="x-bg"[^>]*>(?:\s*<span\b[^>]*>[^<]*</span>)*\s*</span>""")

    fun parse(ttml: String): List<LyricsEntry> {
        val source = ttml.replace(LRC_KEY, " itunes:key=").replace(GENERATED_TRANSLITERATION, "")
            .replace(TRANSLITERATIONS) { it.value.replace(BACKGROUND_SPAN, "") }
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
        val captions = captions(syllables)
        val words = syllables.mapIndexed { index, syllable -> syllable.toWord(captions[index]) }
        val text = words.joinToString("") { word -> if (word.hasTrailingSpace) "${word.text} " else word.text }.trim()
        val romanization = phonetic?.takeIf { LyricsUtils.needsRomanization(text) } ?: joinedCaption(syllables, captions)
        return entry(start, text, words, agent, isBackground, translation, romanization)
    }

    private fun KaraokeSyllable.toWord(caption: String?) = WordTimestamp(
        text = content.trimEnd(),
        startTime = start / 1000.0,
        endTime = end / 1000.0,
        hasTrailingSpace = content.lastOrNull()?.isWhitespace() == true,
        phonetic = caption,
    )

    /**
     * Sources "transliterate" words that need none (English, or Korean in a Japanese song) as the
     * text itself. A line in the Latin script keeps no captions. Otherwise every word needs one:
     * a line with any caption gets none from the phonetics provider, so copied words in another
     * script are romanized here and Latin words are kept as they are.
     */
    private fun captions(syllables: List<KaraokeSyllable>): List<String?> {
        val text = syllables.joinToString("") { it.content }
        if (!LyricsUtils.needsRomanization(text) || syllables.none { !it.phonetic.isNullOrBlank() }) {
            return syllables.map { null }
        }
        val tag = if (text.any { it in '\u3040'..'\u30FF' }) LyricsPhonetics.JAPANESE else LyricsPhonetics.chineseTag()
        return syllables.map { syllable ->
            val content = syllable.content.trim()
            val source = syllable.phonetic?.takeIf { it.isNotBlank() }
            when {
                content.isEmpty() -> source
                source != null && !sameText(source, content) -> source
                !LyricsUtils.needsRomanization(content) -> content
                else -> LyricsPhonetics.romanize(content, if (content.any(::isHangul)) LyricsPhonetics.KOREAN else tag)
                    .takeIf { it.isNotBlank() && !sameText(it, content) }
            }
        }
    }

    private fun joinedCaption(syllables: List<KaraokeSyllable>, captions: List<String?>): String? = buildString {
        for ((index, syllable) in syllables.withIndex()) {
            val caption = captions[index] ?: continue
            if (isNotEmpty()) append(syllable.phoneticSeparatorBefore.ifEmpty { " " })
            append(caption)
        }
    }.ifBlank { null }

    private fun isHangul(char: Char) = char in '\uAC00'..'\uD7A3' || char in '\u1100'..'\u11FF' || char in '\u3130'..'\u318F'

    private fun sameText(caption: String, text: String) =
        caption.filterNot(Char::isWhitespace) == text.filterNot(Char::isWhitespace)

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
            sourceRomanization = romanization?.takeIf {
                it.isNotBlank() && LyricsUtils.needsRomanization(text) && !sameText(it, text)
            },
        )
    }
}
