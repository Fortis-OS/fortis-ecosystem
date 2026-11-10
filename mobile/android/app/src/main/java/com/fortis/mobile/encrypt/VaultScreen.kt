package com.fortis.mobile.encrypt

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.fortis.mobile.ui.FortisViewModel
import com.fortis.mobile.ui.components.FortisActionButton
import com.fortis.mobile.ui.components.FortisSpacing

/**
 * Placeholder while the phone vault is rebuilt: the old screen fetched
 * decrypted entries over the network, which the v2 design forbids
 * (docs/security §6.3). The local encrypted vault + sync replaces it.
 */
@Composable
fun VaultScreen(viewModel: FortisViewModel, onBack: () -> Unit = {}) = Rebuilding("VAULT", onBack)

@Composable
fun AuthenticatorScreen(viewModel: FortisViewModel, onBack: () -> Unit = {}) = Rebuilding("AUTHENTICATOR", onBack)

@Composable
private fun Rebuilding(title: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(FortisSpacing.screenPadding)) {
        Text(title, style = MaterialTheme.typography.headlineLarge, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(bottom = 16.dp))
        Text("The phone vault is being rebuilt to keep your passwords encrypted on the phone and sync them from your vault laptop. " +
            "Use the desktop app or `fortis get` meanwhile.",
            fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        FortisActionButton(text = "BACK", onClick = onBack)
    }
}
