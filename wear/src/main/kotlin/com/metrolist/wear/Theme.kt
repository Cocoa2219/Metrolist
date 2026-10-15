package com.metrolist.wear

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Typography

// Same face as the phone app; it also carries the Japanese bold the watch's system CJK font lacks.
// Only 400 and 700 ship, so Medium (500) resolves to Regular and SemiBold (600) to Bold.
private val Pretendard =
    FontFamily(
        Font(R.font.pretendardjp_regular, FontWeight.Normal),
        Font(R.font.pretendardjp_bold, FontWeight.Bold),
    )

private fun TextStyle.pretendard() = copy(fontFamily = Pretendard)

// Typography(fontFamily) leaves the styles on the device font, so every text style is set here.
// The curved arc styles (TimeText) stay on the system face like the rest of Wear OS.
private val WearTypography =
    Typography().run {
        copy(
            displayLarge = displayLarge.pretendard(),
            displayMedium = displayMedium.pretendard(),
            displaySmall = displaySmall.pretendard(),
            titleLarge = titleLarge.pretendard(),
            titleMedium = titleMedium.pretendard(),
            titleSmall = titleSmall.pretendard(),
            labelLarge = labelLarge.pretendard(),
            labelMedium = labelMedium.pretendard(),
            labelSmall = labelSmall.pretendard(),
            bodyLarge = bodyLarge.pretendard(),
            bodyMedium = bodyMedium.pretendard(),
            bodySmall = bodySmall.pretendard(),
            bodyExtraSmall = bodyExtraSmall.pretendard(),
            numeralExtraLarge = numeralExtraLarge.pretendard(),
            numeralLarge = numeralLarge.pretendard(),
            numeralMedium = numeralMedium.pretendard(),
            numeralSmall = numeralSmall.pretendard(),
            numeralExtraSmall = numeralExtraSmall.pretendard(),
        )
    }

@Composable
fun MetrolistWearTheme(content: @Composable () -> Unit) {
    MaterialTheme(typography = WearTypography, content = content)
}
