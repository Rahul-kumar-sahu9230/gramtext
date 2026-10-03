package com.gramtext.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Violet = Color(0xFF5B21B6)
val VioletDark = Color(0xFF4C1D95)
val Cyan = Color(0xFF0891B2)
val Success = Color(0xFF047857)
val Danger = Color(0xFFB91C1C)

private val colors = lightColorScheme(
    primary = Violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDE9FE),
    onPrimaryContainer = VioletDark,
    secondary = Cyan,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFFAFE),
    onSecondaryContainer = Color(0xFF164E63),
    background = Color(0xFFFAF8FF),
    surface = Color.White,
    onSurface = Color(0xFF1C1B1F),
    onSurfaceVariant = Color(0xFF4A4458),
    error = Danger,
)

// Large type throughout: the target users may have weak eyesight or limited literacy.
private val type = Typography(
    headlineMedium = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, lineHeight = 40.sp),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 30.sp),
    titleMedium = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp),
    bodyLarge = TextStyle(fontSize = 19.sp, lineHeight = 28.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    labelLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun GramTextTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = type, content = content)
}
