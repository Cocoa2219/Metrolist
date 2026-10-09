/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.constants.AccompanistAdditiveBlendKey
import com.metrolist.music.constants.AccompanistAutoResumeDefault
import com.metrolist.music.constants.AccompanistAutoResumeKey
import com.metrolist.music.constants.AccompanistBlurKey
import com.metrolist.music.constants.AccompanistBlurStrengthDefault
import com.metrolist.music.constants.AccompanistBlurStrengthKey
import com.metrolist.music.constants.AccompanistFocusPositionDefault
import com.metrolist.music.constants.AccompanistFocusPositionKey
import com.metrolist.music.constants.AccompanistFontSizeDefault
import com.metrolist.music.constants.AccompanistFontSizeKey
import com.metrolist.music.constants.AccompanistItemSpacingDefault
import com.metrolist.music.constants.AccompanistItemSpacingKey
import com.metrolist.music.constants.AccompanistLineHeightDefault
import com.metrolist.music.constants.AccompanistLineHeightKey
import com.metrolist.music.constants.AccompanistScrollDurationDefault
import com.metrolist.music.constants.AccompanistScrollDurationKey
import com.metrolist.music.lyrics.LyricsEntry
import com.metrolist.music.utils.rememberPreference
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.ui.composable.list.LyricsLazyListState
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.KaraokeLyricsView
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.LyricsAnchor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import timber.log.Timber

private const val TAG = "AccompanistLyrics"
private const val LAST_LINE_FALLBACK_MS = 5000
private const val CLOCK_SNAP_MS = 500
private const val CLOCK_SMOOTHING_MS = 300.0

@OptIn(FlowPreview::class)
@Composable
fun AccompanistLyricsView(
    lines: List<LyricsEntry>,
    listState: LyricsLazyListState,
    currentPosition: () -> Int,
    isPlaying: () -> Boolean,
    playbackSpeed: () -> Float,
    textColor: Color,
    additiveBlend: Boolean,
    respectAgentPositioning: Boolean,
    showPhonetic: Boolean,
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
                .map {
                    val started = System.nanoTime()
                    lines.toSyncedLyrics(respectAgentPositioning).also {
                        Timber.tag(TAG).d("scene rebuild: %d lines mapped in %.1fms", it.lines.size, (System.nanoTime() - started) / 1e6)
                    }
                }
                .flowOn(Dispatchers.Default)
        }
    }.collectAsStateWithLifecycle(null)

    // The player position advances in coarse steps, which makes syllable fills stutter. Advance on
    // frame time instead and ease toward the player; large differences (seeks) snap.
    val latestPosition by rememberUpdatedState(currentPosition)
    val latestIsPlaying by rememberUpdatedState(isPlaying)
    val latestSpeed by rememberUpdatedState(playbackSpeed)
    val smoothPosition = remember { mutableIntStateOf(currentPosition()) }
    val recompositions = remember { intArrayOf(0) }
    LaunchedEffect(Unit) {
        var predicted = latestPosition().toDouble()
        var lastFrame = withFrameMillis { it }
        // ponytail: diagnostics for the choppy-text report, summarized once a second; remove once resolved
        var windowStart = lastFrame
        var frames = 0
        var maxGap = 0L
        var rawStalls = 0
        var rawBackSteps = 0
        var maxRawStep = 0
        var snaps = 0
        var maxError = 0.0
        var smoothBackSteps = 0
        var lastRaw = latestPosition()
        var lastSmooth = predicted.toInt()
        while (isActive) {
            withFrameMillis { frame ->
                val raw = latestPosition()
                val elapsed = (frame - lastFrame).coerceAtLeast(0L)
                lastFrame = frame
                val playing = latestIsPlaying()
                val rawStep = raw - lastRaw
                lastRaw = raw
                predicted = if (!playing) {
                    raw.toDouble()
                } else {
                    if (rawStep == 0) rawStalls++
                    if (rawStep < 0) rawBackSteps++
                    maxRawStep = maxOf(maxRawStep, abs(rawStep))
                    val advanced = predicted + elapsed * latestSpeed()
                    val error = raw - advanced
                    maxError = maxOf(maxError, abs(error))
                    if (abs(error) > CLOCK_SNAP_MS) {
                        snaps++
                        raw.toDouble()
                    } else {
                        advanced + error * (1 - exp(-elapsed / CLOCK_SMOOTHING_MS))
                    }
                }
                val smooth = predicted.toInt()
                if (smooth < lastSmooth) smoothBackSteps++
                lastSmooth = smooth
                smoothPosition.intValue = smooth

                frames++
                maxGap = maxOf(maxGap, elapsed)
                if (frame - windowStart >= 1000) {
                    if (playing) {
                        Timber.tag(TAG).d(
                            "clock: frames=%d maxFrameGap=%dms rawStalls=%d rawBack=%d maxRawStep=%dms snaps=%d maxError=%.1fms smoothBack=%d speed=%.2f recompositions=%d",
                            frames, maxGap, rawStalls, rawBackSteps, maxRawStep, snaps, maxError, smoothBackSteps, latestSpeed(), recompositions[0],
                        )
                    }
                    windowStart = frame
                    frames = 0; maxGap = 0; rawStalls = 0; rawBackSteps = 0; maxRawStep = 0
                    snaps = 0; maxError = 0.0; smoothBackSteps = 0; recompositions[0] = 0
                }
            }
        }
    }
    val smoothPositionProvider = remember { { smoothPosition.intValue } }
    SideEffect { recompositions[0]++ }

    val fontSize by rememberPreference(AccompanistFontSizeKey, AccompanistFontSizeDefault)
    val lineHeight by rememberPreference(AccompanistLineHeightKey, AccompanistLineHeightDefault)
    val itemSpacing by rememberPreference(AccompanistItemSpacingKey, AccompanistItemSpacingDefault)
    val blurEnabled by rememberPreference(AccompanistBlurKey, true)
    val blurStrength by rememberPreference(AccompanistBlurStrengthKey, AccompanistBlurStrengthDefault)
    val additiveBlendEnabled by rememberPreference(AccompanistAdditiveBlendKey, true)
    val focusPosition by rememberPreference(AccompanistFocusPositionKey, AccompanistFocusPositionDefault)
    val scrollDuration by rememberPreference(AccompanistScrollDurationKey, AccompanistScrollDurationDefault)
    val autoResume by rememberPreference(AccompanistAutoResumeKey, AccompanistAutoResumeDefault)

    // Accompanist sizes the tap highlight to the line itself; a taller, centered line height pads it.
    val baseTextStyle = LocalTextStyle.current
    val normalLineTextStyle = remember(baseTextStyle, fontSize, lineHeight) {
        baseTextStyle.copy(
            fontSize = fontSize.sp,
            lineHeight = lineHeight.em,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
            fontWeight = FontWeight.Bold,
            textMotion = TextMotion.Animated,
        )
    }

    val lyrics = syncedLyrics ?: return
    KaraokeLyricsView(
        listState = listState,
        lyrics = lyrics,
        currentPosition = smoothPositionProvider,
        onLineClicked = { onLineClicked(it.start.toLong()) },
        onLinePressed = { line -> line.text()?.let(onLineLongPressed) },
        modifier = modifier,
        textColor = textColor,
        blendMode = if (additiveBlend && additiveBlendEnabled) BlendMode.Plus else BlendMode.SrcOver,
        showPhonetic = showPhonetic,
        normalLineTextStyle = normalLineTextStyle,
        useBlurEffect = blurEnabled,
        blurDelta = blurStrength,
        itemSpacing = itemSpacing.dp,
        anchor = LyricsAnchor.Fraction(focusPosition),
        scrollAnimationSpec = tween(scrollDuration.roundToInt(), easing = FastOutSlowInEasing),
        // 0 keeps following off after a manual scroll until the user resyncs, like the default renderer.
        autoScrollResumeDelayMillis = if (autoResume > 0f) (autoResume * 1000).toLong() else Long.MAX_VALUE,
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
