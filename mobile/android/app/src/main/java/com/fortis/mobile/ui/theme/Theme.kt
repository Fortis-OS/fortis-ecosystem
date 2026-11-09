package com.fortis.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val FortisColorScheme = darkColorScheme(
    primary = White,
    onPrimary = Black,
    secondary = MediumGray,
    onSecondary = White,
    tertiary = MediumGray,
    onTertiary = White,
    background = SurfaceDark,
    onBackground = TextPrimary,
    surface = SurfaceCard,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = BorderSubtle,
    outlineVariant = BorderSubtle
)

@Composable
fun FortisMobileTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = FortisColorScheme,
        typography = Typography,
        content = content
    )
}