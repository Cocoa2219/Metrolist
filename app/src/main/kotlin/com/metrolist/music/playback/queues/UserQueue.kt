/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder

/**
 * Songs added with "Play next" / "Add to queue" form a user queue that always plays right after the
 * current song, ahead of the rest of the playing context. Membership is stored on the MediaItem so it
 * survives player swaps (crossfade, player recreation) and can be persisted.
 */
object QueueSection {
    const val NONE = 0
    const val QUEUED = 1
    const val PLAYED = 2

    internal const val EXTRA_KEY = "metrolist_queue_section"
}

val MediaItem.queueSection: Int
    get() = mediaMetadata.extras?.getInt(QueueSection.EXTRA_KEY, QueueSection.NONE) ?: QueueSection.NONE

val MediaItem.isUserQueued: Boolean
    get() = queueSection == QueueSection.QUEUED

fun MediaItem.withQueueSection(section: Int): MediaItem {
    if (queueSection == section) return this
    val extras =
        Bundle(mediaMetadata.extras ?: Bundle.EMPTY).apply {
            if (section == QueueSection.NONE) remove(QueueSection.EXTRA_KEY) else putInt(QueueSection.EXTRA_KEY, section)
        }
    return buildUpon()
        .setMediaMetadata(mediaMetadata.buildUpon().setExtras(extras).build())
        .build()
}

/** Window indices in playback order, honouring the shuffle order. */
fun Player.playbackOrder(): IntArray {
    val timeline = currentTimeline
    if (timeline.isEmpty) return IntArray(0)
    val order = IntArray(timeline.windowCount)
    var pos = 0
    var index = timeline.getFirstWindowIndex(shuffleModeEnabled)
    while (index != C.INDEX_UNSET && pos < order.size) {
        order[pos++] = index
        index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffleModeEnabled)
    }
    return if (pos == order.size) order else order.copyOf(pos)
}

/** Size of the user queue, which [arrangeUserQueue] keeps at indices current+1..current+n. */
fun Player.userQueueSize(): Int {
    val current = currentMediaItemIndex
    if (current == C.INDEX_UNSET) return 0
    var count = 0
    while (current + 1 + count < mediaItemCount && getMediaItemAt(current + 1 + count).isUserQueued) count++
    return count
}

fun Player.upcomingUserQueue(): List<MediaItem> {
    val current = currentMediaItemIndex
    return List(userQueueSize()) { getMediaItemAt(current + 1 + it) }
}

/**
 * Restores the layout `[history] [anchor] [user queue] [context]`, where the anchor is normally the
 * current item.
 *
 * User-queued items are moved to sit physically right after the anchor. With shuffle on, the shuffle
 * order is rebuilt so they also play next while history and the remaining context keep their order, or
 * the context is reordered by [reshuffle] when given.
 *
 * @param anchor item the user queue should follow; pass the item about to be skipped to so the queue
 *   survives the jump.
 * @param consumeHistory mark user-queued items already behind the current item as played.
 * @param queueOrderFromPlayback take the user queue order from the playback order instead of the
 *   physical order, used after the shuffle order was edited directly.
 * @param reshuffle receives the context indices still to play and returns them in their new order.
 *   History is then reduced to items played from the user queue, so they don't come back.
 * @return the index of [anchor] after the rearrangement.
 */
fun ExoPlayer.arrangeUserQueue(
    anchor: Int = currentMediaItemIndex,
    consumeHistory: Boolean = false,
    queueOrderFromPlayback: Boolean = false,
    reshuffle: ((List<Int>) -> List<Int>)? = null,
): Int {
    val current = currentMediaItemIndex
    val count = mediaItemCount
    if (anchor == C.INDEX_UNSET || anchor >= count || current == C.INDEX_UNSET) return anchor
    val order = playbackOrder()
    val anchorPos = order.indexOf(anchor)
    if (anchorPos < 0) return anchor

    if (consumeHistory) {
        for (pos in 0 until order.indexOf(current)) {
            val item = getMediaItemAt(order[pos])
            if (item.isUserQueued) replaceMediaItem(order[pos], item.withQueueSection(QueueSection.PLAYED))
        }
    }

    val queued =
        (if (queueOrderFromPlayback) order.asList() else (0 until count).toList())
            .filter { it != anchor && it != current && getMediaItemAt(it).isUserQueued }

    // Simulate the moves so that indices captured before them can still be resolved afterwards.
    val physical = MutableList(count) { it }
    queued.forEachIndexed { i, original ->
        val from = physical.indexOf(original)
        val anchorAt = physical.indexOf(anchor)
        val to = (if (from < anchorAt) anchorAt - 1 else anchorAt) + 1 + i
        if (from != to) {
            moveMediaItem(from, to)
            physical.add(to, physical.removeAt(from))
        }
    }
    val newIndexOf = IntArray(count)
    physical.forEachIndexed { index, original -> newIndexOf[original] = index }

    if (!shuffleModeEnabled) return newIndexOf[anchor]

    val queuedSet = queued.toHashSet()
    val desired = ArrayList<Int>(count)
    if (reshuffle == null) {
        val history = order.take(anchorPos).filter { it !in queuedSet }
        val context = order.drop(anchorPos + 1).filter { it !in queuedSet }
        (history + anchor + queued + context).mapTo(desired) { newIndexOf[it] }
    } else {
        val (played, context) =
            physical.indices
                .filter { physical[it] != anchor && physical[it] !in queuedSet }
                .partition { getMediaItemAt(it).queueSection == QueueSection.PLAYED }
        desired += played
        desired += newIndexOf[anchor]
        queued.mapTo(desired) { newIndexOf[it] }
        desired += reshuffle(context)
    }

    if (desired.size == count && !desired.toIntArray().contentEquals(playbackOrder())) {
        setShuffleOrder(DefaultShuffleOrder(desired.toIntArray(), System.currentTimeMillis()))
    }
    return newIndexOf[anchor]
}
