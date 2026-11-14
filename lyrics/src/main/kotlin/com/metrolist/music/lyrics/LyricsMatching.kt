/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import android.content.Context
import java.util.Locale
import kotlin.math.abs

/** A condition a search result must meet to count as the song; [id] names its preference. */
enum class MatchRule(val id: String) {
    ExactTitle("MatchExactTitle"),
    MatchArtist("MatchArtist"),
    SkipOtherVersions("SkipOtherVersions"),
    MatchDuration("RequireDurationMatch"),
}

/** The rules each provider can apply, in settings order. */
val ProviderMatchRules: Map<String, Set<MatchRule>> = linkedMapOf(
    "LrcRed" to MatchRule.entries.toSet(),
    "Paxsenix" to MatchRule.entries.toSet(),
    "AMLL" to setOf(MatchRule.ExactTitle, MatchRule.MatchArtist, MatchRule.SkipOtherVersions),
    "LrcLib" to setOf(MatchRule.SkipOtherVersions, MatchRule.MatchDuration),
    "Kugou" to setOf(MatchRule.MatchDuration),
    "Unison" to setOf(MatchRule.MatchDuration),
)

/** How close a search result's length must be to the song's for a provider to accept it. */
data class DurationMatch(val required: Boolean, val toleranceSeconds: Int) {
    /** Unknown lengths on either side never rule a result out. */
    fun accepts(expectedSeconds: Int, foundSeconds: Number?): Boolean =
        !required || expectedSeconds <= 0 || foundSeconds == null ||
            abs(foundSeconds.toDouble() - expectedSeconds) <= toleranceSeconds

    /** For sources that skip their duration check when given -1. */
    fun durationFor(expectedSeconds: Int): Int = if (required) expectedSeconds else -1
}

data class MatchRules(
    val exactTitle: Boolean,
    val matchArtist: Boolean,
    val skipOtherVersions: Boolean,
    val duration: DurationMatch,
) {
    /** Whether [candidate] is another version (live, remix, ...) of a song titled [title]. */
    fun rejectsVersion(title: String, candidate: String): Boolean =
        skipOtherVersions && isOtherVersion(title, candidate)
}

/** Where the providers read the user's matching preferences; the app supplies the real one. */
interface LyricsMatchSettings {
    fun matchRules(provider: String): MatchRules
    fun searchOptions(): SearchOptions
}

object LyricsMatchConfig {
    private val defaults = object : LyricsMatchSettings {
        override fun matchRules(provider: String) = MatchRules(
            exactTitle = true,
            matchArtist = true,
            skipOtherVersions = true,
            duration = DurationMatch(required = true, toleranceSeconds = 5),
        )

        override fun searchOptions() = SearchOptions(englishNames = true, titleParts = true, albumFallback = true)
    }

    @Volatile
    var settings: LyricsMatchSettings = defaults
}

@Suppress("UnusedReceiverParameter")
fun Context.matchRules(provider: String): MatchRules = LyricsMatchConfig.settings.matchRules(provider)

fun Context.durationMatch(provider: String): DurationMatch = matchRules(provider).duration

/** Search strategies some providers use when the plain search misses. */
data class SearchOptions(val englishNames: Boolean, val titleParts: Boolean, val albumFallback: Boolean)

@Suppress("UnusedReceiverParameter")
fun Context.lyricsSearchOptions(): SearchOptions = LyricsMatchConfig.settings.searchOptions()

// Words marking a recording other than the studio one, matched as whole words.
private val VersionMarkers = Regex(
    """\b(live|remix(ed)?|mix(ed)?|acoustic|instrumental|inst\.?|karaoke|off vocal|sped up|slowed|nightcore|cover|tv size|demo)\b""",
)

/** A result marked as a version the song's own title isn't, e.g. "Song (Live)" for "Song". */
fun isOtherVersion(title: String, candidate: String): Boolean {
    val wanted = VersionMarkers.findAll(title.lowercase(Locale.ROOT)).map { it.value }.toSet()
    return VersionMarkers.findAll(candidate.lowercase(Locale.ROOT)).any { it.value !in wanted }
}
