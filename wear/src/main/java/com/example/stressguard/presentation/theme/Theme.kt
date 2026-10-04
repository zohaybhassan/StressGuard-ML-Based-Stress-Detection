package com.example.stressguard.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Typography

val Canvas = Color(0xFF081311)
val Surface = Color(0xFF101D1A)
val SurfaceVariant = Color(0xFF172723)
val Outline = Color(0xFF2E423B)
val TextPrimary = Color(0xFFE8F1EE)
val TextSecondary = Color(0xFF9CB2AC)
val Brand = Color(0xFF2ED3B7)
val OnBrand = Color(0xFF04231E)
val StressLow = Color(0xFF4FD98F)
val StressModerate = Color(0xFFFFC463)
val StressElevated = Color(0xFFFF9A5C)
val StressHigh = Color(0xFFFF6B6E)
val Heart = Color(0xFFFF7189)
val Steps = Color(0xFF6A9BFF)

private val StressGuardColors = Colors(
    primary = Brand,
    primaryVariant = StressLow,
    secondary = StressModerate,
    secondaryVariant = StressElevated,
    background = Canvas,
    surface = Surface,
    error = StressHigh,
    onPrimary = OnBrand,
    onSecondary = Canvas,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    onError = Canvas,
)

private val StressGuardTypography = Typography(
    display1 = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
    ),
    title2 = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
    ),
    body1 = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
    ),
    body2 = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
    ),
    caption1 = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
    ),
)

@Composable
fun StressGuardTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colors = StressGuardColors,
        typography = StressGuardTypography,
        content = content
    )
}
