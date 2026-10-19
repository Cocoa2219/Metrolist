/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.LyricsAlbumFallbackKey
import com.metrolist.music.constants.LyricsDurationToleranceDefault
import com.metrolist.music.constants.LyricsEnglishNameRetryKey
import com.metrolist.music.constants.LyricsTitlePartSearchKey
import com.metrolist.music.constants.lyricsDurationToleranceKey
import com.metrolist.music.constants.lyricsMatchOverrideKey
import com.metrolist.music.constants.lyricsMatchRuleKey
import com.metrolist.music.lyrics.MatchRule
import com.metrolist.music.lyrics.ProviderMatchRules
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberPreference
import kotlin.math.roundToInt

private val ToleranceRange = 1f..15f
private const val ToleranceSteps = 13

private val MatchRule.icon
    get() = when (this) {
        MatchRule.ExactTitle -> R.drawable.music_note
        MatchRule.MatchArtist -> R.drawable.artist
        MatchRule.SkipOtherVersions -> R.drawable.mic
        MatchRule.MatchDuration -> R.drawable.timer
    }

private val MatchRule.title
    get() = when (this) {
        MatchRule.ExactTitle -> R.string.lyrics_match_exact_title
        MatchRule.MatchArtist -> R.string.lyrics_match_artist
        MatchRule.SkipOtherVersions -> R.string.lyrics_skip_other_versions
        MatchRule.MatchDuration -> R.string.lyrics_require_duration
    }

private val MatchRule.description
    get() = when (this) {
        MatchRule.ExactTitle -> R.string.lyrics_match_exact_title_desc
        MatchRule.MatchArtist -> R.string.lyrics_match_artist_desc
        MatchRule.SkipOtherVersions -> R.string.lyrics_skip_other_versions_desc
        MatchRule.MatchDuration -> R.string.lyrics_require_duration_desc
    }

/**
 * Which lyrics search results count as the song. The rules cover every provider that applies them;
 * a provider can use its own instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsMatchingSettings(navController: NavController) {
    val globalRules = MatchRule.entries.associateWith { rememberPreference(lyricsMatchRuleKey(it.id), true) }
    val tolerance = rememberPreference(lyricsDurationToleranceKey(), LyricsDurationToleranceDefault)
    val englishNames = rememberPreference(LyricsEnglishNameRetryKey, true)
    val titleParts = rememberPreference(LyricsTitlePartSearchKey, true)
    val albumFallback = rememberPreference(LyricsAlbumFallbackKey, true)
    val secondsFormat = stringResource(R.string.lyrics_duration_tolerance_value)
    val secondsLabel: (Float) -> String = { secondsFormat.format(it.roundToInt()) }

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Material3SettingsGroup(
            title = stringResource(R.string.lyrics_matching_all_providers),
            items = buildList {
                for ((rule, preference) in globalRules) {
                    add(switchItem(rule.icon, rule.title, rule.description, preference))
                }
                add(
                    sliderItem(
                        R.drawable.tune, R.string.lyrics_duration_tolerance, tolerance, ToleranceRange, ToleranceSteps,
                        enabled = globalRules.getValue(MatchRule.MatchDuration).value, label = secondsLabel,
                    ),
                )
            },
        )

        Spacer(Modifier.height(27.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.lyrics_search_strategies),
            items = listOf(
                switchItem(R.drawable.translate, R.string.lyrics_english_name_retry, R.string.lyrics_english_name_retry_desc, englishNames),
                switchItem(R.drawable.format_align_left, R.string.lyrics_title_part_search, R.string.lyrics_title_part_search_desc, titleParts),
                switchItem(R.drawable.album, R.string.lyrics_album_fallback, R.string.lyrics_album_fallback_desc, albumFallback),
            ),
        )

        Spacer(Modifier.height(27.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.lyrics_matching_per_provider),
            items = buildList {
                for ((provider, rules) in ProviderMatchRules) {
                    val custom = rememberPreference(lyricsMatchOverrideKey(provider), false)
                    val providerRules = rules.associateWith {
                        rememberPreference(lyricsMatchRuleKey(it.id, provider), globalRules.getValue(it).value)
                    }
                    val providerTolerance = rememberPreference(lyricsDurationToleranceKey(provider), tolerance.value)
                    add(
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.lyrics),
                            title = { Text(provider) },
                            description = {
                                Text(
                                    stringResource(
                                        if (custom.value) R.string.lyrics_matching_custom else R.string.lyrics_matching_uses_global,
                                    ),
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = custom.value,
                                    onCheckedChange = { custom.value = it },
                                    thumbContent = {
                                        Icon(
                                            painter = painterResource(if (custom.value) R.drawable.check else R.drawable.close),
                                            contentDescription = null,
                                            modifier = Modifier.size(SwitchDefaults.IconSize),
                                        )
                                    },
                                )
                            },
                            onClick = { custom.value = !custom.value },
                        ),
                    )
                    if (custom.value) {
                        for ((rule, preference) in providerRules) {
                            add(switchItem(rule.icon, rule.title, null, preference))
                        }
                        providerRules[MatchRule.MatchDuration]?.let { requireDuration ->
                            add(
                                sliderItem(
                                    R.drawable.tune, R.string.lyrics_duration_tolerance, providerTolerance, ToleranceRange,
                                    ToleranceSteps, enabled = requireDuration.value, label = secondsLabel,
                                ),
                            )
                        }
                    }
                }
            },
        )

        Spacer(Modifier.height(16.dp))
    }

    TopAppBar(
        title = { Text(stringResource(R.string.lyrics_matching)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        }
    )
}
