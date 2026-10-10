// Modified by Metrolist: syllables reuse their word's raster and text tiles use a smaller margin.
package com.mocharealm.accompanist.lyrics.ui.internal.rendering

import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.text.TextLayoutInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.mocharealm.accompanist.lyrics.ui.internal.diagnostics.traceLyrics
import com.mocharealm.accompanist.lyrics.ui.preparation.PreparedLine
import com.mocharealm.accompanist.lyrics.ui.preparation.PreparedRow
import com.mocharealm.accompanist.lyrics.ui.profile.DefaultLyricsProfiles
import com.mocharealm.accompanist.lyrics.ui.profile.LyricsProfile
import com.mocharealm.accompanist.lyrics.ui.profile.ProfileTextUnit
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Fixed-origin raster tiles are prepared off the UI thread as lines enter the working set. */
internal class PreparedRowLayers(
    density: Density,
    layoutDirection: LayoutDirection,
    row: PreparedRow,
    color: Color,
    paints: RowPaints,
    tileCache: DisplayUnitRasterCache? = null,
) {
    private val atlas = TextAtlas(density, layoutDirection, tileCache)
    val runs =
        Array(row.runs.size) { runIndex ->
            val run = row.runs[runIndex]
            Array(run.groups.size) { groupIndex ->
                val group = run.groups[groupIndex]
                val combined =
                    group.staticText?.let { text -> atlas.text(run.profile, text, color) }
                GroupLayers(
                    combined,
                    Array(group.units.size) { unitIndex ->
                        val unit = group.units[unitIndex]
                        UnitLayers(
                            if (unit.text === group.staticText && combined != null) combined
                            else combined?.sliceFor(run.profile, group.staticText, unit.text)
                                ?: atlas.text(run.profile, unit.text, color),
                            group.effects.glow,
                            unit.phonetic?.let { layout ->
                                atlas.cached(
                                    PhoneticRasterKey(layout.layoutInput, paints.phoneticColor),
                                    layout.size.width.toFloat(),
                                    layout.size.height,
                                    2,
                                ) {
                                    drawText(layout, paints.phoneticColor)
                                }
                            },
                        )
                    },
                )
            }
        }

    val hasGlow = runs.any { run -> run.any { it.hasGlow } }
    val hasPhonetics =
        runs.any { run -> run.any { group -> group.units.any { it.phonetic != null } } }

    init {
        atlas.finish()
    }

    internal val pageCount
        get() = atlas.pageCount

    internal val pages: List<ImageBitmap>
        get() = buildSet {
            for (run in runs) for (group in run) {
                group.combined?.let { add(it.image) }
                for (unit in group.units) {
                    add(unit.text.image)
                    unit.phonetic?.let { add(it.image) }
                }
            }
        }.toList()
}

internal class GroupLayers(val combined: TextLayer?, val units: Array<UnitLayers>) {
    val hasGlow = units.any { it.glow }
}

internal class UnitLayers(val text: TextLayer, val glow: Boolean, val phonetic: TextLayer?)

/**
 * Only records references to already-rasterized pixels; never calls profiles or rasterizes text.
 * Layers are allocated on first animated use, rather than for every unit as a row enters the cache.
 */
internal class RowGlowLayers(
    private val scope: CacheDrawScope,
    private val raster: PreparedRowLayers,
) {
    private val glowLayers =
        Array(raster.runs.size) { r ->
            Array(raster.runs[r].size) { g ->
                arrayOfNulls<GraphicsLayer>(raster.runs[r][g].units.size)
            }
        }
    fun glow(runIndex: Int, groupIndex: Int, unitIndex: Int): GraphicsLayer? {
        val unit = raster.runs[runIndex][groupIndex].units[unitIndex]
        if (!unit.glow) return null
        return glowLayers[runIndex][groupIndex][unitIndex]
            ?: createLayer(unit.text, offscreen = true).also {
                glowLayers[runIndex][groupIndex][unitIndex] = it
            }
    }

    private fun createLayer(tile: TextLayer, offscreen: Boolean): GraphicsLayer =
        scope.obtainGraphicsLayer().apply {
            if (offscreen) compositingStrategy = CompositingStrategy.Offscreen
            val size = IntSize(tile.dimensions.width + 2 * GlowMargin, tile.dimensions.height + 2 * GlowMargin)
            record(scope, scope.layoutDirection, size) {
                translate(GlowMargin.toFloat(), GlowMargin.toFloat()) { with(tile) { drawPixels() } }
            }
        }
}

internal fun DrawScope.drawUnit(
    unit: UnitLayers,
    glow: GraphicsLayer?,
    shadowIndex: Int,
    opacity: Float,
    paints: RowPaints,
) {
    if (shadowIndex > 0 && glow != null) {
        glow.renderEffect = paints.blurEffects[shadowIndex]
        glow.alpha = opacity * 0.4f * shadowIndex / 64f
        translate(unit.text.originX - GlowMargin, unit.text.originY - GlowMargin) { drawLayer(glow) }
    }
    with(unit.text) { draw(opacity) }
}

/** Room for the glow blur around a unit, kept separately so text tiles need only a small margin. */
private const val GlowMargin = 32

/** Margin around text tiles for glyphs that overhang their layout box. */
private const val TextMargin = 12

/**
 * A rectangle of an atlas page. [originX]/[originY] place its top-left corner relative to the text
 * origin. A slice shares its parent's pixels instead of rasterizing the same glyphs again.
 */
internal class TextLayer(
    val source: IntOffset,
    val dimensions: IntSize,
    val padding: Int,
    val originX: Float = -padding.toFloat(),
    val originY: Float = -padding.toFloat(),
    private val parent: TextLayer? = null,
) {
    private var ownImage: ImageBitmap? = null
    var image: ImageBitmap
        get() = parent?.image ?: checkNotNull(ownImage) { "Text tile drawn before its page was rasterized" }
        set(value) {
            ownImage = value
        }
    private val paint = Paint().apply { filterQuality = FilterQuality.Low }

    fun DrawScope.draw(alpha: Float = 1f) {
        translate(originX, originY) { drawPixels(alpha) }
    }

    /** Draws the tile's pixels with their top-left corner at the current origin. */
    fun DrawScope.drawPixels(alpha: Float = 1f) {
        paint.alpha = alpha
        drawContext.canvas.drawImageRect(image, source, dimensions, IntOffset.Zero, dimensions, paint)
    }

    /**
     * The pixels of [unit] inside this tile, which was rasterized for [whole]. Syllables of one word
     * share its text layout, so their glyphs are exactly a slice of the word's raster. Inner edges
     * are cut at the syllable boundary like the syllable's own clip; outer edges keep the margin.
     */
    fun sliceFor(profile: LyricsProfile, whole: ProfileTextUnit?, unit: ProfileTextUnit): TextLayer? {
        if (whole == null || unit.layout !== whole.layout) return null
        if (DefaultLyricsProfiles.none { it === profile }) return null
        val from = unit.left - whole.rasterOriginX()
        val to = unit.right - whole.rasterOriginX()
        val start = if (unit.left <= whole.left) 0 else (padding + from).roundToInt().coerceIn(0, dimensions.width)
        val end =
            if (unit.right >= whole.right) dimensions.width
            else (padding + to).roundToInt().coerceIn(start, dimensions.width)
        if (end <= start) return null
        return TextLayer(
            IntOffset(source.x + start, source.y),
            IntSize(end - start, dimensions.height),
            padding,
            originX = start - padding - from,
            originY = originY,
            parent = this,
        )
    }
}

/** Where [DefaultLyricsProfile] places the layout's x = 0 relative to this unit's raster origin. */
private fun ProfileTextUnit.rasterOriginX(): Float {
    val range = sourceRange
    val coversWholeLayout = range?.min == 0 && range.max == layout.layoutInput.text.length
    return if (coversWholeLayout || (left == 0f && right == layout.size.width.toFloat())) 0f else left
}

/** Reuses display-unit tiles across rows while keeping shared texture pages under a byte budget. */
internal class DisplayUnitRasterCache(private val maxBytes: Long = 8L * 1024 * 1024) {
    private class PageEntry(val bytes: Long) {
        val keys = mutableSetOf<Any>()
    }

    private val tiles = mutableMapOf<Any, TextLayer>()
    private val pages = mutableMapOf<ImageBitmap, PageEntry>()
    private val pageOrder = mutableListOf<ImageBitmap>()
    private var bytes = 0L

    fun get(key: Any): TextLayer? {
        val tile = tiles[key] ?: return null
        return traceLyrics("Lyrics.displayUnitCacheHit") {
            touch(tile.image)
            tile
        }
    }

    fun add(entries: List<Pair<Any, TextLayer>>) {
        for ((key, tile) in entries) {
            val page = tile.image
            val pageEntry =
                pages.getOrPut(page) {
                    val entry = PageEntry(page.width.toLong() * page.height * 4L)
                    bytes += entry.bytes
                    entry
                }
            tiles.put(key, tile)?.let { old -> pages[old.image]?.keys?.remove(key) }
            pageEntry.keys.add(key)
            touch(page)
        }
        trim()
    }

    private fun touch(page: ImageBitmap) {
        pageOrder.remove(page)
        pageOrder.add(page)
    }

    private fun trim() {
        while (bytes > maxBytes && pageOrder.isNotEmpty()) {
            val page = pageOrder.removeAt(0)
            val entry = pages.remove(page) ?: continue
            bytes -= entry.bytes
            for (key in entry.keys) tiles.remove(key)
        }
    }
}

private data class DefaultTextRasterKey(
    val profile: LyricsProfile,
    val layout: TextLayoutInput,
    val left: Float,
    val right: Float,
    val sourceRange: TextRange?,
    val color: Color,
    val shadow: Shadow,
)

private data class PhoneticRasterKey(val layout: TextLayoutInput, val color: Color)

private fun rasterKey(
    profile: LyricsProfile,
    unit: ProfileTextUnit,
    color: Color,
    shadow: Shadow,
): Any? =
    if (DefaultLyricsProfiles.any { it === profile })
        DefaultTextRasterKey(
            profile,
            unit.layout.layoutInput,
            unit.left,
            unit.right,
            unit.sourceRange,
            color,
            shadow,
        )
    else null

/** Shelf packing is performed once during raster preparation, with bounded page dimensions. */
private class TextAtlas(
    private val density: Density,
    private val layoutDirection: LayoutDirection,
    private val tileCache: DisplayUnitRasterCache?,
) {
    private class Entry(
        val tile: TextLayer,
        val padding: Int,
        val cacheKey: Any?,
        val draw: DrawScope.() -> Unit,
    )

    private val entries = mutableListOf<Entry>()
    private val pendingTiles = mutableMapOf<Any, TextLayer>()
    private var x = 0
    private var y = 0
    private var shelfHeight = 0
    private var width = 0
    var pageCount = 0
        private set
    val pages = mutableListOf<ImageBitmap>()

    fun text(
        profile: LyricsProfile,
        text: ProfileTextUnit,
        color: Color,
        shadow: Shadow = Shadow.None,
    ): TextLayer {
        val key = rasterKey(profile, text, color, shadow)
        return if (key == null)
            add(text.width, kotlin.math.ceil(text.height).toInt(), TextMargin) {
                with(profile) { draw(text, color, shadow) }
            }
        else
            cached(key, text.width, kotlin.math.ceil(text.height).toInt(), TextMargin) {
                with(profile) { draw(text, color, shadow) }
            }
    }

    fun cached(
        key: Any,
        textWidth: Float,
        height: Int,
        padding: Int,
        draw: DrawScope.() -> Unit,
    ): TextLayer {
        tileCache?.get(key)?.let { return it }
        pendingTiles[key]?.let { return it }
        return add(textWidth, height, padding, key, draw)
    }

    fun add(
        textWidth: Float,
        height: Int,
        padding: Int,
        cacheKey: Any? = null,
        draw: DrawScope.() -> Unit,
    ): TextLayer {
        val size =
            IntSize(
                ceil(textWidth).toInt().coerceAtLeast(1) + 2 * padding,
                height.coerceAtLeast(1) + 2 * padding,
            )
        if (x > 0 && x + size.width > 2048) {
            y += shelfHeight
            x = 0
            shelfHeight = 0
        }
        if (y + size.height > 2048 && entries.isNotEmpty()) finish()
        val tile = TextLayer(IntOffset(x, y), size, padding)
        entries.add(Entry(tile, padding, cacheKey, draw))
        if (cacheKey != null) pendingTiles[cacheKey] = tile
        x += size.width
        width = maxOf(width, x)
        shelfHeight = maxOf(shelfHeight, size.height)
        return tile
    }

    fun finish() {
        if (entries.isEmpty()) return
        val image = ImageBitmap(width, y + shelfHeight)
        CanvasDrawScope().draw(
            density,
            layoutDirection,
            Canvas(image),
            Size(width.toFloat(), (y + shelfHeight).toFloat()),
        ) {
            for (entry in entries) {
                val tile = entry.tile
                tile.image = image
                clipRect(
                    tile.source.x.toFloat(),
                    tile.source.y.toFloat(),
                    (tile.source.x + tile.dimensions.width).toFloat(),
                    (tile.source.y + tile.dimensions.height).toFloat(),
                ) {
                    translate(
                        (tile.source.x + entry.padding).toFloat(),
                        (tile.source.y + entry.padding).toFloat(),
                        entry.draw,
                    )
                }
            }
        }
        // Upload near the viewport, not while rasterizing every line in the song.
        // Warming the entire song here evicts useful textures before they are drawn.
        pages.add(image)
        pageCount++
        tileCache?.add(
            entries.mapNotNull { entry ->
                entry.cacheKey?.let { it to entry.tile }
            }
        )
        entries.clear()
        pendingTiles.clear()
        x = 0
        y = 0
        shelfHeight = 0
        width = 0
    }
}

internal class PreparedLineRaster(
    val rows: List<PreparedRowLayers>,
) {
    val pages: List<ImageBitmap> = rows.flatMap { it.pages }.distinct()
    val byteCount: Long = pages.sumOf { it.width.toLong() * it.height * 4L }
}

/** Caller prepares off the UI thread and publishes the complete line only after all pages exist. */
internal fun prepareLineRaster(
    line: PreparedLine,
    color: Color,
    density: Density,
    direction: LayoutDirection,
    tileCache: DisplayUnitRasterCache? = null,
): PreparedLineRaster {
    val paints = RowPaints(color)
    return PreparedLineRaster(
        line.rows.map { PreparedRowLayers(density, direction, it, color, paints, tileCache) },
    )
}
