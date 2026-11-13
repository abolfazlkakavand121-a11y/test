package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.domain.model.ThemeMode

private val DarkColors =
  darkColorScheme(
    primary = RadarCyan,
    onPrimary = Color(0xFF002430),
    primaryContainer = Color(0xFF003B4E),
    onPrimaryContainer = Color(0xFFB3F0FF),
    secondary = AwsAmber,
    onSecondary = Color(0xFF2B1700),
    secondaryContainer = Color(0xFF472900),
    onSecondaryContainer = Color(0xFFFFDDB8),
    tertiary = StatusEmerald,
    background = MidnightBg,
    onBackground = Color(0xFFE6EEF8),
    surface = MidnightSurface,
    onSurface = Color(0xFFE6EEF8),
    surfaceVariant = MidnightSurfaceHigh,
    onSurfaceVariant = Color(0xFF9FB2CA),
    outline = MidnightBorder,
    error = StatusRose,
    onError = Color.White,
  )

private val LightColors =
  lightColorScheme(
    primary = RadarCyanDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6F5FF),
    onPrimaryContainer = Color(0xFF002B38),
    secondary = AwsAmberDark,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE7C7),
    onSecondaryContainer = Color(0xFF331C00),
    tertiary = StatusEmerald,
    background = AlabasterBg,
    onBackground = Color(0xFF0F172A),
    surface = AlabasterSurface,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = AlabasterSurfaceHigh,
    onSurfaceVariant = Color(0xFF475569),
    outline = AlabasterBorder,
    error = StatusRose,
    onError = Color.White,
  )

@Composable
fun CloudFrontScannerTheme(
  themeMode: ThemeMode = ThemeMode.SYSTEM,
  content: @Composable () -> Unit,
) {
  val useDark =
    when (themeMode) {
      ThemeMode.DARK -> true
      ThemeMode.LIGHT -> false
      ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

  MaterialTheme(
    colorScheme = if (useDark) DarkColors else LightColors,
    typography = AppTypography,
    content = content,
  )
}
