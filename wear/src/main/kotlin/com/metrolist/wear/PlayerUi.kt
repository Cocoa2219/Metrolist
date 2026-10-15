package com.metrolist.wear

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.graphics.ColorUtils
import androidx.media3.common.Player
import androidx.palette.graphics.Palette
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.material3.LevelIndicator
import androidx.wear.compose.material3.LevelIndicatorDefaults
import androidx.wear.compose.material3.MaterialTheme
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** What the media pages render; rebuilt on every player event since Player itself isn't observable. */
data class NowPlaying(
    val mediaId: String? = null,
    val title: String = "",
    val artist: String = "",
    val artworkUri: Uri? = null,
    val playing: Boolean = false,
    val durationMs: Long = 0L,
)

@Composable
fun rememberNowPlaying(player: Player?): NowPlaying {
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onEvents(
                    player: Player,
                    events: Player.Events,
                ) {
                    revision++
                }
            }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    return remember(player, revision) {
        val metadata = player?.mediaMetadata
        NowPlaying(
            mediaId = player?.currentMediaItem?.mediaId,
            title = metadata?.title?.toString().orEmpty(),
            artist = metadata?.artist?.toString().orEmpty(),
            artworkUri = metadata?.artworkUri,
            playing = player?.playWhenReady == true,
            durationMs = player?.duration?.takeIf { it > 0 } ?: 0L,
        )
    }
}

/** Playback position, polled once a second only while playing and on screen. */
@Composable
fun rememberPositionMs(
    player: Player?,
    nowPlaying: NowPlaying,
): MutableLongState {
    val position = remember { mutableLongStateOf(0L) }
    LaunchedEffect(player, nowPlaying) {
        while (player != null) {
            position.longValue = player.currentPosition
            if (!nowPlaying.playing) break
            delay(1_000)
        }
    }
    return position
}

// Long titles scroll a few times and then rest: an endless marquee keeps the UI redrawing every frame.
const val MARQUEE_PASSES = 3

// Marquees span the full width so their ends pass behind the round edge; the inset (padding applied inside the
// marquee, see call sites) puts the first characters where the circle is visible at that height.

private val VideoThumbnail = Regex("^(https://i\\.ytimg\\.com/vi/[^/]+/)(default|mqdefault|hqdefault|sddefault)\\.jpg(\\?.*)?$")

/**
 * Song art is resized through the URL. Video thumbnails up to sddefault are 4:3 with the letterbox
 * baked in, so they become hq720 (16:9, no bars). ponytail: no fallback if a video lacks hq720;
 * borrow the phone's YouTubeThumbnailInterceptor if that shows up in practice.
 */
fun String.largeThumbnail(): String =
    VideoThumbnail.matchEntire(this)?.let { "${it.groupValues[1]}hq720.jpg" }
        ?: replace(Regex("=w\\d+-h\\d+"), "=w544-h544")

/** A readable-on-black accent picked from the artwork, falling back to the theme primary. */
@Composable
fun rememberArtworkAccent(artworkUri: Uri?): Color {
    val context = LocalContext.current
    val fallback = MaterialTheme.colorScheme.primary
    var accent by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(artworkUri) {
        accent = artworkUri?.let { loadAccent(context, it) }
    }
    return animateColorAsState(accent ?: fallback, label = "accent").value
}

private suspend fun loadAccent(
    context: Context,
    uri: Uri,
): Color? {
    val request = ImageRequest.Builder(context).data(uri).size(96).allowHardware(false).build()
    val bitmap = (context.imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap() ?: return null
    return withContext(Dispatchers.Default) {
        val palette = Palette.from(bitmap).generate()
        val swatch = palette.vibrantSwatch ?: palette.lightVibrantSwatch ?: palette.dominantSwatch ?: return@withContext null
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(swatch.rgb, hsl)
        hsl[1] = hsl[1].coerceAtLeast(0.45f)
        hsl[2] = hsl[2].coerceIn(0.62f, 0.78f)
        Color(ColorUtils.HSLToColor(hsl))
    }
}

/**
 * Bold title that stays bold in scripts whose watch fonts ship only a Regular weight (Japanese and
 * Chinese fall back to SECCJK-Regular), by stroking the glyphs over the fill.
 */
@Composable
fun BoldTitle(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleLarge,
    color: Color = Color.White,
    textAlign: TextAlign? = null,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val strokeWidth = with(LocalDensity.current) { style.fontSize.toPx() * 0.045f }
    val base = style.copy(fontWeight = FontWeight.Bold, color = color)
    Box(modifier) {
        Text(text, style = base, textAlign = textAlign, maxLines = maxLines, overflow = overflow)
        Text(
            text,
            style = base.copy(drawStyle = Stroke(width = strokeWidth, join = StrokeJoin.Round)),
            textAlign = textAlign,
            maxLines = maxLines,
            overflow = overflow,
        )
    }
}

@Stable
class Haptics(private val view: View) {
    fun click() {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    fun tick() {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK,
        )
    }

    fun lightTick() {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK,
        )
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

/** Interaction source that clicks on touch-down; onClick only fires on release, which feels late. */
@Composable
fun rememberPressHaptics(haptics: Haptics): MutableInteractionSource {
    val source = remember { MutableInteractionSource() }
    LaunchedEffect(source, haptics) {
        source.interactions.collect { if (it is PressInteraction.Press) haptics.click() }
    }
    return source
}

private const val ROTARY_PIXELS_PER_VOLUME_STEP = 40f
private const val VOLUME_BAR_VISIBLE_MS = 2_000L

/** Music volume changed without the system volume panel; the left-edge bar stands in for it. */
@Stable
class VolumeState(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    private var rotaryPixels = 0f

    var level by mutableFloatStateOf(current())
        private set
    var lastChangeAt by mutableLongStateOf(0L)
        private set

    private fun current() = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max

    fun step(up: Boolean): Boolean {
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            0,
        )
        level = current()
        lastChangeAt = System.currentTimeMillis()
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC) != before
    }

    fun onRotary(
        pixels: Float,
        haptics: Haptics,
    ) {
        rotaryPixels += pixels
        while (rotaryPixels >= ROTARY_PIXELS_PER_VOLUME_STEP || rotaryPixels <= -ROTARY_PIXELS_PER_VOLUME_STEP) {
            val up = rotaryPixels > 0
            if (step(up)) haptics.lightTick()
            rotaryPixels -= if (up) ROTARY_PIXELS_PER_VOLUME_STEP else -ROTARY_PIXELS_PER_VOLUME_STEP
        }
    }
}

@Composable
fun rememberVolumeState(): VolumeState {
    val context = LocalContext.current
    return remember(context) { VolumeState(context) }
}

/** Bezel → volume on the page that currently has focus in the pager. */
fun Modifier.rotaryVolume(
    volume: VolumeState,
    haptics: Haptics,
): Modifier =
    onRotaryScrollEvent {
        volume.onRotary(it.verticalScrollPixels, haptics)
        true
    }.requestFocusOnHierarchyActive()
        .focusable()

/** A single instance above the pager, so moving between pages never replays it; only a real change shows it. */
@Composable
fun VolumeIndicator(
    volume: VolumeState,
    accent: Color,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(volume.lastChangeAt) {
        if (volume.lastChangeAt == 0L) return@LaunchedEffect
        visible = true
        delay(VOLUME_BAR_VISIBLE_MS)
        visible = false
    }
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        LevelIndicator(
            value = { volume.level },
            modifier = Modifier.fillMaxSize(),
            colors = LevelIndicatorDefaults.colors(indicatorColor = accent),
            strokeWidth = LevelIndicatorDefaults.StrokeWidth * 1.5f,
            sweepAngle = LevelIndicatorDefaults.SweepAngle * 0.6f,
        )
    }
}
