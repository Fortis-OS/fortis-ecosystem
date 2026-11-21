package com.fortis.mobile.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fortis.mobile.data.LaptopApi
import com.fortis.mobile.data.Listing
import com.fortis.mobile.data.RemoteEntry
import com.fortis.mobile.ui.FortisViewModel
import com.fortis.mobile.ui.components.FortisCaption
import com.fortis.mobile.ui.components.FrostChips
import com.fortis.mobile.ui.components.pressable
import com.fortis.mobile.ui.fileSender
import com.fortis.mobile.ui.files.FileKind
import com.fortis.mobile.ui.files.humanDate
import com.fortis.mobile.ui.files.humanSize
import com.fortis.mobile.ui.files.mimeOf
import com.fortis.mobile.ui.files.openInApp
import com.fortis.mobile.ui.files.shareFile
import com.fortis.mobile.ui.sendClipboardToLaptop
import com.fortis.mobile.ui.theme.Frost
import com.fortis.mobile.ui.theme.FrostMono
import com.fortis.mobile.ui.theme.FrostSans
import com.fortis.mobile.ui.toast
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private enum class SortBy(val label: String) { Name("Name"), Date("Date modified"), Size("Size") }

/** What to do with a file once it's fetched into the phone's cache. */
private enum class AfterFetch { Open, OpenWith, Share }

/**
 * Laptop → Files: a real file manager over the laptop's shared folders (agent.toml [files] roots).
 * Tap a folder to enter it, tap a file to open it in an Android app, long-press for more.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(viewModel: FortisViewModel) {
    val context = LocalContext.current
    val activeDevice by viewModel.activeDevice.collectAsState()
    val scope = rememberCoroutineScope()
    val sendFiles = fileSender()

    var listing by remember { mutableStateOf<Listing?>(null) }
    var loading by remember { mutableStateOf(false) }
    var roots by remember { mutableStateOf<List<String>>(emptyList()) }
    var sortBy by rememberSaveable { mutableStateOf(SortBy.Name) }
    var sortMenu by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<RemoteEntry?>(null) }
    var detailsFor by remember { mutableStateOf<RemoteEntry?>(null) }
    var fetching by remember { mutableStateOf<Pair<String, Float>?>(null) }   // name, progress
    var fetchJob by remember { mutableStateOf<Job?>(null) }
    // Downloading a whole folder: counting / waiting for confirmation / downloading
    var folderProgress by remember { mutableStateOf<Triple<String, String, Float?>?>(null) }   // title, line, progress
    var folderConfirm by remember { mutableStateOf<Triple<String, List<LaptopApi.FolderFile>, Int>?>(null) }   // name, files, skipped
    var folderJob by remember { mutableStateOf<Job?>(null) }

    val path = listing?.path ?: ""
    val root = roots.firstOrNull { path == it || path.startsWith("$it/") }
    val atRoot = root == null || path == root

    fun load(p: String) {
        if (activeDevice == null) return
        loading = true
        scope.launch {
            try {
                listing = LaptopApi.listFiles(p)
            } catch (e: Exception) {
                context.toast("Couldn't open the folder: ${e.message}")
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(activeDevice?.uuid) {
        listing = null
        roots = runCatching { LaptopApi.fileRoots() }.getOrDefault(emptyList())
        load("")
    }

    // The system back gesture goes up a folder until the top of the shared folder
    BackHandler(enabled = !atRoot && listing?.parent != null) { listing?.parent?.let { load(it) } }

    fun fetchThen(entry: RemoteEntry, then: AfterFetch) {
        val l = listing ?: return
        fetchJob = scope.launch {
            fetching = entry.name to 0f
            try {
                val file = LaptopApi.fetchToCache(context, l.childPath(entry), entry.name, entry.size, entry.modified,
                    onProgress = { p -> fetching = entry.name to p })
                fetching = null
                when (then) {
                    AfterFetch.Open -> if (!openInApp(context, file, entry.name)) {
                        if (!openInApp(context, file, entry.name, chooser = true)) context.toast("No app on this phone can open ${entry.name}")
                    }
                    AfterFetch.OpenWith -> openInApp(context, file, entry.name, chooser = true)
                    AfterFetch.Share -> shareFile(context, file, entry.name)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                context.toast("Couldn't open ${entry.name}: ${e.message}", long = true)
            } finally {
                fetching = null
            }
        }
    }

    fun download(entry: RemoteEntry) {
        val l = listing ?: return
        context.toast("Downloading ${entry.name}…")
        scope.launch {
            context.toast(
                try { "Saved to " + LaptopApi.download(context, l.childPath(entry), entry.name) }
                catch (e: Exception) { "Download failed: ${e.message}" },
                long = true,
            )
        }
    }

    /** Step 1: count what's in the folder, then ask. */
    fun countFolder(entry: RemoteEntry) {
        val l = listing ?: return
        folderJob = scope.launch {
            folderProgress = Triple("Download ${entry.name}", "Counting files…", null)
            var skipped = 0
            try {
                val files = LaptopApi.walkFolder(
                    l.childPath(entry),
                    skipped = { skipped++ },
                    onFound = { n -> folderProgress = Triple("Download ${entry.name}", "Counting files… $n", null) },
                )
                folderProgress = null
                if (files.isEmpty()) context.toast("${entry.name} has no files to download")
                else folderConfirm = Triple(entry.name, files, skipped)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: LaptopApi.FolderTooBig) {
                context.toast("${entry.name} has ${e.message}. Download the folders inside it instead.", long = true)
            } catch (e: Exception) {
                context.toast("Couldn't read ${entry.name}: ${e.message}", long = true)
            } finally {
                folderProgress = null
            }
        }
    }

    /** Step 2: download every file into Downloads/Fortis/<folder>/…, keeping the subfolders. */
    fun downloadFolder(name: String, files: List<LaptopApi.FolderFile>) {
        folderJob = scope.launch {
            var saved = 0
            val failed = mutableListOf<String>()
            try {
                files.forEachIndexed { i, f ->
                    folderProgress = Triple("Downloading $name", "${i + 1} of ${files.size}: ${f.entry.name}", i.toFloat() / files.size)
                    try {
                        LaptopApi.download(context, f.remotePath, f.entry.name, subdir = if (f.relDir.isEmpty()) name else "$name/${f.relDir}")
                        saved++
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed += f.entry.name
                    }
                }
                context.toast(
                    "Saved $saved file(s) to Downloads/Fortis/$name" +
                        if (failed.isEmpty()) "" else ". Failed ${failed.size}: ${failed.take(5).joinToString()}${if (failed.size > 5) "…" else ""}",
                    long = true,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                context.toast("Stopped. $saved of ${files.size} file(s) were saved to Downloads/Fortis/$name", long = true)
                throw e
            } finally {
                folderProgress = null
            }
        }
    }

    fun copyPath(entry: RemoteEntry) {
        val l = listing ?: return
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Path", l.childPath(entry)))
        context.toast("Path copied")
    }

    val entries = (listing?.entries ?: emptyList()).let { list ->
        val sorted = when (sortBy) {
            SortBy.Name -> list.sortedBy { it.name.lowercase() }
            SortBy.Date -> list.sortedByDescending { it.modified }
            SortBy.Size -> list.sortedByDescending { it.size }
        }
        sorted.sortedBy { !it.isDir }   // folders first, keeping the order within each group
    }

    Column(Modifier.fillMaxSize()) {
        if (roots.size > 1) {
            FrostChips(roots.map { it to it.substringAfterLast('/') }, root ?: "", { load(it) })
        }

        // ─── Toolbar: where we are, and actions ───────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Breadcrumbs(path = path, root = root, onOpen = { load(it) }, modifier = Modifier.weight(1f))
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort", tint = Frost.muted) }
                DropdownMenu(sortMenu, { sortMenu = false }, containerColor = Frost.sheet) {
                    SortBy.entries.forEach { s ->
                        DropdownMenuItem(
                            text = { Text(s.label, fontFamily = FrostSans, color = if (s == sortBy) Frost.accent else Frost.text) },
                            onClick = { sortBy = s; sortMenu = false },
                        )
                    }
                }
            }
            IconButton(onClick = sendFiles) { Icon(Icons.Default.UploadFile, "Send files to this folder", tint = Frost.muted) }
            IconButton(onClick = { scope.sendClipboardToLaptop(context) }) { Icon(Icons.Default.ContentPaste, "Send clipboard", tint = Frost.muted) }
        }
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Frost.accent, trackColor = Frost.faint)
            else HorizontalDivider(color = Frost.faint)
        }

        // ─── The list ─────────────────────────────────────────────────────
        if (!loading && listing != null && entries.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.FolderOpen, null, tint = Frost.subtle, modifier = Modifier.size(40.dp))
                Text("This folder is empty", fontFamily = FrostSans, color = Frost.muted, modifier = Modifier.padding(top = 8.dp))
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(entries, key = { it.name }) { e ->
                FileRow(
                    entry = e,
                    onClick = {
                        val l = listing ?: return@FileRow
                        if (e.isDir) load(l.childPath(e)) else fetchThen(e, AfterFetch.Open)
                    },
                    onLongClick = { menuFor = e },
                )
            }
        }
        if (listing != null) {
            val dirs = entries.count { it.isDir }
            FortisCaption(
                "$dirs folder${if (dirs == 1) "" else "s"} · ${entries.size - dirs} file${if (entries.size - dirs == 1) "" else "s"} · sent files go to FortisMobileDrop",
                Modifier.padding(top = 6.dp, start = 4.dp),
            )
        }
    }

    // ─── Long-press menu ──────────────────────────────────────────────────
    menuFor?.let { e ->
        val kind = FileKind.of(e.name, e.isDir)
        ModalBottomSheet(
            onDismissRequest = { menuFor = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Frost.sheet,
        ) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                    KindTile(kind)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.name, fontFamily = FrostSans, fontSize = 17.sp, color = Frost.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(subtitle(e, kind), fontFamily = FrostMono, fontSize = 12.sp, color = Frost.muted)
                    }
                }
                HorizontalDivider(color = Frost.faint)
                if (e.isDir) {
                    MenuRow(Icons.Default.FolderOpen, "Open") { menuFor = null; listing?.let { load(it.childPath(e)) } }
                    MenuRow(Icons.Default.Download, "Download folder to phone") { menuFor = null; countFolder(e) }
                } else {
                    MenuRow(Icons.Default.Visibility, "Open") { menuFor = null; fetchThen(e, AfterFetch.Open) }
                    MenuRow(Icons.AutoMirrored.Filled.OpenInNew, "Open with…") { menuFor = null; fetchThen(e, AfterFetch.OpenWith) }
                    MenuRow(Icons.Default.Download, "Download to phone") { menuFor = null; download(e) }
                    MenuRow(Icons.Default.Share, "Share") { menuFor = null; fetchThen(e, AfterFetch.Share) }
                }
                MenuRow(Icons.Default.ContentCopy, "Copy path") { menuFor = null; copyPath(e) }
                MenuRow(Icons.Default.Info, "Details") { menuFor = null; detailsFor = e }
            }
        }
    }

    // ─── Details ──────────────────────────────────────────────────────────
    detailsFor?.let { e ->
        val kind = FileKind.of(e.name, e.isDir)
        AlertDialog(
            onDismissRequest = { detailsFor = null },
            containerColor = Frost.sheet,
            icon = { KindTile(kind) },
            title = { Text(e.name, fontFamily = FrostSans, color = Frost.text, maxLines = 3, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    DetailRow("Type", if (e.isDir) "Folder" else "${kind.label} · ${mimeOf(e.name)}")
                    if (!e.isDir) DetailRow("Size", "${humanSize(e.size)} (${"%,d".format(e.size)} bytes)")
                    DetailRow("Modified", humanDate(e.modified).ifEmpty { "Unknown" })
                    DetailRow("Location", path)
                }
            },
            confirmButton = { TextButton(onClick = { detailsFor = null }) { Text("Close", fontFamily = FrostSans, color = Frost.accent) } },
        )
    }

    // ─── Downloading a folder: confirm, then progress ─────────────────────
    folderConfirm?.let { (name, files, skipped) ->
        val total = files.sumOf { it.entry.size }
        AlertDialog(
            onDismissRequest = { folderConfirm = null },
            containerColor = Frost.sheet,
            title = { Text("Download $name?", fontFamily = FrostSans, color = Frost.text) },
            text = {
                Column {
                    Text("${files.size} file(s), ${humanSize(total)}", fontFamily = FrostMono, color = Frost.text)
                    Text("Saved to Downloads/Fortis/$name, keeping the subfolders.", fontFamily = FrostSans, color = Frost.muted,
                        modifier = Modifier.padding(top = 8.dp))
                    if (skipped > 0) Text("$skipped folder(s) inside couldn't be read and will be skipped.", fontFamily = FrostSans,
                        color = Frost.warn, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = { folderConfirm = null; downloadFolder(name, files) }) { Text("Download", fontFamily = FrostSans, color = Frost.accent) }
            },
            dismissButton = {
                TextButton(onClick = { folderConfirm = null }) { Text("Cancel", fontFamily = FrostSans, color = Frost.muted) }
            },
        )
    }
    folderProgress?.let { (title, line, p) ->
        AlertDialog(
            onDismissRequest = {},
            containerColor = Frost.sheet,
            title = { Text(title, fontFamily = FrostSans, color = Frost.text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    Text(line, fontFamily = FrostMono, fontSize = 13.sp, color = Frost.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(12.dp))
                    if (p == null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Frost.accent, trackColor = Frost.faint)
                    else LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth(), color = Frost.accent, trackColor = Frost.faint)
                }
            },
            confirmButton = {
                TextButton(onClick = { folderJob?.cancel(); folderProgress = null }) { Text("Cancel", fontFamily = FrostSans, color = Frost.muted) }
            },
        )
    }

    // ─── Fetching a file to open it ───────────────────────────────────────
    fetching?.let { (name, p) ->
        AlertDialog(
            onDismissRequest = {},
            containerColor = Frost.sheet,
            title = { Text("Opening", fontFamily = FrostSans, color = Frost.text) },
            text = {
                Column {
                    Text(name, fontFamily = FrostMono, color = Frost.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth(), color = Frost.accent, trackColor = Frost.faint)
                }
            },
            confirmButton = {
                TextButton(onClick = { fetchJob?.cancel(); fetching = null }) { Text("Cancel", fontFamily = FrostSans, color = Frost.muted) }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(entry: RemoteEntry, onClick: () -> Unit, onLongClick: () -> Unit) {
    val kind = FileKind.of(entry.name, entry.isDir)
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 4.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KindTile(kind)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, fontFamily = FrostSans, fontSize = 15.sp, color = Frost.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle(entry, kind), fontFamily = FrostMono, fontSize = 11.sp, color = Frost.muted, maxLines = 1)
        }
        if (entry.isDir) Icon(Icons.Default.ChevronRight, null, tint = Frost.subtle, modifier = Modifier.size(18.dp))
    }
}

private fun subtitle(e: RemoteEntry, kind: FileKind): String {
    val date = humanDate(e.modified)
    return if (e.isDir) listOf("Folder", date).filter { it.isNotEmpty() }.joinToString(" · ")
    else listOf(humanSize(e.size), date).filter { it.isNotEmpty() }.joinToString(" · ")
}

/** The tinted square behind a file-type icon. */
@Composable
private fun KindTile(kind: FileKind) {
    Box(
        Modifier.size(40.dp).background(kind.tint.copy(alpha = 0.16f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(kind.icon, null, tint = kind.tint, modifier = Modifier.size(22.dp))
    }
}

/** Home › dev › Fortis, each part tappable; the shared root is shown as its folder name (or "Home"). */
@Composable
private fun Breadcrumbs(path: String, root: String?, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    LaunchedEffect(path) { scroll.animateScrollTo(scroll.maxValue) }
    Row(modifier.horizontalScroll(scroll).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (root == null) {
            Text(path, fontFamily = FrostMono, fontSize = 13.sp, color = Frost.muted)
            return@Row
        }
        val rootLabel = if (Regex("/home/[^/]+").matches(root)) "Home" else root.substringAfterLast('/')
        val parts = path.removePrefix(root).split('/').filter { it.isNotEmpty() }
        val crumbs = listOf(rootLabel to root) + parts.mapIndexed { i, p -> p to root + "/" + parts.take(i + 1).joinToString("/") }
        crumbs.forEachIndexed { i, (label, target) ->
            val last = i == crumbs.lastIndex
            if (i > 0) Icon(Icons.Default.ChevronRight, null, tint = Frost.subtle, modifier = Modifier.size(16.dp))
            Text(
                label,
                fontFamily = FrostSans,
                fontSize = 14.sp,
                color = if (last) Frost.text else Frost.muted,
                maxLines = 1,
                modifier = Modifier
                    .then(if (last) Modifier else Modifier.pressable { onOpen(target) })
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(onClick = onClick).padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Frost.muted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(18.dp))
        Text(label, fontFamily = FrostSans, fontSize = 16.sp, color = Frost.text)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(Modifier.padding(bottom = 10.dp)) {
        FortisCaption(label)
        Text(value, fontFamily = FrostMono, fontSize = 13.sp, color = Frost.text)
    }
}
