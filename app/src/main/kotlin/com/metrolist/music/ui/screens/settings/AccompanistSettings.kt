/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import androidx.annotation.DrawableRes
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.AccompanistAdditiveBlendKey
import com.metrolist.music.constants.AccompanistAutoResumeDefault
import com.metrolist.music.constants.AccompanistAutoResumeKey
import com.metrolist.music.constants.AccompanistBlurKey
import com.metrolist.music.constants.AccompanistBlurStrengthDefault
import com.metrolist.music.constants.AccompanistBlurStrengthKey
import com.metrolist.music.constants.AccompanistFocusPositionDefault
import com.metrolist.music.constants.AccompanistFocusPositionKey
import com.metrolist.music.constants.AccompanistFontSizeDefault
import com.metrolist.music.constants.AccompanistFontSizeKey
import com.metrolist.music.constants.AccompanistItemSpacingDefault
import com.metrolist.music.constants.AccompanistItemSpacingKey
import com.metrolist.music.constants.AccompanistLineHeightDefault
import com.metrolist.music.constants.AccompanistLineHeightKey
import com.metrolist.music.constants.AccompanistScrollDurationDefault
import com.metrolist.music.constants.AccompanistScrollDurationKey
import com.metrolist.music.constants.AccompanistSungLineOpacityDefault
import com.metrolist.music.constants.AccompanistSungLineOpacityKey
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberPreference
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccompanistSettings(navController: NavController) {
    val fontSize = rememberPreference(AccompanistFontSizeKey, AccompanistFontSizeDefault)
    val lineHeight = rememberPreference(AccompanistLineHeightKey, AccompanistLineHeightDefault)
    val itemSpacing = rememberPreference(AccompanistItemSpacingKey, AccompanistItemSpacingDefault)
    val blur = rememberPreference(AccompanistBlurKey, true)
    val blurStrength = rememberPreference(AccompanistBlurStrengthKey, AccompanistBlurStrengthDefault)
    val additiveBlend = rememberPreference(AccompanistAdditiveBlendKey, true)
    val sungLineOpacity = rememberPreference(AccompanistSungLineOpacityKey, AccompanistSungLineOpacityDefault)
    val focusPosition = rememberPreference(AccompanistFocusPositionKey, AccompanistFocusPositionDefault)
    val scrollDuration = rememberPreference(AccompanistScrollDurationKey, AccompanistScrollDurationDefault)
    val autoResume = rememberPreference(AccompanistAutoResumeKey, AccompanistAutoResumeDefault)
    val off = stringResource(R.string.accompanist_auto_resume_off)

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Material3SettingsGroup(
            title = stringResource(R.string.accompanist_text_and_spacing),
            items = listOf(
                sliderItem(R.drawable.lyrics, R.string.accompanist_font_size, fontSize, 20f..48f, 27) { "${it.roundToInt()} sp" },
                sliderItem(R.drawable.linear_scale, R.string.accompanist_line_height, lineHeight, 1f..2f, 9) {
                    String.format(Locale.US, "%.1f×", it)
                },
                sliderItem(R.drawable.linear_scale, R.string.accompanist_item_spacing, itemSpacing, 0f..32f, 15) { "${it.roundToInt()} dp" },
            ),
        )

        Spacer(Modifier.height(16.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.accompanist_effects),
            items = listOf(
                switchItem(R.drawable.gradient, R.string.accompanist_blur, null, blur),
                sliderItem(R.drawable.tune, R.string.accompanist_blur_strength, blurStrength, 0.5f..8f, 14, enabled = blur.value) {
                    String.format(Locale.US, "%.1f", it)
                },
                switchItem(R.drawable.gradient, R.string.accompanist_additive_blend, R.string.accompanist_additive_blend_desc, additiveBlend),
                sliderItem(R.drawable.tune, R.string.accompanist_sung_line_opacity, sungLineOpacity, 0f..1f, 19) {
                    "${(it * 100).roundToInt()}%"
                },
            ),
        )

        Spacer(Modifier.height(16.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.accompanist_scrolling),
            items = listOf(
                sliderItem(R.drawable.linear_scale, R.string.accompanist_focus_position, focusPosition, 0.1f..0.6f, 9) {
                    "${(it * 100).roundToInt()}%"
                },
                sliderItem(R.drawable.speed, R.string.accompanist_scroll_duration, scrollDuration, 200f..1200f, 19) {
                    "${it.roundToInt()} ms"
                },
                sliderItem(R.drawable.timer, R.string.accompanist_auto_resume, autoResume, 0f..10f, 9) {
                    if (it.roundToInt() == 0) off else "${it.roundToInt()} s"
                },
            ),
        )
        Spacer(Modifier.height(16.dp))
    }

    TopAppBar(
        title = { Text(stringResource(R.string.accompanist_settings)) },
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

/** The slider only writes the preference when released, so dragging doesn't spam DataStore. */
@Composable
private fun sliderItem(
    @DrawableRes icon: Int,
    title: Int,
    preference: MutableState<Float>,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean = true,
    label: (Float) -> String,
) = Material3SettingsItem(
    icon = painterResource(icon),
    title = { Text(stringResource(title)) },
    enabled = enabled,
    description = {
        var value by remember(preference.value) { mutableFloatStateOf(preference.value) }
        Column {
            Text(label(value))
            Slider(
                value = value,
                onValueChange = { value = it },
                onValueChangeFinished = { preference.value = value },
                valueRange = range,
                steps = steps,
                enabled = enabled,
            )
        }
    },
)

@Composable
private fun switchItem(
    @DrawableRes icon: Int,
    title: Int,
    description: Int?,
    preference: MutableState<Boolean>,
) = Material3SettingsItem(
    icon = painterResource(icon),
    title = { Text(stringResource(title)) },
    description = description?.let { { Text(stringResource(it)) } },
    trailingContent = {
        Switch(
            checked = preference.value,
            onCheckedChange = { preference.value = it },
            thumbContent = {
                Icon(
                    painter = painterResource(if (preference.value) R.drawable.check else R.drawable.close),
                    contentDescription = null,
                    modifier = Modifier.size(SwitchDefaults.IconSize),
                )
            },
        )
    },
    onClick = { preference.value = !preference.value },
)
