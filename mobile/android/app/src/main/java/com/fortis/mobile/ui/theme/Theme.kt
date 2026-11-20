package com.fortis.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Frost on Material 3: red for actions and selection, near-black page,
// solid dark surfaces for Material's own dialogs and menus.
private val FortisColorScheme = darkColorScheme(
    primary = Frost.accent,
    onPrimary = Frost.textOnAccent,
    primaryContainer = Frost.accentSoft,
    onPrimaryContainer = Frost.accent,
    secondary = Frost.muted,
    onSecondary = Frost.text,
    tertiary = Frost.muted,
    onTertiary = Frost.text,
    background = Frost.page,
    onBackground = Frost.text,
    surface = SurfaceCard,
    onSurface = Frost.text,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = Frost.muted,
    surfaceContainer = SurfaceCard,
    surfaceContainerHigh = SurfaceElevated,
    surfaceContainerHighest = SurfaceElevated,
    outline = Frost.border,
    outlineVariant = Frost.faint,
    error = Frost.error,
    onError = Frost.page,
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
