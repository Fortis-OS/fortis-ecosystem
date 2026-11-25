package com.fortis.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.fortis.mobile.data.BrowserLockState
import com.fortis.mobile.data.LaptopApi
import com.fortis.mobile.ensureTailscaleActive
import com.fortis.mobile.network.BrowserUnlock
import com.fortis.mobile.ui.FortisViewModel
import com.fortis.mobile.ui.components.ErrorLine
import com.fortis.mobile.ui.components.FortisActionButton
import com.fortis.mobile.ui.components.FortisCaption
import com.fortis.mobile.ui.components.FortisCard
import com.fortis.mobile.ui.components.FortisCompactActionButton
import com.fortis.mobile.ui.components.FrostField
import com.fortis.mobile.ui.components.StatusRow
import com.fortis.mobile.ui.theme.Frost
import com.fortis.mobile.ui.theme.FrostMono
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Fortis Lock (security §7.8): hide chosen sites in the laptop's browser behind a fake
 * certificate error. Lock with one tap; unlock with a fingerprint; edit the sites.
 */
@Composable
fun BrowserLockPane(viewModel: FortisViewModel) {
    val context = LocalContext.current
    val device by viewModel.activeDevice.collectAsState()
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<BrowserLockState?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var newSite by remember { mutableStateOf("") }

    suspend fun load() {
        state = try { LaptopApi.browserLock().also { error = null } } catch (e: Exception) {
            error = "Couldn't reach the laptop: ${e.message}"; state
        }
    }
    LaunchedEffect(device?.uuid) {
        while (true) { load(); delay(5_000) }
    }
    fun run(block: suspend () -> BrowserLockState) {
        if (!ensureTailscaleActive(context)) return
        busy = true
        scope.launch {
            try { state = block(); error = null } catch (e: Exception) { error = e.message ?: "Failed" }
            busy = false
        }
    }
    fun saveSites(sites: List<String>) = run { LaptopApi.setBrowserLockSites(sites) }

    val s = state
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        FortisCard {
            FortisCaption("Browser lock", Modifier.padding(bottom = 4.dp))
            StatusRow(
                "Locked sites",
                when { s == null -> "--"; s.locked -> "Hidden"; else -> "Visible" },
                s?.let { it.locked },
            )
            if (s != null && !s.hasPassword) {
                FortisCaption("No laptop password set: only this phone can unlock.", Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(10.dp))
            if (s?.locked == true) {
                FortisActionButton(
                    text = "Unlock with fingerprint",
                    isPrimary = true,
                    isLoading = busy,
                    onClick = {
                        val activity = context as? FragmentActivity ?: return@FortisActionButton
                        val laptop = device ?: return@FortisActionButton
                        run { BrowserUnlock.unlock(activity, laptop) }
                    },
                )
            } else {
                FortisActionButton(
                    text = "Lock browser now",
                    isPrimary = true,
                    isLoading = busy,
                    enabled = s != null && s.sites.isNotEmpty(),
                    onClick = { run { LaptopApi.browserLockOn() } },
                )
            }
            error?.let { ErrorLine(it) }
        }

        FortisCard {
            FortisCaption("Sites", Modifier.padding(bottom = 6.dp))
            if (s?.sites.isNullOrEmpty()) {
                Text("No sites yet.", color = Frost.muted, fontSize = 14.sp)
            }
            s?.sites?.forEach { site ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(site, fontFamily = FrostMono, fontSize = 14.sp, color = Frost.text, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    FortisCompactActionButton(text = "Remove", enabled = !busy, onClick = { saveSites(s.sites - site) })
                }
            }
            Spacer(Modifier.height(8.dp))
            FrostField(
                value = newSite,
                onValueChange = { newSite = it.trim() },
                label = "Add a site",
                placeholder = "web.whatsapp.com",
                mono = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            )
            Spacer(Modifier.height(8.dp))
            FortisActionButton(
                text = "Add site",
                enabled = !busy && newSite.isNotBlank() && s != null,
                onClick = { val add = newSite; newSite = ""; saveSites(s!!.sites + add) },
            )
            FortisCaption(
                "A site covers its subdomains. Changes reach the browser at once, even while locked.",
                Modifier.padding(top = 8.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}
