/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// Adapted from the Accompanist sample's AndroidPhoneticProvider
// (https://github.com/6xingyv/accompanist-lyrics-ui, Apache-2.0).

package com.metrolist.music.lyrics

import android.content.Context
import android.icu.text.Transliterator
import android.os.Build
import com.metrolist.music.constants.ChineseReading
import com.mocharealm.accompanist.lyrics.core.model.karaoke.PhoneticLevel
import com.mocharealm.accompanist.lyrics.core.utils.PhoneticProjection
import com.mocharealm.accompanist.lyrics.core.utils.PhoneticProvider
import com.mocharealm.accompanist.lyrics.core.utils.PhoneticRequest
import com.mocharealm.accompanist.lyrics.core.utils.PhoneticResponse
import com.mocharealm.accompanist.lyrics.phonetics.AndroidPackLoader
import com.mocharealm.accompanist.lyrics.phonetics.AsciiFormatter
import com.mocharealm.accompanist.lyrics.phonetics.CjkPhoneticProfile
import com.mocharealm.accompanist.lyrics.phonetics.FormatOptions
import com.mocharealm.accompanist.lyrics.phonetics.LanguageHint
import com.mocharealm.accompanist.lyrics.phonetics.LanguageResult
import com.mocharealm.accompanist.lyrics.phonetics.PackLoader
import com.mocharealm.accompanist.lyrics.phonetics.ParseOptions
import com.mocharealm.accompanist.lyrics.phonetics.ParseWorkspace
import com.mocharealm.accompanist.lyrics.phonetics.PhoneticEngine
import com.mocharealm.accompanist.lyrics.phonetics.PhoneticProfile
import com.mocharealm.accompanist.lyrics.phonetics.PhoneticSpan
import com.mocharealm.accompanist.lyrics.phonetics.Pronunciation
import com.mocharealm.accompanist.lyrics.phonetics.PronunciationFormatter
import com.mocharealm.accompanist.lyrics.phonetics.ReadingCandidate
import com.mocharealm.accompanist.lyrics.phonetics.ReadingUnit
import com.mocharealm.accompanist.lyrics.phonetics.Resolution
import com.mocharealm.accompanist.lyrics.phonetics.SharedPackLoader
import com.mocharealm.accompanist.lyrics.phonetics.TextRange
import com.mocharealm.accompanist.lyrics.phonetics.data.CantoneseData
import com.mocharealm.accompanist.lyrics.phonetics.data.JapaneseData
import com.mocharealm.accompanist.lyrics.phonetics.data.MandarinData
import com.mocharealm.accompanist.lyrics.phonetics.isHangulCodePoint
import java.util.Locale

/**
 * Dictionary-based readings for Japanese and Chinese, and ICU transliteration for Korean.
 * Language packs are memory-mapped from the APK on first use, so idle languages cost nothing.
 *
 * As a [PhoneticProvider] it only captions text that carries a language hint, which is how
 * callers choose the lines whose language the user enabled for romanization.
 */
object LyricsPhonetics : PhoneticProvider {
    const val KOREAN = "ko"
    const val JAPANESE = "ja"

    @Volatile private var loader: PackLoader? = null

    @Volatile var chineseReading: ChineseReading = ChineseReading.MANDARIN

    fun initialize(context: Context) {
        synchronized(this) { if (loader == null) loader = SharedPackLoader(AndroidPackLoader(context)) }
    }

    private val engine by lazy {
        val packs = checkNotNull(loader) { "LyricsPhonetics.initialize must be called first" }
        val cjk = CjkPhoneticProfile(
            listOf(
                MandarinData.mainland(packs),
                MandarinData.taiwan(packs),
                CantoneseData.hongKong(packs),
                JapaneseData.japanese(packs),
            ),
        )
        PhoneticEngine(profiles = listOf(cjk, HangulProfile))
    }

    /** Line captions keep Latin words and punctuation, since they may replace the original line. */
    private val lineFormatter = AsciiFormatter(FormatOptions(), listOf(HangulFormatter))

    /** Syllable captions keep Latin words too, so a mixed line is romanized throughout. */
    private val syllableFormatter = lineFormatter

    fun chineseTag(): String = chineseReading.languageTag

    /** Romanizes a whole line; [languageTag] decides how Han characters are read. */
    fun romanize(text: String, languageTag: String?): String {
        if (text.isBlank()) return text
        val hints = languageTag?.let { listOf(LanguageHint(TextRange(0, text.length), it)) }.orEmpty()
        return lineFormatter.format(engine.parse(text, ParseOptions(hints = hints))).latin
    }

    override val phoneticLevel = PhoneticLevel.LINE

    override fun getPhonetic(string: String): String = getPhonetic(string, null)

    override fun getPhonetic(string: String, languageTag: String?): String =
        languageTag?.let { romanize(string, it) }.orEmpty()

    override fun resolve(request: PhoneticRequest): PhoneticResponse {
        if (request.hints.isEmpty()) return PhoneticResponse(null)
        val parsed = engine.parse(
            request.text,
            ParseOptions(hints = request.hints.map { LanguageHint(TextRange(it.range.start, it.range.end), it.languageTag) }),
        )
        val ranges = request.projectionRanges.map { TextRange(it.start, it.end) }
        val complete = ranges.firstOrNull()?.start == 0 && ranges.lastOrNull()?.end == request.text.length &&
            ranges.zipWithNext().all { (a, b) -> a.end == b.start }
        val projections = when {
            ranges.isEmpty() -> emptyList()
            complete -> syllableFormatter.formatRanges(parsed, ranges)
                ?.map { PhoneticProjection(it.latin.ifBlank { null }, separatorBefore = it.separatorBefore) }
                ?: ranges.map { PhoneticProjection(null, aligned = false) }
            else -> ranges.map {
                val latin = syllableFormatter.formatRange(parsed, it)
                PhoneticProjection(latin?.ifBlank { null }, aligned = latin != null)
            }
        }
        return PhoneticResponse(syllableFormatter.format(parsed).latin.ifBlank { null }, projections)
    }

    private val icuHangul: Transliterator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Transliterator.getInstance("Hangul-Latin; Latin-ASCII") else null
    }

    private fun romanizeHangul(text: String): String =
        icuHangul?.let { synchronized(it) { it.transliterate(text).lowercase(Locale.ROOT) } }
            ?: LyricsUtils.romanizeHangulRules(text)

    /** Keeps each Hangul run whole; ICU has no per-character alignment, so it only formats runs. */
    private object HangulProfile : PhoneticProfile {
        override fun matches(codePoint: Int): Boolean = isHangulCodePoint(codePoint)

        override fun resolve(text: String, range: TextRange, options: ParseOptions, workspace: ParseWorkspace): LanguageResult {
            val reading = ReadingCandidate(
                Pronunciation(null, listOf(ReadingUnit(range.textIn(text), sourceRange = range)), notation = NOTATION, languageTag = KOREAN),
                sourceIds = listOf("android-icu"),
            )
            return LanguageResult(listOf(PhoneticSpan(range, null, Resolution.RESOLVED, reading, listOf(reading))))
        }
    }

    private object HangulFormatter : PronunciationFormatter {
        override val notation = NOTATION
        override fun format(pronunciation: Pronunciation, options: FormatOptions): String =
            romanizeHangul(pronunciation.units.joinToString("") { it.value })
    }

    private const val NOTATION = "hangul"
}
