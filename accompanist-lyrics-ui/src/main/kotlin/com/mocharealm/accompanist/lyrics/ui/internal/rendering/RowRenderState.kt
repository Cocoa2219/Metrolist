// Modified by Metrolist: captions sweep on a track of their own.
package com.mocharealm.accompanist.lyrics.ui.internal.rendering

import androidx.compose.ui.graphics.*
import com.mocharealm.accompanist.lyrics.ui.preparation.PreparedRow

/** Mutable draw cursor and reusable paints belong to one rendering host. */
internal class RowRenderState(private val row: PreparedRow) {
    private val textSweep = SweepTrack(row.sweepStarts, row.sweepEnds, row.sweepLeft, row.sweepRight)
    private val captionSweep =
        SweepTrack(row.captionSweepStarts, row.captionSweepEnds, row.captionSweepLeft, row.captionSweepRight)
    internal val layerPaint = Paint()
    internal val mask: Brush =
        Brush.horizontalGradient(
            if (row.rtl) listOf(Color.White.copy(alpha = FocusedRowUnlitAlpha), Color.White)
            else listOf(Color.White, Color.White.copy(alpha = FocusedRowUnlitAlpha)),
            startX = -row.sweepFadeWidth / 2f,
            endX = row.sweepFadeWidth / 2f,
        )

    internal fun sweepCenter(now: Int): Float = textSweep.center(now)

    /** Falls back to the text sweep for rows whose captions were not split per word. */
    internal fun captionSweepCenter(now: Int): Float =
        if (row.captionSweepStarts.isEmpty()) textSweep.center(now) else captionSweep.center(now)

    private inner class SweepTrack(
        private val starts: IntArray,
        private val ends: IntArray,
        private val lefts: FloatArray,
        private val rights: FloatArray,
    ) {
        private var cursor = 0
        private var previousTime = Int.MIN_VALUE

        fun center(now: Int): Float = with(row) {
            if (starts.isEmpty()) return bounds.left
            if (now < previousTime || now.toLong() - previousTime > 1000L) {
                var low = 0
                var high = starts.size
                while (low < high) {
                    val middle = (low + high) ushr 1
                    if (starts[middle] <= now) low = middle + 1 else high = middle
                }
                cursor = (low - 1).coerceAtLeast(0)
            }
            while (cursor < starts.lastIndex && now >= starts[cursor + 1]) cursor++
            previousTime = now
            val i = cursor
            val p =
                if (ends[i] <= starts[i]) {
                    if (now >= starts[i]) 1f else 0f
                } else
                    ((now.toLong() - starts[i]).toFloat() / (ends[i].toLong() - starts[i]).toFloat())
                        .coerceIn(0f, 1f)
            val halfFade = sweepFadeWidth / 2f
            // The first/last timing units own the extra half-fade travel, within their
            // original durations. A single unit owns both extensions. RTL mirrors geometry,
            // not time: its center moves from the right side towards the left.
            val direction = if (rtl) -1f else 1f
            val from = (if (rtl) rights[i] else lefts[i]) - if (i == 0) direction * halfFade else 0f
            val to = (if (rtl) lefts[i] else rights[i]) + if (i == starts.lastIndex) direction * halfFade else 0f
            return from + (to - from) * p
        }
    }
}
