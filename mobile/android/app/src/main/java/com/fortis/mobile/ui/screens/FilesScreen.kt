package com.fortis.mobile.ui.screens

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.fortis.mobile.ensureTailscaleActive
import com.fortis.mobile.data.LaptopApi
import com.fortis.mobile.data.Listing
import com.fortis.mobile.ui.rememberPhoneBackup
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

    // Upload state
    var isUploading by remember { mutableStateOf(false) }
    var currentFileIndex by remember { mutableStateOf(0) }
    var totalFiles by remember { mutableStateOf(0) }
    var currentFileName by remember { mutableStateOf("") }
    var uploadProgress by remember { mutableStateOf(0f) }

    // Backup dialog
    var showBackupDialog by remember { mutableStateOf(false) }
    val startBackup = rememberPhoneBackup()

    if (showBackupDialog) {
        FortisConfirmDialog(
            title = "Confirm Backup",
            message = "Are you sure you want to force a laptop backup?",
            onConfirm = {
                showBackupDialog = false
                startBackup()
            },
            onDismiss = { showBackupDialog = false }
        )
    }

    if (isUploading) {
        AlertDialog(
            onDismissRequest = { /* Prevent dismissing */ },
            title = {
                Text("Uploading Files...", fontFamily = FrostMono, style = MaterialTheme.typography.titleLarge)
            },
            text = {
                Column {
                    Text(
                        "File $currentFileIndex of $totalFiles: $currentFileName",
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FrostMono,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { uploadProgress },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "${(uploadProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FrostMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {},
            containerColor = MaterialTheme.colorScheme.surface,
            textContentColor = MaterialTheme.colorScheme.onSurface
        )
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isEmpty() || activeDevice == null) return@rememberLauncherForActivityResult
        if (!ensureTailscaleActive(context)) return@rememberLauncherForActivityResult
        totalFiles = uris.size
        currentFileIndex = 0
        isUploading = true
        coroutineScope.launch {
            var sent = 0
            val failed = mutableListOf<String>()
            for (uri in uris) {
                val fileName = getFileName(context, uri)
                currentFileIndex++
                currentFileName = fileName
                uploadProgress = 0f
                try {
                    LaptopApi.uploadFile(
                        name = fileName,
                        size = fileSize(context, uri),
                        open = { context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("can't read $fileName") },
                        onProgress = { uploadProgress = it },
                    )
                    sent++
                } catch (e: Exception) {
                    failed += "$fileName (${e.message})"
                }
            }
            isUploading = false
            context.toast(
                if (failed.isEmpty()) "Sent $sent file(s) to the laptop"
                else "Sent $sent, failed ${failed.size}: ${failed.joinToString()}",
                long = failed.isNotEmpty(),
            )
        }
    }

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

    // Load the default folder, and again whenever the active laptop changes
    LaunchedEffect(activeDevice?.uuid) {
        listing = null
        fetchFiles("")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            
            .padding(FortisSpacing.screenPadding)
    ) {
        // ─── Action Buttons Row ──────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FortisCompactActionButton(
                text = "SEND FILES",
                onClick = { filePickerLauncher.launch("*/*") },
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
            FortisCompactActionButton(
                text = "BACKUP",
                onClick = { showBackupDialog = true },
                modifier = Modifier.weight(1f)
            )
        }

        // ─── File Explorer ───────────────────────────────────────
        FortisCard(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { listing?.parent?.let { fetchFiles(it) } },
                    enabled = listing?.parent != null && !isLoading,
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

private fun fileSize(context: Context, uri: Uri): Long =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
    } ?: 0L

private fun getFileName(context: Context, uri: Uri): String {
    var result: String? = null
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        try {
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    result = cursor.getString(index)
                }
            }
        } finally {
            cursor?.close()
        }
    }
    if (result == null) {
        result = uri.path
        val cut = result?.lastIndexOf('/') ?: -1
        if (cut != -1) {
            result = result?.substring(cut + 1)
        }
    }
    return result ?: "uploaded_file"
}
