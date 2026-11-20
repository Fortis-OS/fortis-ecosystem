package com.fortis.mobile.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fortis.mobile.encrypt.AuthenticatorScreen
import com.fortis.mobile.encrypt.VaultScreen
import com.fortis.mobile.ui.FortisViewModel
import com.fortis.mobile.ui.components.FrostChips

enum class VaultPane(val label: String) { Passwords("Passwords"), Codes("2FA") }

/**
 * The Vault tab: the phone's own encrypted vault. Works without a selected laptop; syncing with
 * the vault laptop happens in the background and shows as a status line.
 */
@Composable
fun VaultTab(viewModel: FortisViewModel) {
    var pane by rememberSaveable { mutableStateOf(VaultPane.Passwords) }
    Column(Modifier.fillMaxSize()) {
        FrostChips(
            VaultPane.entries.map { it to it.label },
            pane,
            { pane = it },
            Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )
        Box(Modifier.weight(1f)) {
            when (pane) {
                VaultPane.Passwords -> VaultScreen(viewModel = viewModel)
                VaultPane.Codes -> AuthenticatorScreen(viewModel = viewModel)
            }
        }
    }
}
