package com.fortis.mobile.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.fortis.mobile.R

/**
 * Frost's type: Rufscript (handwritten) for headings and titles, JetBrains
 * Mono for everything that is data. Rule for this app: passwords, codes,
 * keys, fingerprints and addresses are always FrostMono, where 0/O and 1/l
 * can't be confused.
 */
val FrostSans = FontFamily(Font(R.font.rufscript))
val FrostMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_medium, FontWeight.SemiBold),
    Font(R.font.jetbrains_mono_medium, FontWeight.Bold),
)

private fun sans(size: Int, line: Int, spacing: Double = 0.0) =
    TextStyle(fontFamily = FrostSans, fontWeight = FontWeight.Normal, fontSize = size.sp, lineHeight = line.sp, letterSpacing = spacing.sp)

private fun mono(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, spacing: Double = 0.0) =
    TextStyle(fontFamily = FrostMono, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = spacing.sp)

val Typography = Typography(
    // headings and titles: Rufscript
    headlineLarge = sans(30, 36, 1.0),
    headlineMedium = sans(24, 30, 1.0),
    headlineSmall = sans(19, 26, 0.5),
    titleLarge = sans(20, 26),
    titleMedium = sans(16, 22),
    titleSmall = sans(14, 20),
    // body and labels: JetBrains Mono
    bodyLarge = mono(15, 22),
    bodyMedium = mono(13, 19),
    bodySmall = mono(12, 16),
    labelLarge = mono(14, 20, FontWeight.Medium),
    labelMedium = mono(12, 16, FontWeight.Medium),
    labelSmall = mono(11, 16, spacing = 0.5),
)
