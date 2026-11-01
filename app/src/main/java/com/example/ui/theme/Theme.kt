package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = WhatsAppGreenAccentDark,
    onPrimary = Color.White,
    primaryContainer = WhatsAppTealPrimaryDark,
    onPrimaryContainer = Color.White,
    secondary = WhatsAppUserBubbleDark,
    onSecondary = Color.White,
    background = WhatsAppChatBackgroundDark,
    onBackground = Color(0xFFE9EDEF),
    surface = WhatsAppSurfaceDark,
    onSurface = Color(0xFFE9EDEF),
    surfaceVariant = WhatsAppAiBubbleDark,
    onSurfaceVariant = Color(0xFF8696A0),
    error = ErrorBannerRedBg,
    onError = ErrorBannerRedText
)

private val LightColorScheme = lightColorScheme(
    primary = WhatsAppTealPrimaryLight,
    onPrimary = Color.White,
    primaryContainer = WhatsAppTealPrimaryLight,
    onPrimaryContainer = Color.White,
    secondary = WhatsAppGreenAccentLight,
    onSecondary = Color.White,
    background = WhatsAppChatBackgroundLight,
    onBackground = Color(0xFF111B21),
    surface = WhatsAppSurfaceLight,
    onSurface = Color(0xFF111B21),
    surfaceVariant = WhatsAppAiBubbleLight,
    onSurfaceVariant = Color(0xFF667781),
    error = ErrorBannerRedBg,
    onError = ErrorBannerRedText
)

@Composable
fun PocketGgufTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
