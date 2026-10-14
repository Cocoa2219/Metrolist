/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.palette.graphics.Palette
import coil3.ImageLoader
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.metrolist.music.R
import com.metrolist.music.lyrics.LyricsEntry
import com.metrolist.music.models.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_SHARED_LINES = 5

/** Cards are laid out at this size and captured at [CAPTURE_PX], whatever the preview's size. */
private val CardDesignSize = 360.dp
private const val CAPTURE_PX = 2160

/** One lyric line as shared, with what it was showing when the sheet opened. */
data class ShareLine(val text: String, val romanization: String?, val translation: String?)

fun LyricsEntry.toShareLine() = ShareLine(
    text = text,
    romanization = romanizedTextFlow.value?.takeIf { it.isNotBlank() && it != text },
    translation = translatedTextFlow.value?.takeIf { it.isNotBlank() },
)

private data class CardColors(val background: Color, val gradient: Brush?, val text: Color, val secondary: Color)

/**
 * Pick up to [MAX_SHARED_LINES] lines, starting from the pressed one, then share them as text or
 * as a card image. The card shown is the image shared, captured from its own layer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsShareSheet(
    lines: List<ShareLine>,
    initialIndex: Int,
    mediaMetadata: MediaMetadata,
    textAlign: TextAlign,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selected = remember { mutableStateListOf(initialIndex.coerceIn(0, (lines.size - 1).coerceAtLeast(0))) }
    var choosingCard by remember { mutableStateOf(false) }
    val chosen = selected.sorted().mapNotNull(lines::getOrNull)
    val hasRomanization = chosen.any { it.romanization != null }
    val hasTranslation = chosen.any { it.translation != null }
    var showRomanization by remember { mutableStateOf(true) }
    var showTranslation by remember { mutableStateOf(true) }
    val shown = chosen.map {
        it.copy(
            romanization = it.romanization.takeIf { showRomanization },
            translation = it.translation.takeIf { showTranslation },
        )
    }
    val maxReached = stringResource(R.string.max_selection_limit)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Text(stringResource(R.string.share_lyrics), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            if (!choosingCard) {
                val listState = rememberLazyListState(initialFirstVisibleItemIndex = (initialIndex - 2).coerceAtLeast(0))
                LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 420.dp), state = listState) {
                    itemsIndexed(lines) { index, line ->
                        val isSelected = index in selected
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable {
                                    when {
                                        isSelected -> if (selected.size > 1) selected.remove(index)
                                        selected.size >= MAX_SHARED_LINES -> Toast.makeText(context, maxReached, Toast.LENGTH_SHORT).show()
                                        else -> selected.add(index)
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(line.text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            line.romanization?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            line.translation?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            } else {
                ShareCardPicker(shown, mediaMetadata, textAlign) { bitmap, save ->
                    scope.launch {
                        runCatching { if (save) saveToGallery(context, bitmap) else shareImage(context, bitmap) }
                            .onSuccess { if (save) Toast.makeText(context, R.string.saved_to_gallery, Toast.LENGTH_SHORT).show() }
                            .onFailure { Toast.makeText(context, context.getString(R.string.failed_to_create_image, it.message), Toast.LENGTH_SHORT).show() }
                    }
                }
            }
            if (hasRomanization || hasTranslation) {
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hasRomanization) FilterChip(showRomanization, { showRomanization = !showRomanization }, { Text(stringResource(R.string.lyrics_romanize_title)) })
                    if (hasTranslation) FilterChip(showTranslation, { showTranslation = !showTranslation }, { Text(stringResource(R.string.share_lyrics_translation)) })
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (choosingCard) {
                    OutlinedButton({ choosingCard = false }, Modifier.weight(1f)) { Text(stringResource(R.string.back)) }
                } else {
                    OutlinedButton({ copyText(context, shareText(shown, mediaMetadata)) }, Modifier.weight(1f)) { Text(stringResource(R.string.copy)) }
                    OutlinedButton({ shareTextIntent(context, shareText(shown, mediaMetadata)); onDismiss() }, Modifier.weight(1f)) {
                        Text(stringResource(R.string.share_as_text))
                    }
                    Button({ choosingCard = true }, Modifier.weight(1f)) { Text(stringResource(R.string.share_as_image)) }
                }
            }
        }
    }
}

@Composable
private fun ShareCardPicker(
    lines: List<ShareLine>,
    mediaMetadata: MediaMetadata,
    textAlign: TextAlign,
    onCaptured: (Bitmap, save: Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var style by remember { mutableStateOf(LyricsBackgroundStyle.BLUR) }
    val colors = rememberCardColors(mediaMetadata.thumbnailUrl, style)
    val layer = rememberGraphicsLayer()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val preview = minOf(maxWidth, 340.dp)
            CapturedCard(layer, preview) { LyricsShareCard(lines, mediaMetadata, style, colors, textAlign) }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LyricsBackgroundStyle.entries.forEach { option ->
                FilterChip(style == option, { style = option }, {
                    Text(
                        stringResource(
                            when (option) {
                                LyricsBackgroundStyle.SOLID -> R.string.player_background_solid
                                LyricsBackgroundStyle.BLUR -> R.string.player_background_blur
                                LyricsBackgroundStyle.GRADIENT -> R.string.gradient
                            },
                        ),
                    )
                })
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Saving without a storage permission needs scoped storage (Android 10).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                OutlinedButton({ scope.launch { onCaptured(layer.toImageBitmap().asAndroidBitmap(), true) } }, Modifier.weight(1f)) {
                    Text(stringResource(R.string.save_to_gallery))
                }
            }
            Button({ scope.launch { onCaptured(layer.toImageBitmap().asAndroidBitmap(), false) } }, Modifier.weight(1f)) {
                Text(stringResource(R.string.share))
            }
        }
    }
}

/**
 * Lays the card out at [CardDesignSize] with a density that makes it [CAPTURE_PX] wide, records
 * it into [layer] and shows it scaled down to [preview].
 */
@Composable
private fun CapturedCard(layer: GraphicsLayer, preview: Dp, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val scale = with(density) { preview.toPx() } / CAPTURE_PX
    Box(Modifier.size(preview), contentAlignment = Alignment.Center) {
        CompositionLocalProvider(LocalDensity provides Density(CAPTURE_PX / CardDesignSize.value, 1f)) {
            Box(
                Modifier
                    .requiredSize(CardDesignSize)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .drawWithContent {
                        layer.record { this@drawWithContent.drawContent() }
                        drawLayer(layer)
                    },
            ) { content() }
        }
    }
}

@Composable
private fun LyricsShareCard(
    lines: List<ShareLine>,
    mediaMetadata: MediaMetadata,
    style: LyricsBackgroundStyle,
    colors: CardColors,
    textAlign: TextAlign,
) {
    val context = LocalContext.current
    val painter = rememberAsyncImagePainter(ImageRequest.Builder(context).data(mediaMetadata.thumbnailUrl).build())
    val shape = RoundedCornerShape(20.dp)
    Box(Modifier.fillMaxSize().clip(shape).background(colors.background)) {
        when (style) {
            LyricsBackgroundStyle.BLUR -> Image(
                painter, null, Modifier.fillMaxSize().blur(50.dp).background(Color.Black.copy(alpha = 0.3f)),
                contentScale = ContentScale.Crop,
            )
            LyricsBackgroundStyle.GRADIENT -> colors.gradient?.let { Box(Modifier.fillMaxSize().background(it)) }
            LyricsBackgroundStyle.SOLID -> Unit
        }
        Box(Modifier.fillMaxSize().border(1.dp, colors.text.copy(alpha = 0.09f), shape))
        Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter, null,
                    Modifier.size(56.dp).clip(RoundedCornerShape(4.dp)).border(1.dp, colors.text.copy(alpha = 0.16f), RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(mediaMetadata.title, color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(mediaMetadata.artists.joinToString { it.name }, color = colors.secondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // Shorter selections get larger text; captions keep a fixed ratio to it.
            val characters = lines.sumOf { it.text.length + (it.romanization?.length ?: 0) / 2 + (it.translation?.length ?: 0) / 2 }
            val size = when {
                characters < 60 -> 24.sp
                characters < 120 -> 20.sp
                characters < 200 -> 17.sp
                else -> 14.sp
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                horizontalAlignment = when (textAlign) {
                    TextAlign.Center -> Alignment.CenterHorizontally
                    TextAlign.Right, TextAlign.End -> Alignment.End
                    else -> Alignment.Start
                },
            ) {
                lines.forEach { line ->
                    Column(horizontalAlignment = Alignment.Start) {
                        Text(line.text, color = colors.text, fontSize = size, lineHeight = size * 1.2f, fontWeight = FontWeight.Bold, textAlign = textAlign)
                        line.romanization?.let { Text(it, color = colors.secondary, fontSize = size * 0.6f, textAlign = textAlign) }
                        line.translation?.let { Text(it, color = colors.secondary, fontSize = size * 0.6f, textAlign = textAlign) }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(32.dp).clip(RoundedCornerShape(50)).background(colors.secondary), contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.small_icon), null, Modifier.size(28.dp), colorFilter = ColorFilter.tint(colors.background))
                }
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.app_name), color = colors.secondary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun rememberCardColors(thumbnailUrl: String?, style: LyricsBackgroundStyle): CardColors {
    val context = LocalContext.current
    var palette by remember(thumbnailUrl) { mutableStateOf<Palette?>(null) }
    LaunchedEffect(thumbnailUrl) {
        palette = withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context).data(thumbnailUrl).allowHardware(false).build()
                ImageLoader(context).execute(request).image?.toBitmap()?.let { Palette.from(it).generate() }
            }.getOrNull()
        }
    }
    val fallback = Color(0xFF121212)
    val solid = palette?.let { Color(it.getDarkMutedColor(it.getDominantColor(fallback.toArgb()))) } ?: fallback
    val background = if (style == LyricsBackgroundStyle.SOLID) solid else Color.Black
    val text = if (style == LyricsBackgroundStyle.SOLID && solid.luminance() > 0.5f) Color.Black else Color.White
    val gradient = palette?.let {
        Brush.linearGradient(listOf(Color(it.getVibrantColor(solid.toArgb())), Color(it.getDarkVibrantColor(solid.toArgb()))))
    }
    return CardColors(background, gradient, text, text.copy(alpha = 0.7f))
}

private fun shareText(lines: List<ShareLine>, mediaMetadata: MediaMetadata): String = buildString {
    append('"')
    append(lines.joinToString("\n") { line -> listOfNotNull(line.text, line.romanization, line.translation).joinToString("\n") })
    append("\"\n\n")
    append(mediaMetadata.title).append(" - ").append(mediaMetadata.artists.joinToString { it.name })
    append("\nhttps://music.youtube.com/watch?v=").append(mediaMetadata.id)
}

private fun copyText(context: Context, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .setPrimaryClip(ClipData.newPlainText("Lyrics", text))
    Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
}

private fun shareTextIntent(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_lyrics)))
}

/** Shared images go through the cache, so sharing never adds files to the gallery. */
private suspend fun shareImage(context: Context, bitmap: Bitmap) {
    val uri: Uri = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "images").apply { mkdirs() }
        directory.listFiles()?.forEach { it.delete() }
        val file = File(directory, "lyrics_${System.currentTimeMillis()}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        FileProvider.getUriForFile(context, "${context.packageName}.FileProvider", file)
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_lyrics)))
}

private suspend fun saveToGallery(context: Context, bitmap: Bitmap) = withContext(Dispatchers.IO) {
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "lyrics_${System.currentTimeMillis()}.png")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Metrolist")
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: error("MediaStore insert failed")
    context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        ?: error("Could not open $uri")
}
