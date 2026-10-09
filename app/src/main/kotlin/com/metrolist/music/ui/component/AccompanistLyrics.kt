/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.lyrics.LyricsEntry
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.ui.composable.list.rememberLyricsLazyListState
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.KaraokeLyricsView
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.LyricsAnchor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

private const val LAST_LINE_FALLBACK_MS = 5000

@OptIn(FlowPreview::class)
@Composable
fun AccompanistLyricsView(
    lines: List<LyricsEntry>,
    currentPosition: () -> Int,
    textColor: Color,
    additiveBlend: Boolean,
    respectAgentPositioning: Boolean,
    showPhonetic: Boolean,
    anchorFraction: Float,
    onLineClicked: (startMs: Long) -> Unit,
    onLineLongPressed: (text: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Translations and romanizations fill in line by line after parsing. Accompanist re-prepares the
    // whole scene for every new SyncedLyrics, so those bursts are debounced into one rebuild.
    val syncedLyrics by remember(lines, respectAgentPositioning) {
        if (lines.isEmpty()) {
            flowOf(null)
        } else {
            combine(lines.flatMap { listOf(it.translatedTextFlow, it.romanizedTextFlow) }) { }
                .debounce(300)
                .map { lines.toSyncedLyrics(respectAgentPositioning) }
                .flowOn(Dispatchers.Default)
        }
    }.collectAsStateWithLifecycle(null)

    val lyrics = syncedLyrics ?: return
    KaraokeLyricsView(
        listState = rememberLyricsLazyListState(),
        lyrics = lyrics,
        currentPosition = currentPosition,
        onLineClicked = { onLineClicked(it.start.toLong()) },
        onLinePressed = { line -> line.text()?.let(onLineLongPressed) },
        modifier = modifier,
        textColor = textColor,
        blendMode = if (additiveBlend) BlendMode.Plus else BlendMode.SrcOver,
        showPhonetic = showPhonetic,
        anchor = LyricsAnchor.Fraction(anchorFraction),
    )
}

private fun ISyncedLine.text(): String? = when (this) {
    is KaraokeLine -> syllables.joinToString("") { it.content }.trim()
    is SyncedLine -> content
    else -> null
}

/**
 * Background vocals attach to the preceding word-synced main line; when there is none they are
 * shown as ordinary lines, since Accompanist only nests accompaniment under karaoke lines.
 */
internal fun List<LyricsEntry>.toSyncedLyrics(respectAgentPositioning: Boolean): SyncedLyrics {
    val result = mutableListOf<ISyncedLine>()
    var lastMainIndex = -1

    forEachIndexed { index, entry ->
        if (entry.text.isBlank()) return@forEachIndexed
        val start = entry.time.toInt()
        val end = (entry.words?.lastOrNull()?.let { (it.endTime * 1000).toInt() }
            ?: subList(index + 1, size).firstOrNull { !it.isBackground }?.time?.toInt()
            ?: (start + LAST_LINE_FALLBACK_MS)).coerceAtLeast(start)
        val translation = entry.translatedTextFlow.value?.takeIf { it.isNotBlank() }
        val phonetic = entry.romanizedTextFlow.value?.takeIf { it.isNotBlank() && it != entry.text }
        val alignment = if (respectAgentPositioning && entry.agent == "v2") KaraokeAlignment.End else KaraokeAlignment.Unspecified
        val syllables = entry.words?.takeIf { it.isNotEmpty() }?.map { word ->
            val wordStart = (word.startTime * 1000).toInt()
            KaraokeSyllable(
                content = if (word.hasTrailingSpace) "${word.text} " else word.text,
                start = wordStart,
                end = (word.endTime * 1000).toInt().coerceAtLeast(wordStart),
            )
        }

        val main = result.getOrNull(lastMainIndex) as? KaraokeLine.MainKaraokeLine
        if (entry.isBackground && syllables != null && main != null) {
            val accompaniment = KaraokeLine.AccompanimentKaraokeLine(
                syllables = syllables.stripParentheses(),
                translation = translation,
                alignment = main.alignment,
                start = start,
                end = end,
                phonetic = phonetic,
            )
            result[lastMainIndex] = main.copy(accompanimentLines = main.accompanimentLines.orEmpty() + accompaniment)
        } else if (syllables != null) {
            result += KaraokeLine.MainKaraokeLine(syllables, translation, alignment, start, end, phonetic)
            lastMainIndex = result.lastIndex
        } else {
            result += SyncedLine(entry.text, translation, start, end, phonetic)
            if (!entry.isBackground) lastMainIndex = -1
        }
    }
    return SyncedLyrics(result)
}

private fun List<KaraokeSyllable>.stripParentheses(): List<KaraokeSyllable> = mapIndexed { i, syllable ->
    var content = syllable.content
    if (i == 0) content = content.removePrefix("(")
    if (i == lastIndex) content = content.trimEnd().removeSuffix(")")
    syllable.copy(content = content)
}
