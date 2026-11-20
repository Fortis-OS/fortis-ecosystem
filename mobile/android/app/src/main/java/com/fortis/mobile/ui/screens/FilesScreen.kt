package com.fortis.mobile.ui.screens

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.widget.Toast
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
import com.fortis.mobile.network.FortisApiClient
import com.fortis.mobile.ui.FortisViewModel
import com.fortis.mobile.ui.components.*
import com.fortis.mobile.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.InputStream

data class RemoteFile(val name: String, val isDir: Boolean, val size: Long)

@Composable
fun FilesScreen(viewModel: FortisViewModel) {
    val context = LocalContext.current
    val activeDevice by viewModel.activeDevice.collectAsState()

    val ip = activeDevice?.ipAddress ?: ""
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager

    // File explorer state
    var currentPath by remember { mutableStateOf("") }
    var files by remember { mutableStateOf(emptyList<RemoteFile>()) }
    var isLoading by remember { mutableStateOf(false) }

    // Upload state
    var isUploading by remember { mutableStateOf(false) }
    var currentFileIndex by remember { mutableStateOf(0) }
    var totalFiles by remember { mutableStateOf(0) }
    var currentFileName by remember { mutableStateOf("") }
    var uploadProgress by remember { mutableStateOf(0f) }

    // Backup dialog
    var showBackupDialog by remember { mutableStateOf(false) }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(context, "Termux Permission Granted! Try again.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Termux RUN_COMMAND permission is required!", Toast.LENGTH_LONG).show()
        }
    }

    if (showBackupDialog) {
        FortisConfirmDialog(
            title = "Confirm Backup",
            message = "Are you sure you want to force a laptop backup?",
            onConfirm = {
                showBackupDialog = false
                if (ip.isEmpty()) {
                    Toast.makeText(context, "Set IP/Key in Workspace!", Toast.LENGTH_SHORT).show()
                    return@FortisConfirmDialog
                }
                if (context.checkSelfPermission("com.termux.permission.RUN_COMMAND") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    permissionLauncher.launch("com.termux.permission.RUN_COMMAND")
                    return@FortisConfirmDialog
                }
                try {
                    val intent = android.content.Intent("com.termux.RUN_COMMAND")
                    intent.setClassName("com.termux", "com.termux.app.RunCommandService")
                    intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                    intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", "bash /data/data/com.termux/files/home/.local/bin/fortis-mobile start > /data/data/com.termux/files/home/intent.log 2>&1"))
                    intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
                    intent.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "4")
                    context.startService(intent)
                } catch (e: Exception) {
                    Toast.makeText(context, "Failed to start Termux: ${e.message}", Toast.LENGTH_LONG).show()
                }

                coroutineScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val client = FortisApiClient.getClient()
                            val json = """{"action":"run_backup"}"""
                            val body = json.toRequestBody("application/json".toMediaType())
                            val request = FortisApiClient.buildRequest("/api/v1/actions") { post(body) }
                            client.newCall(request).execute().close()
                        }
                    } catch (e: Exception) {}
                }
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
        if (uris.isNotEmpty()) {
            if (ip.isEmpty()) return@rememberLauncherForActivityResult
            if (!ensureTailscaleActive(context)) return@rememberLauncherForActivityResult
            totalFiles = uris.size
            currentFileIndex = 0
            isUploading = true

            coroutineScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val client = FortisApiClient.getClient()
                        var successCount = 0
                        var failCount = 0

                        for (uri in uris) {
                            try {
                                val fileName = getFileName(context, uri)
                                val inputStream: InputStream? = context.contentResolver.openInputStream(uri)

                                var fileSize = 0L
                                context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                                    if (cursor.moveToFirst()) {
                                        fileSize = cursor.getLong(0)
                                    }
                                }

                                withContext(Dispatchers.Main) {
                                    currentFileIndex++
                                    currentFileName = fileName
                                    uploadProgress = 0f
                                }

                                if (inputStream != null) {
                                    val streamBody = object : okhttp3.RequestBody() {
                                        override fun contentType() = "application/octet-stream".toMediaType()
                                        override fun writeTo(sink: okio.BufferedSink) {
                                            inputStream.use { stream ->
                                                val buffer = ByteArray(8192)
                                                var bytesRead: Int
                                                var totalBytesRead = 0L
                                                while (stream.read(buffer).also { bytesRead = it } != -1) {
                                                    sink.write(buffer, 0, bytesRead)
                                                    totalBytesRead += bytesRead
                                                    val progress = if (fileSize > 0) totalBytesRead.toFloat() / fileSize else 0f
                                                    if (progress - uploadProgress > 0.01f || progress == 1f) {
                                                        uploadProgress = progress
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    val requestBody = MultipartBody.Builder()
                                        .setType(MultipartBody.FORM)
                                        .addFormDataPart("file", fileName, streamBody)
                                        .build()

                                    val request = FortisApiClient.buildRequest("/api/v1/files/drop?dest=drop") { post(requestBody) }

                                    client.newCall(request).execute().close()
                                    successCount++
                                }
                            } catch (e: Exception) {
                                failCount++
                            }
                        }
                        withContext(Dispatchers.Main) {
                            isUploading = false
                            Toast.makeText(context, "Shared $successCount files. Failed $failCount.", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Upload failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun fetchFiles(path: String) {
        if (ip.isEmpty()) return
        isLoading = true
        coroutineScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val client = FortisApiClient.getClient()
                    val q = if (path.isEmpty()) "" else "?path=" + android.net.Uri.encode(path)
                    val request = FortisApiClient.buildRequest("/api/v1/files/list$q") { get() }
                    val response = client.newCall(request).execute()
                    val responseBody = response.body?.string() ?: ""
                    if (response.isSuccessful) {
                        val jsonObj = JSONObject(responseBody)
                        val returnedPath = jsonObj.optString("path", "")
                        val filesArray = jsonObj.optJSONArray("entries")
                        val newList = mutableListOf<RemoteFile>()
                        if (filesArray != null) {
                            for (i in 0 until filesArray.length()) {
                                val fObj = filesArray.getJSONObject(i)
                                newList.add(RemoteFile(fObj.getString("name"), fObj.getBoolean("is_dir"), fObj.getLong("size")))
                            }
                        }
                        newList.sortWith(compareBy({ !it.isDir }, { it.name.lowercase() }))

                        withContext(Dispatchers.Main) {
                            currentPath = returnedPath
                            files = newList
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Error: ${response.code}", Toast.LENGTH_SHORT).show()
                        }
                    }
                    response.close()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Network Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        if (currentPath.isEmpty()) {
            fetchFiles("")
        }
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
                        Toast.makeText(context, "Select a device first!", Toast.LENGTH_SHORT).show()
                        return@FortisCompactActionButton
                    }
                    if (!ensureTailscaleActive(context)) return@FortisCompactActionButton
                    val clipData = clipboardManager.primaryClip
                    if (clipData != null && clipData.itemCount > 0) {
                        val text = clipData.getItemAt(0).text.toString()
                        coroutineScope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    val client = FortisApiClient.getClient()
                                    val body = org.json.JSONObject().put("text", text).toString().toRequestBody("application/json".toMediaType())
                                    val request = FortisApiClient.buildRequest("/api/v1/clipboard") { post(body) }
                                    client.newCall(request).execute().close()
                                }
                                Toast.makeText(context, "Sent to Laptop!", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        Toast.makeText(context, "Clipboard is empty!", Toast.LENGTH_SHORT).show()
                    }
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
                    onClick = {
                        val parent = currentPath.substringBeforeLast("/", "")
                        fetchFiles(if (parent.isEmpty()) "/" else parent)
                    },
                    enabled = currentPath.isNotEmpty() && currentPath != "/" && !isLoading,
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
                            if (file.isDir) {
                                val nextPath = if (currentPath.endsWith("/")) currentPath + file.name else currentPath + "/" + file.name
                                fetchFiles(nextPath)
                            } else {
                                val filePath = if (currentPath.endsWith("/")) currentPath + file.name else currentPath + "/" + file.name
                                Toast.makeText(context, "Downloading ${file.name}...", Toast.LENGTH_SHORT).show()
                                coroutineScope.launch {
                                    val msg = try {
                                        "Saved to " + withContext(Dispatchers.IO) { com.fortis.mobile.network.Downloads.download(context, filePath, file.name) }
                                    } catch (e: Exception) {
                                        "Download failed: ${e.message}"
                                    }
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
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
