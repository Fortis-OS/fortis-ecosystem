package com.fortis.mobile.encrypt

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.fortis.mobile.ui.FortisViewModel
import com.fortis.mobile.network.FortisApiClient
import com.fortis.mobile.domain.VaultEntry
import com.fortis.mobile.ui.components.FortisActionButton
import com.fortis.mobile.ui.components.FortisSpacing
import com.fortis.mobile.ui.theme.StatusGreen
import com.fortis.mobile.ui.theme.StatusRed
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch

@Composable
fun AuthenticatorScreen(viewModel: FortisViewModel, onBack: () -> Unit = {}) {
    val activeDevice by viewModel.activeDevice.collectAsState()

    var entries by remember { mutableStateOf<List<VaultEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showAddDialog by remember { mutableStateOf(false) }
    var newEntryTitle by remember { mutableStateOf("") }
    var newEntrySecret by remember { mutableStateOf("") }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            try {
                val uri = Uri.parse(result.contents)
                if (uri.scheme == "otpauth" && uri.host == "totp") {
                    val secret = uri.getQueryParameter("secret")
                    val path = uri.path?.removePrefix("/") ?: "Unknown"
                    val issuer = uri.getQueryParameter("issuer") ?: path
                    
                    if (secret != null) {
                        coroutineScope.launch {
                            try {
                                FortisApiClient.addTotpEntry(issuer, secret)
                                Toast.makeText(context, "Added Token", Toast.LENGTH_SHORT).show()
                                val fetched = FortisApiClient.fetchVaultEntries()
                                entries = fetched.filter { it.totpSecret != null }
                            } catch (e: Exception) {
                                error = e.message
                            }
                        }
                    }
                } else {
                    Toast.makeText(context, "Invalid QR Code", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Invalid QR Code", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(activeDevice) {
        if (activeDevice != null) {
            isLoading = true
            try {
                val fetched = FortisApiClient.fetchVaultEntries()
                entries = fetched.filter { it.totpSecret != null }
            } catch (e: Exception) {
                error = e.message
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            currentTime = System.currentTimeMillis()
            delay(1000L)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(FortisSpacing.screenPadding)
    ) {
        FortisActionButton(
            text = "< GO_BACK",
            onClick = onBack,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "AUTHENTICATOR",
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.headlineLarge,
                fontFamily = FontFamily.Monospace,
            )
            
            Button(
                onClick = { showAddDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(FortisSpacing.buttonCorner)
            ) {
                Text("+ ADD", fontFamily = FontFamily.Monospace)
            }
        }

        if (isLoading) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        } else if (error != null) {
            Text(
                text = "Error: $error",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        } else if (entries.isEmpty()) {
            Text(
                text = "No TOTP secrets found in Vault.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        } else {
            LazyColumn {
                items(entries) { item ->
                    AuthenticatorItemCard(
                        title = item.title,
                        secretBase32 = item.totpSecret!!,
                        currentTimeMillis = currentTime
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Token", fontFamily = FontFamily.Monospace) },
            text = {
                Column {
                    Button(
                        onClick = {
                            showAddDialog = false
                            val options = ScanOptions().apply {
                                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                setPrompt("Scan Authenticator QR Code")
                                setBeepEnabled(false)
                            }
                            scanLauncher.launch(options)
                        },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        shape = RoundedCornerShape(FortisSpacing.buttonCorner)
                    ) {
                        Text("SCAN QR CODE", fontFamily = FontFamily.Monospace)
                    }
                    
                    Text("Or enter manually:", fontFamily = FontFamily.Monospace, modifier = Modifier.padding(bottom = 8.dp))
                    
                    OutlinedTextField(
                        value = newEntryTitle,
                        onValueChange = { newEntryTitle = it },
                        label = { Text("Account Name") },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = newEntrySecret,
                        onValueChange = { newEntrySecret = it },
                        label = { Text("Secret Key") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newEntryTitle.isNotBlank() && newEntrySecret.isNotBlank()) {
                        showAddDialog = false
                        coroutineScope.launch {
                            try {
                                FortisApiClient.addTotpEntry(newEntryTitle, newEntrySecret)
                                Toast.makeText(context, "Added Token", Toast.LENGTH_SHORT).show()
                                newEntryTitle = ""
                                newEntrySecret = ""
                                val fetched = FortisApiClient.fetchVaultEntries()
                                entries = fetched.filter { it.totpSecret != null }
                            } catch (e: Exception) {
                                error = e.message
                            }
                        }
                    }
                }) {
                    Text("SAVE")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("CANCEL")
                }
            }
        )
    }
}

@Composable
fun AuthenticatorItemCard(title: String, secretBase32: String, currentTimeMillis: Long) {
    val totpCode = TotpUtils.generateTotpCode(secretBase32, currentTimeMillis)
    val remainingSeconds = 30 - ((currentTimeMillis / 1000) % 30).toInt()
    val progress = remainingSeconds / 30f

    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 1000, easing = LinearEasing)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = if (totpCode.length == 6) "${totpCode.substring(0,3)} ${totpCode.substring(3)}" else totpCode,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp
            )
        }

        CircularProgressIndicator(
            progress = { animatedProgress },
            color = if (remainingSeconds > 5) StatusGreen else StatusRed,
            trackColor = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(36.dp),
            strokeWidth = 4.dp
        )
    }
}
