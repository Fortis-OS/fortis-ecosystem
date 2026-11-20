package com.fortis.mobile.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fortis.mobile.ui.theme.Frost
import com.fortis.mobile.ui.theme.FrostMono
import com.fortis.mobile.ui.theme.FrostSans

// Frost Mobile's Glass.kt pieces, in Fortis: chips, the glass dock, status dots and rows.

/** Tap with Frost's press feedback: a slight shrink, no ripple. */
@Composable
fun Modifier.pressable(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, tween(90), label = "press")
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(source, indication = null, enabled = enabled, onClick = onClick)
}

/** Profile-style chip: accent outline and soft red fill when on. */
@Composable
fun FrostChip(label: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        label,
        modifier
            .pressable(onClick = onClick)
            .clip(shape)
            .background(if (on) Frost.accentSoft else Color.Transparent, shape)
            .border(1.dp, if (on) Frost.accent else Frost.border, shape)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        fontFamily = FrostSans,
        fontSize = 13.sp,
        color = if (on) Frost.accent else Frost.muted,
        maxLines = 1,
    )
}

/** A row of [FrostChip]s choosing one of [options]. */
@Composable
fun <T> FrostChips(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) -> FrostChip(label, value == selected) { onSelect(value) } }
    }
}

data class DockItem(val label: String, val icon: ImageVector)

/** The floating glass dock: four items, the current one in Frost red on a red pill. */
@Composable
fun FrostDock(items: List<DockItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .frostGlass(radius = 24.dp)
            .background(Frost.sheet.copy(alpha = 0.55f), RoundedCornerShape(24.dp))
            .padding(6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        items.forEachIndexed { i, item ->
            val on = i == selected
            val tint by animateColorAsState(if (on) Frost.accent else Frost.muted, tween(160), label = "dock")
            val pill by animateColorAsState(if (on) Frost.accentSoft else Color.Transparent, tween(160), label = "pill")
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(18.dp))
                    .pressable { onSelect(i) }
                    .background(pill, RoundedCornerShape(18.dp))
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(item.icon, contentDescription = item.label, tint = tint, modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(3.dp))
                Text(item.label, fontFamily = FrostSans, fontSize = 11.sp, color = tint, maxLines = 1)
            }
        }
    }
}

/** A small round status light. */
@Composable
fun StatusDot(ok: Boolean?, modifier: Modifier = Modifier) {
    val c = when (ok) { true -> Frost.ok; false -> Frost.error; null -> Frost.subtle }
    Box(modifier.size(8.dp).background(c, CircleShape))
}

/**
 * One line of state with a light: label, value, optional chevron when it can be tapped.
 * [ok] null = neutral (unknown / not applicable).
 */
@Composable
fun StatusRow(label: String, value: String, ok: Boolean?, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.pressable(onClick = onClick) else Modifier)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(ok)
        Spacer(Modifier.width(12.dp))
        Text(label, fontFamily = FrostSans, fontSize = 15.sp, color = Frost.text, modifier = Modifier.weight(1f))
        Text(
            value,
            fontFamily = FrostMono,
            fontSize = 12.sp,
            color = when (ok) { false -> Frost.error; else -> Frost.muted },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onClick != null) Icon(Icons.Default.ChevronRight, null, tint = Frost.subtle, modifier = Modifier.size(18.dp))
    }
}

/** A large round glass action with a label under it (Home's quick actions). */
@Composable
fun QuickAction(icon: ImageVector, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Column(modifier.pressable(enabled = enabled, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(56.dp).frostGlass(radius = 18.dp, soft = true),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = if (enabled) Frost.text else Frost.subtle, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontFamily = FrostSans, fontSize = 12.sp, color = if (enabled) Frost.muted else Frost.subtle, maxLines = 1)
    }
}

/** Screen title with an optional back arrow and trailing actions (Rufscript, like Frost's headers). */
@Composable
fun FrostTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            androidx.compose.material3.IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Frost.text)
            }
        }
        Text(
            title, fontFamily = FrostSans, fontSize = 24.sp, color = Frost.text, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = if (onBack == null) 4.dp else 0.dp),
        )
        actions()
    }
}

/** A failure: Frost error colour, a warning icon and the message (never mistaken for a red button). */
@Composable
fun ErrorLine(message: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Default.WarningAmber, contentDescription = "Error", tint = Frost.error, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(message, fontFamily = FrostSans, fontSize = 14.sp, color = Frost.error)
    }
}

/** Frost text-field colours: a dark well, light border, red edge and cursor when focused. */
@Composable
fun frostFieldColors() = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
    focusedTextColor = Frost.text,
    unfocusedTextColor = Frost.text,
    focusedContainerColor = Frost.well,
    unfocusedContainerColor = Frost.well,
    focusedBorderColor = Frost.accent,
    unfocusedBorderColor = Frost.border,
    focusedLabelColor = Frost.accent,
    unfocusedLabelColor = Frost.muted,
    cursorColor = Frost.accent,
    focusedTrailingIconColor = Frost.muted,
    unfocusedTrailingIconColor = Frost.muted,
    focusedLeadingIconColor = Frost.muted,
    unfocusedLeadingIconColor = Frost.muted,
    focusedPlaceholderColor = Frost.subtle,
    unfocusedPlaceholderColor = Frost.subtle,
)

/**
 * Frost text field. [mono] for secrets, codes, keys and addresses (FrostMono); otherwise Rufscript.
 * Everything else is OutlinedTextField's own parameters.
 */
@Composable
fun FrostField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    singleLine: Boolean = true,
    placeholder: String? = null,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) = androidx.compose.material3.OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    modifier = modifier.fillMaxWidth(),
    label = if (label.isEmpty()) null else ({ Text(label, fontFamily = FrostSans) }),
    placeholder = placeholder?.let { { Text(it, fontFamily = FrostSans) } },
    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = if (mono) FrostMono else FrostSans, fontSize = 15.sp, color = Frost.text),
    singleLine = singleLine,
    visualTransformation = visualTransformation,
    keyboardOptions = keyboardOptions,
    leadingIcon = leadingIcon,
    trailingIcon = trailingIcon,
    shape = RoundedCornerShape(14.dp),
    colors = frostFieldColors(),
)

/** On/off chip for options (the password generator's character sets). */
@Composable
fun FrostToggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) = FrostChip(label, on) { onChange(!on) }

/** Frost switch: red track when on, faint when off. */
@Composable
fun frostSwitchColors() = androidx.compose.material3.SwitchDefaults.colors(
    checkedThumbColor = Frost.textOnAccent,
    checkedTrackColor = Frost.accent,
    checkedBorderColor = Frost.accent,
    uncheckedThumbColor = Frost.muted,
    uncheckedTrackColor = Frost.faint,
    uncheckedBorderColor = Frost.border,
)
