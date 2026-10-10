/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import com.metrolist.music.constants.DynamicBackgroundBrightnessDefault
import com.metrolist.music.constants.DynamicBackgroundBrightnessKey
import com.metrolist.music.constants.DynamicBackgroundSaturationDefault
import com.metrolist.music.constants.DynamicBackgroundSaturationKey
import com.metrolist.music.constants.DynamicBackgroundSpeedDefault
import com.metrolist.music.constants.DynamicBackgroundSpeedKey
import com.metrolist.music.constants.DynamicBackgroundWarpDefault
import com.metrolist.music.constants.DynamicBackgroundWarpKey
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberPreference
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DynamicBackgroundSettings(navController: NavController) {
    val speed = rememberPreference(DynamicBackgroundSpeedKey, DynamicBackgroundSpeedDefault)
    val warp = rememberPreference(DynamicBackgroundWarpKey, DynamicBackgroundWarpDefault)
    val saturation = rememberPreference(DynamicBackgroundSaturationKey, DynamicBackgroundSaturationDefault)
    val brightness = rememberPreference(DynamicBackgroundBrightnessKey, DynamicBackgroundBrightnessDefault)

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Material3SettingsGroup(
            title = stringResource(R.string.dynamic_background_motion),
            items = listOf(
                sliderItem(R.drawable.speed, R.string.dynamic_background_speed, speed, 0.25f..3f, 10) {
                    String.format(Locale.US, "%.2f×", it)
                },
                sliderItem(R.drawable.linear_scale, R.string.dynamic_background_warp, warp, 0f..2f, 19) {
                    String.format(Locale.US, "%.1f", it)
                },
            ),
        )

        Spacer(Modifier.height(16.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.dynamic_background_color),
            items = listOf(
                sliderItem(R.drawable.palette, R.string.dynamic_background_saturation, saturation, 0f..3f, 29) {
                    String.format(Locale.US, "%.1f×", it)
                },
                sliderItem(R.drawable.contrast, R.string.dynamic_background_brightness, brightness, 0.3f..1f, 13) {
                    "${(it * 100).roundToInt()}%"
                },
            ),
        )
        Spacer(Modifier.height(16.dp))
    }

    TopAppBar(
        title = { Text(stringResource(R.string.dynamic_background_settings)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
            }
        },
    )
}
