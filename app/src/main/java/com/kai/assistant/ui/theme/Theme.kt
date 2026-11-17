package com.kai.assistant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Teal / ink direction — not purple-on-white, not cream/terracotta.
private val Teal900 = Color(0xFF0B1F1C)
private val Teal700 = Color(0xFF0F766E)
private val Teal500 = Color(0xFF14B8A6)
private val Teal100 = Color(0xFFCCFBF1)
private val Mist = Color(0xFFF3FFFC)
private val SoftInk = Color(0xFF16332E)
private val Night = Color(0xFF071412)
private val NightCard = Color(0xFF102420)

private val LightColors = lightColorScheme(
    primary = Teal700,
    onPrimary = Color.White,
    primaryContainer = Teal100,
    onPrimaryContainer = SoftInk,
    secondary = Teal500,
    onSecondary = Teal900,
    background = Mist,
    onBackground = SoftInk,
    surface = Color.White,
    onSurface = SoftInk,
    surfaceVariant = Color(0xFFE6F7F2),
    onSurfaceVariant = Color(0xFF3D5C56),
)

private val DarkColors = darkColorScheme(
    primary = Teal500,
    onPrimary = Night,
    primaryContainer = Teal700,
    onPrimaryContainer = Teal100,
    secondary = Color(0xFF5EEAD4),
    onSecondary = Night,
    background = Night,
    onBackground = Teal100,
    surface = NightCard,
    onSurface = Teal100,
    surfaceVariant = Color(0xFF1A3330),
    onSurfaceVariant = Color(0xFFA7C7C0),
)

private val AppTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 48.sp,
        letterSpacing = (-1).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
    ),
)

@Composable
fun KaiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
