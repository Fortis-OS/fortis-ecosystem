package com.example.fortismobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val MonochromeColorScheme = lightColorScheme(
    primary = Black,
    onPrimary = White,
    secondary = DarkGray,
    onSecondary = White,
    tertiary = MediumGray,
    onTertiary = White,
    background = White,
    onBackground = Black,
    surface = White,
    onSurface = Black
)

@Composable
fun FortisMobileTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = MonochromeColorScheme,
        typography = Typography,
        content = content
    )
}