package com.fortis.mobile.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Frost / Obsidian Glass tokens, the same values as Frost's
 * widgets/shared/Style.qml and Frost Mobile's FrostTheme.kt. Dark only.
 */
object Frost {
    val accent = Color(0xFFF21D43)                          // Frost red: actions, selection
    val accentSoft = Color(242, 29, 67, 56)                  // 0.22
    val textOnAccent = Color.White
    val page = Color(0xFF0B0B0E)                             // behind everything
    val text = Color(0xFFF2F2F5)
    val muted = Color(0xFF9A9AA6)
    val faint = Color.White.copy(alpha = 0.08f)              // hairlines, quiet fills
    val hover = Color.White.copy(alpha = 0.12f)
    val subtle = Color.White.copy(alpha = 0.25f)             // "off" dots, muted bars
    val glass = Color(18, 18, 22, 122)                       // 0.48
    val glassSoft = Color(28, 28, 34, 97)                    // 0.38
    val sheet = Color(16, 16, 20, 245)                       // dialogs and menus: solid enough to read
    val well = Color.Black.copy(alpha = 0.28f)               // text fields
    val border = Color.White.copy(alpha = 0.12f)
    val highlight = Color.White.copy(alpha = 0.08f)          // glass top edge
    val fill = Color(242, 242, 245, 230)                     // the second data series
    val textOnFill = Color(0xFF1A1A1F)                       // text and icons on fill
    val accentSelect = Color(242, 29, 67, 107)               // 0.42: the selected row / dock item
    val accentSelectBorder = Color(255, 89, 115, 191)        // 0.75

    // status: kept apart from the accent, so a failure never looks like a button
    val ok = Color(0xFF34C759)
    val warn = Color(0xFFFFB02E)
    val error = Color(0xFFFF5C5C)
}

// Names the screens already use, mapped onto Frost
val Black = Color(0xFF000000)
val White = Color(0xFFFFFFFF)
val DarkGray = Color(0xFF1C1C22)
val MediumGray = Frost.muted
val LightGray = Frost.text

val SurfaceDark = Frost.page
val SurfaceCard = Color(0xFF141418)
val SurfaceElevated = Color(0xFF1C1C22)
val BorderSubtle = Frost.border
val TextPrimary = Frost.text
val TextSecondary = Frost.muted
val StatusGreen = Frost.ok
val StatusRed = Frost.error
val StatusAmber = Frost.warn
