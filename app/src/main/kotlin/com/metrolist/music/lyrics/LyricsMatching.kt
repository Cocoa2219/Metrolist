/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import android.content.Context
import com.metrolist.music.constants.LyricsDurationToleranceDefault
import com.metrolist.music.constants.LyricsDurationToleranceKey
import com.metrolist.music.constants.LyricsRequireDurationMatchKey
import com.metrolist.music.constants.lyricsDurationToleranceKey
import com.metrolist.music.constants.lyricsMatchOverrideKey
import com.metrolist.music.constants.lyricsRequireDurationMatchKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import kotlin.math.abs

/** How close a search result's length must be to the song's for a provider to accept it. */
data class DurationMatch(val required: Boolean, val toleranceSeconds: Int) {
    /** Unknown lengths on either side never rule a result out. */
    fun accepts(expectedSeconds: Int, foundSeconds: Number?): Boolean =
        !required || expectedSeconds <= 0 || foundSeconds == null ||
            abs(foundSeconds.toDouble() - expectedSeconds) <= toleranceSeconds

    /** For sources that skip their duration check when given -1. */
    fun durationFor(expectedSeconds: Int): Int = if (required) expectedSeconds else -1
}

/** Providers whose matching uses a duration, in settings order. */
val DurationMatchedProviders = listOf("LrcRed", "Paxsenix", "LrcLib", "Kugou", "Unison")

/** The provider's own rule if the user set one, otherwise the global rule. */
fun Context.durationMatch(provider: String): DurationMatch {
    val custom = dataStore[lyricsMatchOverrideKey(provider)] == true
    val required = dataStore[if (custom) lyricsRequireDurationMatchKey(provider) else LyricsRequireDurationMatchKey] ?: true
    val tolerance = dataStore[if (custom) lyricsDurationToleranceKey(provider) else LyricsDurationToleranceKey]
        ?: dataStore[LyricsDurationToleranceKey] ?: LyricsDurationToleranceDefault
    return DurationMatch(required, tolerance.toInt())
}
