/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import android.content.Context
import com.metrolist.music.constants.LyricsAlbumFallbackKey
import com.metrolist.music.constants.LyricsDurationToleranceDefault
import com.metrolist.music.constants.LyricsEnglishNameRetryKey
import com.metrolist.music.constants.LyricsTitlePartSearchKey
import com.metrolist.music.constants.lyricsDurationToleranceKey
import com.metrolist.music.constants.lyricsMatchOverrideKey
import com.metrolist.music.constants.lyricsMatchRuleKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get

/** Reads the matching preferences the lyrics matching settings screen writes. */
class DataStoreLyricsMatchSettings(context: Context) : LyricsMatchSettings {
    private val dataStore = context.applicationContext.dataStore

    /** The provider's own rules if the user set them, otherwise the rules for all providers. */
    override fun matchRules(provider: String): MatchRules {
        val custom = dataStore[lyricsMatchOverrideKey(provider)] == true
        fun rule(rule: MatchRule): Boolean {
            val global = dataStore[lyricsMatchRuleKey(rule.id)] ?: true
            return if (custom) dataStore[lyricsMatchRuleKey(rule.id, provider)] ?: global else global
        }
        val globalTolerance = dataStore[lyricsDurationToleranceKey()] ?: LyricsDurationToleranceDefault
        val tolerance = if (custom) dataStore[lyricsDurationToleranceKey(provider)] ?: globalTolerance else globalTolerance
        return MatchRules(
            exactTitle = rule(MatchRule.ExactTitle),
            matchArtist = rule(MatchRule.MatchArtist),
            skipOtherVersions = rule(MatchRule.SkipOtherVersions),
            duration = DurationMatch(rule(MatchRule.MatchDuration), tolerance.toInt()),
        )
    }

    override fun searchOptions() = SearchOptions(
        englishNames = dataStore[LyricsEnglishNameRetryKey] ?: true,
        titleParts = dataStore[LyricsTitlePartSearchKey] ?: true,
        albumFallback = dataStore[LyricsAlbumFallbackKey] ?: true,
    )
}
