/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.IntState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.Preferences
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
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
import com.metrolist.music.constants.AccompanistSungLineOpacityDefault
import com.metrolist.music.constants.AccompanistSungLineOpacityKey
import com.metrolist.music.lyrics.LyricsEntry
import com.metrolist.music.lyrics.LyricsPhonetics
import com.metrolist.music.utils.dataStore
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.core.utils.PhoneticProvider
import com.mocharealm.accompanist.lyrics.core.utils.PhoneticRequest
import com.mocharealm.accompanist.lyrics.core.utils.PhoneticResponse
import com.mocharealm.accompanist.lyrics.core.utils.withPhonetics
import com.mocharealm.accompanist.lyrics.ui.composable.list.LyricsLazyListState
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.KaraokeBreathingDotsDefaults
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.KaraokeLyricsView
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.LyricsAnchor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlin.math.roundToInt

private const val LAST_LINE_FALLBACK_MS = 5000
private const val CLOCK_RESAMPLE_NANOS = 1_000_000_000L
private const val CLOCK_HOLD_LIMIT_MS = 1000

/** Long enough for Accompanist's caption reveal spring to settle. */
private const val CAPTION_HIDE_MS = 1000L

/**
 * Playback position for Accompanist, after its sample's PlaybackClock: the player is sampled on its
 * events (and once a second while playing) and extrapolated on frame time, so frames are only
 * requested while playing. Accompanist treats any backward step as a seek and restarts its follow
 * scroll, so small player corrections are held; only discontinuities and overrides step back.
 */
@Composable
fun rememberAccompanistPosition(player: Player, overridePosition: () -> Long?): IntState {
    val latestOverride by rememberUpdatedState(overridePosition)
    val position = remember(player) {
        mutableIntStateOf(runCatching { player.currentPosition.toInt() }.getOrDefault(0))
    }
    LaunchedEffect(player) {
        // Set by seeks and transitions; consumed by the next sample so a stale frame can't claim it.
        var discontinuity = false
        fun show(candidate: Long, jump: Boolean) {
            position.intValue = heldPosition(position.intValue, candidate, jump)
        }
        val playerEvents = callbackFlow<Unit> {
            val listener = object : Player.Listener {
                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    discontinuity = true
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    discontinuity = true
                }

                override fun onEvents(player: Player, events: Player.Events) {
                    trySend(Unit)
                }
            }
            player.addListener(listener)
            awaitClose { player.removeListener(listener) }
        }
        merge(playerEvents, snapshotFlow { latestOverride() }.map { }).collectLatest {
            val override = latestOverride()
            val jump = discontinuity || override != null
            discontinuity = false
            if (override != null) {
                show(override, jump)
                return@collectLatest
            }
            var sampled = player.currentPosition
            var sampledAt = System.nanoTime()
            show(sampled, jump)
            if (!player.isPlaying) return@collectLatest
            val speed = player.playbackParameters.speed
            // Runs until the next player event or override restarts the collection.
            while (true) {
                val frame = withFrameNanos { it }
                if (frame - sampledAt >= CLOCK_RESAMPLE_NANOS) {
                    sampled = player.currentPosition
                    sampledAt = System.nanoTime()
                }
                show(sampled + ((frame - sampledAt).coerceAtLeast(0L) * speed / 1_000_000f).toLong(), jump = false)
            }
        }
    }
    return position
}

/** Small backward steps keep [previous] unless the step is a [jump] (seek, transition, override). */
internal fun heldPosition(previous: Int, candidate: Long, jump: Boolean): Int {
    val next = candidate.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    return if (!jump && next < previous && previous - next < CLOCK_HOLD_LIMIT_MS) previous else next
}

@OptIn(FlowPreview::class)
@Composable
fun AccompanistLyricsView(
    lines: List<LyricsEntry>,
    listState: LyricsLazyListState,
    currentPosition: () -> Int,
    textColor: Color,
    additiveBlend: Boolean,
    respectAgentPositioning: Boolean,
    showPhonetic: Boolean,
    onLineClicked: (startMs: Long) -> Unit,
    onLineLongPressed: (startMs: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Translations and romanizations fill in line by line after parsing. Accompanist re-prepares the
    // whole scene for every new SyncedLyrics, so those bursts are debounced into one rebuild.
    // Cleared translations stay in the scene and are only hidden, so turning them off animates the
    // rows away like romanization does instead of rebuilding the lyrics.
    // Word captions widen their words even while hidden, so they leave the scene once hidden.
    val phoneticsInScene = remember { MutableStateFlow(showPhonetic) }
    LaunchedEffect(showPhonetic) {
        if (!showPhonetic) delay(CAPTION_HIDE_MS)
        phoneticsInScene.value = showPhonetic
    }
    val content by remember(lines, respectAgentPositioning) {
        if (lines.isEmpty()) {
            flowOf(null)
        } else {
            val keptTranslations = arrayOfNulls<String>(lines.size)
            val phonetics = CachedPhonetics(LyricsPhonetics)
            combine(lines.flatMap { listOf(it.translatedTextFlow, it.romanizedTextFlow) }) { }
                .debounce(300)
                .combine(phoneticsInScene) { _, captioned -> captioned }
                .map { captioned ->
                    var anyTranslation = false
                    lines.forEachIndexed { i, entry ->
                        entry.translatedTextFlow.value?.takeIf { it.isNotBlank() }?.let {
                            keptTranslations[i] = it
                            anyTranslation = true
                        }
                    }
                    val lyrics = lines.toSyncedLyrics(respectAgentPositioning, keptTranslations, captioned)
                    (if (captioned) lyrics.withPhonetics(phonetics) else lyrics) to anyTranslation
                }
                .flowOn(Dispatchers.Default)
        }
    }.collectAsStateWithLifecycle(null)

    // One snapshot of every setting, and nothing drawn until it has loaded: per-key preferences start
    // at their defaults, which would prepare and rasterize the whole scene twice when the player opens.
    val context = LocalContext.current
    val settings by remember {
        context.dataStore.data.map { AccompanistViewSettings(it) }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(null)

    // Accompanist sizes the tap highlight to the line itself; a taller, centered line height pads it.
    val baseTextStyle = LocalTextStyle.current
    val normalLineTextStyle = remember(baseTextStyle, settings?.fontSize, settings?.lineHeight) {
        baseTextStyle.copy(
            fontSize = (settings?.fontSize ?: AccompanistFontSizeDefault).sp,
            lineHeight = (settings?.lineHeight ?: AccompanistLineHeightDefault).em,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
            fontWeight = FontWeight.Bold,
            textMotion = TextMotion.Animated,
        )
    }

    // New translations arrive with their scene already shown, as if the lyrics came with them.
    val (lyrics, hasTranslation) = content ?: return
    val s = settings ?: return
    KaraokeLyricsView(
        listState = listState,
        lyrics = lyrics,
        currentPosition = currentPosition,
        onLineClicked = { onLineClicked(it.start.toLong()) },
        onLinePressed = { onLineLongPressed(it.start.toLong()) },
        modifier = modifier,
        textColor = textColor,
        breathingDotsDefaults = remember(textColor) { KaraokeBreathingDotsDefaults(breathingDotsColor = textColor) },
        blendMode = if (additiveBlend && s.additiveBlend) BlendMode.Plus else BlendMode.SrcOver,
        showPhonetic = showPhonetic,
        showTranslation = hasTranslation,
        normalLineTextStyle = normalLineTextStyle,
        useBlurEffect = s.blur,
        blurDelta = s.blurStrength,
        sungLineAlpha = s.sungLineOpacity,
        lineHighlightPadding = 8.dp,
        itemSpacing = s.itemSpacing.dp,
        anchor = LyricsAnchor.Fraction(s.focusPosition),
        scrollAnimationSpec = tween(s.scrollDuration.roundToInt(), easing = FastOutSlowInEasing),
        // 0 keeps following off after a manual scroll until the user resyncs, like the default renderer.
        autoScrollResumeDelayMillis = if (s.autoResume > 0f) (s.autoResume * 1000).toLong() else Long.MAX_VALUE,
    )
}

private data class AccompanistViewSettings(
    val fontSize: Float,
    val lineHeight: Float,
    val itemSpacing: Float,
    val blur: Boolean,
    val blurStrength: Float,
    val additiveBlend: Boolean,
    val sungLineOpacity: Float,
    val focusPosition: Float,
    val scrollDuration: Float,
    val autoResume: Float,
) {
    constructor(prefs: Preferences) : this(
        fontSize = prefs[AccompanistFontSizeKey] ?: AccompanistFontSizeDefault,
        lineHeight = prefs[AccompanistLineHeightKey] ?: AccompanistLineHeightDefault,
        itemSpacing = prefs[AccompanistItemSpacingKey] ?: AccompanistItemSpacingDefault,
        blur = prefs[AccompanistBlurKey] ?: true,
        blurStrength = prefs[AccompanistBlurStrengthKey] ?: AccompanistBlurStrengthDefault,
        additiveBlend = prefs[AccompanistAdditiveBlendKey] ?: true,
        sungLineOpacity = prefs[AccompanistSungLineOpacityKey] ?: AccompanistSungLineOpacityDefault,
        focusPosition = prefs[AccompanistFocusPositionKey] ?: AccompanistFocusPositionDefault,
        scrollDuration = prefs[AccompanistScrollDurationKey] ?: AccompanistScrollDurationDefault,
        autoResume = prefs[AccompanistAutoResumeKey] ?: AccompanistAutoResumeDefault,
    )
}

/**
 * Background vocals attach to the preceding word-synced main line; when there is none they are
 * shown as ordinary lines, since Accompanist only nests accompaniment under karaoke lines.
 */
internal fun List<LyricsEntry>.toSyncedLyrics(
    respectAgentPositioning: Boolean,
    translations: Array<String?>,
    wordCaptions: Boolean = true,
): SyncedLyrics {
    val result = mutableListOf<ISyncedLine>()
    var lastMainIndex = -1
    val japaneseSong = any { entry -> entry.text.any(::isKana) }

    forEachIndexed { index, entry ->
        if (entry.text.isBlank()) return@forEachIndexed
        val start = entry.time.toInt()
        val end = (entry.words?.lastOrNull()?.let { (it.endTime * 1000).toInt() }
            ?: subList(index + 1, size).firstOrNull { !it.isBackground }?.time?.toInt()
            ?: (start + LAST_LINE_FALLBACK_MS)).coerceAtLeast(start)
        val translation = translations[index]
        val romanized = entry.romanizedTextFlow.value?.takeIf { it.isNotBlank() && it != entry.text }
        // A romanized CJK or Korean line is captioned later, word by word, by the phonetics engine.
        // A line timed as one piece keeps its line caption: Accompanist splits it by script for
        // display, which would leave a word caption under its first part once the line wraps.
        val languageTag = romanized?.let { phoneticLanguageTag(entry.text, japaneseSong) }
        val phonetic = romanized.takeIf { languageTag == null || entry.words?.size == 1 }
        val alignment = if (respectAgentPositioning && entry.agent == "v2") KaraokeAlignment.End else KaraokeAlignment.Unspecified
        // The source's own word romanization (TTML), under the same conditions as generated ones.
        val sourceWordCaptions = wordCaptions && romanized != null && (entry.words?.size ?: 0) > 1
        val syllables = entry.words?.takeIf { it.isNotEmpty() }?.map { word ->
            val wordStart = (word.startTime * 1000).toInt()
            KaraokeSyllable(
                content = if (word.hasTrailingSpace) "${word.text} " else word.text,
                start = wordStart,
                end = (word.endTime * 1000).toInt().coerceAtLeast(wordStart),
                phonetic = word.phonetic.takeIf { sourceWordCaptions },
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
                languageTag = languageTag,
            )
            result[lastMainIndex] = main.copy(accompanimentLines = main.accompanimentLines.orEmpty() + accompaniment)
        } else if (syllables != null) {
            result += KaraokeLine.MainKaraokeLine(syllables, translation, alignment, start, end, phonetic, languageTag = languageTag)
            lastMainIndex = result.lastIndex
        } else {
            result += SyncedLine(entry.text, translation, start, end, phonetic, languageTag)
            if (!entry.isBackground) lastMainIndex = -1
        }
    }
    return SyncedLyrics(result)
}

private fun isKana(char: Char) = char in '\u3040'..'\u30FF'

private fun isHan(char: Char) = char in '\u3400'..'\u4DBF' || char in '\u4E00'..'\u9FFF' || char in '\uF900'..'\uFAFF'

/** Han characters are read as Japanese when the song uses kana anywhere, as the classic view does. */
private fun phoneticLanguageTag(text: String, japaneseSong: Boolean): String? = when {
    text.any(::isKana) || (japaneseSong && text.any(::isHan)) -> LyricsPhonetics.JAPANESE
    text.any(::isHan) -> LyricsPhonetics.chineseTag()
    text.any { it in '\uAC00'..'\uD7A3' || it in '\u1100'..'\u11FF' || it in '\u3130'..'\u318F' } -> LyricsPhonetics.KOREAN
    else -> null
}

/** Scenes are rebuilt as translations arrive; readings for unchanged lines are reused. */
private class CachedPhonetics(private val delegate: PhoneticProvider) : PhoneticProvider by delegate {
    private val responses = HashMap<PhoneticRequest, PhoneticResponse>()
    override fun resolve(request: PhoneticRequest): PhoneticResponse =
        responses.getOrPut(request) { delegate.resolve(request) }
}

private fun List<KaraokeSyllable>.stripParentheses(): List<KaraokeSyllable> = mapIndexed { i, syllable ->
    var content = syllable.content
    if (i == 0) content = content.removePrefix("(")
    if (i == lastIndex) content = content.trimEnd().removeSuffix(")")
    syllable.copy(content = content)
}
