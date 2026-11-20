package com.fortis.mobile.ui.screens

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.fortis.mobile.data.LaptopApi
import com.fortis.mobile.data.Listing
import com.fortis.mobile.ui.fileSender
import com.fortis.mobile.ui.sendClipboardToLaptop
import com.fortis.mobile.ui.toast
import com.fortis.mobile.ui.FortisViewModel
import com.fortis.mobile.ui.components.*
import com.fortis.mobile.ui.theme.*
import kotlinx.coroutines.launch


@Composable
fun FilesScreen(viewModel: FortisViewModel) {
    val context = LocalContext.current
    val activeDevice by viewModel.activeDevice.collectAsState()

    val coroutineScope = rememberCoroutineScope()

    // File explorer state
    var listing by remember { mutableStateOf<Listing?>(null) }
    val currentPath = listing?.path ?: ""
    val files = listing?.entries ?: emptyList()
    var isLoading by remember { mutableStateOf(false) }

    val sendFiles = fileSender()

    fun fetchFiles(path: String) {
        if (activeDevice == null) return
        isLoading = true
        coroutineScope.launch {
            try {
                listing = LaptopApi.listFiles(path)
            } catch (e: Exception) {
                context.toast("Couldn't open the folder: ${e.message}")
            } finally {
                isLoading = false
            }
        }
    }

    // The shared folders (agent.toml [files] roots); Up stops at the top of each
    var roots by remember { mutableStateOf<List<String>>(emptyList()) }
    val atRoot = currentPath in roots || roots.isEmpty()
    val currentRoot = roots.firstOrNull { currentPath == it || currentPath.startsWith("$it/") }

    // Load the default folder, and again whenever the active laptop changes
    LaunchedEffect(activeDevice?.uuid) {
        listing = null
        roots = runCatching { LaptopApi.fileRoots() }.getOrDefault(emptyList())
        fetchFiles("")
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ─── Action Buttons Row ──────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FortisCompactActionButton(
                text = "SEND FILES",
                onClick = sendFiles,
                modifier = Modifier.weight(1f)
            )
            FortisCompactActionButton(
                text = "CLIPBOARD",
                onClick = {
                    if (activeDevice == null) {
                        context.toast("Pick a laptop first")
                        return@FortisCompactActionButton
                    }
                    coroutineScope.sendClipboardToLaptop(context)
                },
                modifier = Modifier.weight(1f)
            )
        }

        if (roots.size > 1) {
            FrostChips(roots.map { it to it.substringAfterLast('/') }, currentRoot ?: "", { fetchFiles(it) })
        }

        // ─── File Explorer ───────────────────────────────────────
        FortisCard(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { listing?.parent?.let { fetchFiles(it) } },
                    enabled = !atRoot && listing?.parent != null && !isLoading,
                    shape = RoundedCornerShape(FortisSpacing.buttonCorner),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, MaterialTheme.colorScheme.outline
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("UP", fontFamily = FrostMono, style = MaterialTheme.typography.labelMedium)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    currentPath,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FrostMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ─── File List ───────────────────────────────────────────
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(files) { file ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clickable {
                            val filePath = listing?.childPath(file) ?: return@clickable
                            if (file.isDir) {
                                fetchFiles(filePath)
                            } else {
                                context.toast("Downloading ${file.name}…")
                                coroutineScope.launch {
                                    context.toast(
                                        try {
                                            "Saved to " + LaptopApi.download(context, filePath, file.name)
                                        } catch (e: Exception) {
                                            "Download failed: ${e.message}"
                                        },
                                        long = true,
                                    )
                                }
                            }
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    shape = RoundedCornerShape(FortisSpacing.buttonCorner)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (file.isDir) "📁" else "📄",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                file.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FrostMono,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (!file.isDir) {
                                Text(
                                    "${file.size / 1024} KB",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FrostMono,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
