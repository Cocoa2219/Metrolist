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
import com.metrolist.music.constants.LyricsDurationToleranceDefault
import com.metrolist.music.constants.LyricsDurationToleranceKey
import com.metrolist.music.constants.LyricsRequireDurationMatchKey
import com.metrolist.music.constants.lyricsDurationToleranceKey
import com.metrolist.music.constants.lyricsMatchOverrideKey
import com.metrolist.music.constants.lyricsRequireDurationMatchKey
import com.metrolist.music.lyrics.DurationMatchedProviders
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberPreference
import kotlin.math.roundToInt

private val ToleranceRange = 1f..15f
private const val ToleranceSteps = 13

/**
 * How closely a lyrics search result must match the song. One rule covers every provider that
 * compares lengths; a provider can use its own instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsMatchingSettings(navController: NavController) {
    val requireDuration = rememberPreference(LyricsRequireDurationMatchKey, true)
    val tolerance = rememberPreference(LyricsDurationToleranceKey, LyricsDurationToleranceDefault)
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
            items = listOf(
                switchItem(R.drawable.timer, R.string.lyrics_require_duration, R.string.lyrics_require_duration_desc, requireDuration),
                sliderItem(
                    R.drawable.tune, R.string.lyrics_duration_tolerance, tolerance, ToleranceRange, ToleranceSteps,
                    enabled = requireDuration.value, label = secondsLabel,
                ),
            ),
        )

        Spacer(Modifier.height(27.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.lyrics_matching_per_provider),
            items = buildList {
                for (provider in DurationMatchedProviders) {
                    val custom = rememberPreference(lyricsMatchOverrideKey(provider), false)
                    val providerRequire = rememberPreference(lyricsRequireDurationMatchKey(provider), requireDuration.value)
                    val providerTolerance = rememberPreference(lyricsDurationToleranceKey(provider), tolerance.value)
                    add(
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.lyrics),
                            title = { Text(provider) },
                            description = {
                                Text(
                                    when {
                                        !custom.value -> stringResource(R.string.lyrics_matching_uses_global)
                                        !providerRequire.value -> stringResource(R.string.lyrics_matching_any_duration)
                                        else -> secondsLabel(providerTolerance.value)
                                    },
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
                        add(switchItem(R.drawable.timer, R.string.lyrics_require_duration, null, providerRequire))
                        add(
                            sliderItem(
                                R.drawable.tune, R.string.lyrics_duration_tolerance, providerTolerance, ToleranceRange,
                                ToleranceSteps, enabled = providerRequire.value, label = secondsLabel,
                            ),
                        )
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
