package com.fortis.mobile.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fortis.mobile.ui.theme.*

// ─── Spacing Constants ───────────────────────────────────────────────

object FortisSpacing {
    val sectionGap = 24.dp
    val cardPadding = 18.dp          // Frost pad
    val cardGap = 12.dp
    val buttonGap = 8.dp
    val screenPadding = 16.dp
    val iconSize = 24.dp
    val minTouchTarget = 48.dp
    val cardCorner = 18.dp           // Frost radius
    val buttonCorner = 12.dp
}

/**
 * Turns the old SHOUTING_SNAKE labels ("LOCK_LAPTOP") into Frost's sentence
 * case ("Lock laptop"). Labels that are already mixed case are left alone.
 */
fun frostLabel(text: String): String {
    if (text.any { it.isLowerCase() }) return text
    val t = text.replace('_', ' ').lowercase()
    val i = t.indexOfFirst { it.isLetter() }
    return if (i < 0) t else t.substring(0, i) + t[i].uppercaseChar() + t.substring(i + 1)
}

// ─── Background ──────────────────────────────────────────────────────

/** The page behind every screen: near-black with a soft red glow, so the glass has something to frost. */
@Composable
fun FrostBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .fillMaxSize()
            .background(Frost.page)
            .drawWithContent {
                drawRect(
                    Brush.radialGradient(
                        listOf(Frost.accent.copy(alpha = 0.16f), Color.Transparent),
                        center = Offset(size.width * 0.85f, size.height * 0.05f),
                        radius = size.maxDimension * 0.55f,
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(Color(0xFF7A1024).copy(alpha = 0.22f), Color.Transparent),
                        center = Offset(0f, size.height),
                        radius = size.maxDimension * 0.6f,
                    )
                )
                drawContent()
            },
    ) {
        CompositionLocalProvider(LocalContentColor provides Frost.text) { content() }
    }
}

// ─── Small building blocks ───────────────────────────────────────────

/** Small uppercase caption, like "SYSTEM OBSERVATORY". */
@Composable
fun FortisCaption(text: String, modifier: Modifier = Modifier, color: Color = Frost.muted) {
    Text(
        text = text.replace('_', ' ').uppercase(),
        modifier = modifier,
        color = color,
        fontFamily = FrostSans,
        fontSize = 11.sp,
        letterSpacing = 2.5.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A slight shrink on press, no ripple: Frost's press feedback. */
@Composable
private fun pressScale(source: MutableInteractionSource): Float {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(90), label = "press")
    return scale
}

/** Level-1 glass: translucent fill, 1 px light border, a faint highlight along the top edge. */
fun Modifier.frostGlass(radius: Dp = FortisSpacing.cardCorner, soft: Boolean = false): Modifier {
    val shape = RoundedCornerShape(radius)
    return this
        .clip(shape)
        .background(if (soft) Frost.glassSoft else Frost.glass, shape)
        .drawWithContent {
            drawContent()
            val r = radius.toPx()
            val y = 1.dp.toPx() + 0.5f
            drawLine(Frost.highlight, Offset(r, y), Offset(size.width - r, y), 1.dp.toPx())
        }
        .border(1.dp, Frost.border, shape)
}

// ─── FortisCard ──────────────────────────────────────────────────────

@Composable
fun FortisCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .animateContentSize()
            .frostGlass()
            .padding(FortisSpacing.cardPadding)
            .fillMaxWidth(),
        content = content
    )
}

// ─── FortisActionButton ──────────────────────────────────────────────

@Composable
fun FortisActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isPrimary: Boolean = false,
    isDestructive: Boolean = false,
    isLoading: Boolean = false
) {
    val source = remember { MutableInteractionSource() }
    val scale = pressScale(source)
    val containerColor = when {
        isPrimary -> Frost.accent
        isDestructive -> Frost.error.copy(alpha = 0.10f)
        else -> Color.White.copy(alpha = 0.05f)
    }
    val contentColor = when {
        isPrimary -> Frost.textOnAccent
        isDestructive -> Frost.error
        else -> Frost.text
    }
    val borderColor = when {
        isPrimary -> Frost.accent
        isDestructive -> Frost.error.copy(alpha = 0.55f)
        else -> Frost.faint
    }

    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = FortisSpacing.minTouchTarget)
            .graphicsLayer { scaleX = scale; scaleY = scale },
        enabled = enabled && !isLoading,
        shape = RoundedCornerShape(FortisSpacing.buttonCorner),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = containerColor.copy(alpha = containerColor.alpha * 0.45f),
            disabledContentColor = contentColor.copy(alpha = 0.45f)
        ),
        border = BorderStroke(1.dp, if (enabled && !isLoading) borderColor else borderColor.copy(alpha = 0.3f)),
        interactionSource = source,
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = contentColor
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = frostLabel(text),
            fontFamily = FrostSans,
            fontSize = 15.sp,
        )
    }
}

// ─── FortisIconActionButton ──────────────────────────────────────────

@Composable
fun FortisIconActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val source = remember { MutableInteractionSource() }
    val scale = pressScale(source)
    Button(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 76.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale },
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White.copy(alpha = 0.05f),
            contentColor = Frost.text,
            disabledContainerColor = Color.White.copy(alpha = 0.02f),
            disabledContentColor = Frost.text.copy(alpha = 0.4f)
        ),
        border = BorderStroke(1.dp, Frost.faint),
        contentPadding = PaddingValues(8.dp),
        interactionSource = source,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Frost.accent,
                modifier = Modifier.size(FortisSpacing.iconSize)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = frostLabel(label),
                fontFamily = FrostSans,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ─── FortisMetricCard ────────────────────────────────────────────────

@Composable
fun FortisMetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .padding(4.dp)
            .frostGlass(radius = 14.dp, soft = true)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .fillMaxWidth()
    ) {
        FortisCaption(label)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            fontFamily = FrostMono,
            fontSize = 18.sp,
            color = Frost.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ─── FortisStatusChip ────────────────────────────────────────────────

@Composable
fun FortisStatusChip(
    label: String,
    isHealthy: Boolean,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .clip(shape)
            .background(Color.White.copy(alpha = 0.04f), shape)
            .border(1.dp, Frost.border, shape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    color = if (isHealthy) Frost.ok else Frost.error,
                    shape = CircleShape
                )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            fontFamily = FrostMono,
            fontSize = 12.sp,
            color = Frost.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ─── FortisSection ───────────────────────────────────────────────────

@Composable
fun FortisSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        FortisCaption(title, Modifier.padding(start = 4.dp, bottom = 10.dp))
        content()
    }
}

// ─── FortisConfirmDialog ─────────────────────────────────────────────

@Composable
fun FortisConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                fontFamily = FrostSans,
                fontSize = 22.sp,
            )
        },
        text = {
            Text(
                text = message,
                fontFamily = FrostSans,
                fontSize = 15.sp,
                color = Frost.muted,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Yes", fontFamily = FrostSans, fontSize = 15.sp, color = Frost.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("No", fontFamily = FrostSans, fontSize = 15.sp, color = Frost.muted)
            }
        },
        shape = RoundedCornerShape(FortisSpacing.cardCorner),
        containerColor = Frost.sheet,
        titleContentColor = Frost.text,
        textContentColor = Frost.muted
    )
}

// ─── FortisCompactActionButton (for rows like media/power) ───────────

@Composable
fun FortisCompactActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isPrimary: Boolean = false,
) {
    val source = remember { MutableInteractionSource() }
    val scale = pressScale(source)
    Button(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = FortisSpacing.minTouchTarget)
            .graphicsLayer { scaleX = scale; scaleY = scale },
        enabled = enabled,
        shape = RoundedCornerShape(FortisSpacing.buttonCorner),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isPrimary) Frost.accent else Color.White.copy(alpha = 0.05f),
            contentColor = if (isPrimary) Frost.textOnAccent else Frost.text,
            disabledContainerColor = Color.White.copy(alpha = 0.02f),
            disabledContentColor = Frost.text.copy(alpha = 0.4f)
        ),
        border = if (isPrimary) null else BorderStroke(1.dp, if (enabled) Frost.faint else Frost.faint.copy(alpha = 0.03f)),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        interactionSource = source,
    ) {
        Text(
            text = frostLabel(text),
            fontFamily = FrostSans,
            fontSize = 14.sp,
            maxLines = 1,
        )
    }
}
