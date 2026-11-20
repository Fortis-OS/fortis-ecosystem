package com.fortis.mobile.encrypt

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.fortis.mobile.ui.FortisViewModel
import com.fortis.mobile.ui.components.*
import com.fortis.mobile.ui.theme.StatusGreen
import com.fortis.mobile.ui.theme.StatusRed
import com.fortis.mobile.vault.SecretClipboard
import com.fortis.mobile.vault.UserAuth
import com.fortis.mobile.vault.VaultError
import com.fortis.mobile.vault.VaultLocked
import com.fortis.mobile.vault.VaultRepository
import com.fortis.mobile.vault.VaultRepository.Companion.innerOf
import com.fortis.mobile.vault.VaultRepository.Companion.kindOf
import com.fortis.mobile.vault.VaultSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.fortis.mobile.ui.theme.FrostMono

// ---------------------------------------------------------------- shared

/** Hide vault screens from screenshots and the recents preview (docs/security §6.3). */
@Composable
private fun SecureWindow() {
    val activity = LocalContext.current as? Activity
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

@Composable
private fun Title(text: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onBackground) }
        Text(text, style = MaterialTheme.typography.headlineMedium, fontFamily = FrostMono,
            color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
private fun Screen(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxSize().padding(FortisSpacing.screenPadding), content = content)

private fun mono() = FrostMono

/**
 * Shows [unlocked] only when the phone vault is set up and unlocked; otherwise
 * walks the user through choosing a vault laptop, the one-time master
 * password, or the fingerprint unlock.
 */
@Composable
private fun VaultGate(title: String, onBack: (() -> Unit)?, unlocked: @Composable () -> Unit) {
    SecureWindow()
    val context = LocalContext.current
    val repo = remember { VaultRepository.get(context) }
    val isUnlocked by VaultSession.unlocked.collectAsState()
    var state by remember { mutableStateOf(repo.state()) }
    LaunchedEffect(isUnlocked) { state = repo.state() }
    when (state) {
        VaultRepository.State.Unlocked -> unlocked()
        VaultRepository.State.NoVaultLaptop -> Screen {
            Title(title, onBack)
            Text("Pick the laptop that holds your vault: in Settings → Laptops, tap \"Use for vault sync\" under it (or pair one first).",
                fontFamily = mono(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        VaultRepository.State.NotSetUp -> SetupScreen(title, repo, onBack) { state = repo.state() }
        VaultRepository.State.Locked -> UnlockScreen(title, repo, onBack) { state = repo.state() }
    }
}

@Composable
private fun SetupScreen(title: String, repo: VaultRepository, onBack: (() -> Unit)?, onDone: () -> Unit) {
    val activity = LocalContext.current as FragmentActivity
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val laptop = repo.vaultLaptop()
    Screen {
        Title(title, onBack)
        Text("One-time setup. Enter the master password of the vault on ${laptop?.name ?: "your laptop"}. " +
            "It's used once on this phone to open your vault key, then forgotten — after this you unlock with your fingerprint.\n\n" +
            "The laptop vault must be unlocked (fortis unlock).",
            fontFamily = mono(), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 16.dp))
        OutlinedTextField(password, { password = it }, label = { Text("Master password", fontFamily = mono()) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
        error?.let { Text(it, color = StatusRed, fontFamily = mono(), modifier = Modifier.padding(bottom = 8.dp)) }
        FortisActionButton(text = if (busy) "CHECKING…" else "SET UP", isPrimary = true, isLoading = busy, enabled = password.isNotEmpty(), onClick = {
            busy = true; error = null
            scope.launch {
                val chars = password.toCharArray(); password = ""
                val pending = try {
                    withContext(Dispatchers.IO) { repo.verifyPassword(chars).also { repo.prepareLocalKey() } }
                } catch (e: Exception) {
                    error = e.message; busy = false; return@launch
                }
                val authErr = UserAuth.authenticate(activity, "Protect your vault", "Confirm it's you to store the vault key on this phone")
                if (authErr != null) {
                    pending.discard(); error = authErr; busy = false; return@launch
                }
                try {
                    withContext(Dispatchers.IO) { repo.finishSetup(pending); runCatching { repo.sync() } }
                    onDone()
                } catch (e: Exception) {
                    pending.discard(); error = e.message
                } finally { busy = false }
            }
        })
    }
}

@Composable
private fun UnlockScreen(title: String, repo: VaultRepository, onBack: (() -> Unit)?, onDone: () -> Unit) {
    val activity = LocalContext.current as FragmentActivity
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val unlock: () -> Unit = {
        scope.launch {
            error = UserAuth.authenticate(activity, "Unlock vault", "Fingerprint or screen lock") ?: try {
                withContext(Dispatchers.IO) { repo.unlockAfterAuth() }
                onDone(); null
            } catch (e: Exception) { onDone(); e.message }
        }
    }
    LaunchedEffect(Unit) { unlock() }
    Screen {
        Title(title, onBack)
        Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp).align(Alignment.CenterHorizontally).padding(bottom = 16.dp))
        error?.let { Text(it, color = StatusRed, fontFamily = mono(), modifier = Modifier.padding(bottom = 12.dp)) }
        FortisActionButton(text = "UNLOCK", isPrimary = true, onClick = unlock)
    }
}

/** Run a repository call; maps a lock to the unlock screen and errors to a message. */
private suspend fun <T> vaultCall(onError: (String) -> Unit, block: () -> T): T? = try {
    withContext(Dispatchers.IO) { block() }
} catch (e: VaultLocked) {
    null // VaultGate notices the lock and shows the unlock screen
} catch (e: Exception) {
    onError(e.message ?: "Error"); null
}

// ---------------------------------------------------------------- vault list

@Composable
fun VaultScreen(viewModel: FortisViewModel, onBack: (() -> Unit)? = null) {
    VaultGate("VAULT", onBack) { VaultHome(onBack) }
}

@Composable
private fun VaultHome(onBack: (() -> Unit)?) {
    val context = LocalContext.current
    val repo = remember { VaultRepository.get(context) }
    var entries by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var syncing by remember { mutableStateOf(false) }
    var openId by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        vaultCall({ status = it }) { repo.list() }?.let { (list, bad) ->
            entries = list
            if (bad > 0) status = "$bad entries couldn't be decrypted"
        }
    }
    fun sync() {
        if (syncing) return
        syncing = true; status = "Syncing…"
        scope.launch {
            val r = vaultCall({ status = it }) { repo.sync() }
            if (r != null) status = "Synced · ${r.pulled} in, ${r.pushed} out" + if (r.conflicts > 0) ", ${r.conflicts} resolved" else ""
            reload(); syncing = false
        }
    }
    LaunchedEffect(Unit) { reload(); sync() }

    openId?.let { id -> EntryDetail(repo, id, onBack = { openId = null; scope.launch { reload() } }, onEdit = { editing = it }); }
    if (editing != null || creating) {
        EntryEditor(repo, editing, onDone = { saved ->
            editing = null; creating = false
            scope.launch { reload(); if (saved != null) { openId = saved; sync() } }
        })
        return
    }
    if (openId != null) return

    val shown = entries.filter { e ->
        query.isBlank() || listOf(e.optString("title"), e.optString("folder"), innerOf(e)?.optString("username").orEmpty())
            .any { it.contains(query, ignoreCase = true) }
    }
    Screen {
        Title("VAULT", onBack) {
            IconButton(onClick = { sync() }) { Icon(Icons.Default.Sync, "Sync", tint = MaterialTheme.colorScheme.onBackground) }
            IconButton(onClick = { repo.lock() }) { Icon(Icons.Default.Lock, "Lock", tint = MaterialTheme.colorScheme.onBackground) }
        }
        OutlinedTextField(query, { query = it }, placeholder = { Text("Search", fontFamily = mono()) }, singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth())
        if (status.isNotEmpty()) Text(status, fontFamily = mono(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.optString("id") }) { e ->
                Row(Modifier.fillMaxWidth().clickable { openId = e.optString("id") }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(kindIcon(kindOf(e)), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.optString("title"), fontFamily = mono(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                        val sub = listOf(innerOf(e)?.optString("username").orEmpty(), e.optString("folder").takeUnless { it == "null" }.orEmpty())
                            .filter { it.isNotEmpty() }.joinToString(" · ")
                        if (sub.isNotEmpty()) Text(sub, fontFamily = mono(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
        if (entries.isEmpty()) Text("No entries yet.", fontFamily = mono(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        FortisActionButton(text = "+ NEW ENTRY", isPrimary = true, onClick = { creating = true })
    }
}

private fun kindIcon(kind: String) = when (kind) {
    "Login" -> Icons.Default.Key
    "CreditCard" -> Icons.Default.CreditCard
    "Identity" -> Icons.Default.Badge
    "Totp" -> Icons.Default.Timer
    else -> Icons.Default.Description
}

// ---------------------------------------------------------------- detail

@Composable
private fun EntryDetail(repo: VaultRepository, id: String, onBack: () -> Unit, onEdit: (JSONObject) -> Unit) {
    val context = LocalContext.current
    var entry by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(id) { entry = vaultCall({ error = it }) { repo.get(id) } }
    val e = entry ?: return Screen { Title("ENTRY", onBack); error?.let { Text(it, color = StatusRed, fontFamily = mono()) } }
    val inner = innerOf(e) ?: JSONObject()
    val kind = kindOf(e)

    if (confirmDelete) {
        FortisConfirmDialog("Delete \"${e.optString("title")}\"?", "It's removed from the laptop on the next sync.",
            onConfirm = {
                confirmDelete = false
                scope.launch { vaultCall({ error = it }) { repo.delete(id); runCatching { repo.sync() } }; onBack() }
            }, onDismiss = { confirmDelete = false })
    }

    Screen {
        Title(e.optString("title"), onBack) {
            IconButton(onClick = { onEdit(e) }) { Icon(Icons.Default.Edit, "Edit", tint = MaterialTheme.colorScheme.onBackground) }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete", tint = StatusRed) }
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (inner.has("totp") && !inner.isNull("totp")) TotpRow(repo, e)
            fun s(k: String) = if (inner.isNull(k)) "" else inner.optString(k)
            when (kind) {
                "Login" -> {
                    Field("Username", s("username"), copy = true)
                    Field("Password", s("password"), secret = true, copy = true)
                    inner.optJSONArray("urls")?.let { a -> for (i in 0 until a.length()) Field("Website", a.getString(i)) }
                }
                "CreditCard" -> {
                    Field("Name on card", s("cardholder_name"))
                    Field("Number", s("number"), secret = true, copy = true)
                    Field("Expires", "%02d/%s".format(inner.optInt("exp_month"), s("exp_year")))
                    Field("CVV", s("cvv"), secret = true, copy = true)
                }
                "Identity" -> {
                    Field("Name", "${s("first_name")} ${s("last_name")}".trim())
                    for (k in listOf("email", "phone", "address")) if (s(k).isNotEmpty()) Field(k.replaceFirstChar { it.uppercase() }, s(k), copy = true)
                }
                "SecureNote" -> Field("Note", s("content"), secret = true, copy = true)
                "Totp" -> { if (s("issuer").isNotEmpty()) Field("Issuer", s("issuer")); if (s("account").isNotEmpty()) Field("Account", s("account")) }
            }
            if (e.optString("notes").isNotEmpty()) Field("Notes", e.optString("notes"), secret = true, copy = true)
            error?.let { Text(it, color = StatusRed, fontFamily = mono()) }
        }
    }
}

@Composable
private fun Field(label: String, value: String, secret: Boolean = false, copy: Boolean = false) {
    if (value.isEmpty()) return
    val context = LocalContext.current
    var shown by remember { mutableStateOf(!secret) }
    FortisCard(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text(label, fontFamily = mono(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (shown) value else "••••••••••", fontFamily = mono(), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            if (secret) IconButton(onClick = { shown = !shown }) { Icon(if (shown) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Show") }
            if (copy) IconButton(onClick = {
                SecretClipboard.copy(context, label, value)
                android.widget.Toast.makeText(context, "Copied — clears in 30 s", android.widget.Toast.LENGTH_SHORT).show()
            }) { Icon(Icons.Default.ContentCopy, "Copy") }
        }
    }
}

@Composable
private fun TotpRow(repo: VaultRepository, e: JSONObject) {
    val context = LocalContext.current
    var code by remember { mutableStateOf<Pair<String, Long>?>(null) }
    LaunchedEffect(e.optString("id")) { while (true) { code = repo.totp(e); delay(1000) } }
    val c = code ?: return
    FortisCard(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text("One-time code", fontFamily = mono(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.first.chunked((c.first.length + 1) / 2).joinToString(" "), fontFamily = mono(), fontSize = 28.sp,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text("${c.second}s", fontFamily = mono(), color = if (c.second <= 5) StatusRed else MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = { SecretClipboard.copy(context, "code", c.first) }) { Icon(Icons.Default.ContentCopy, "Copy") }
        }
    }
}

// ---------------------------------------------------------------- editor

private val KINDS = listOf("Login" to "Login", "CreditCard" to "Card", "Identity" to "Identity", "SecureNote" to "Note", "Totp" to "2FA")

@Composable
private fun EntryEditor(repo: VaultRepository, existing: JSONObject?, onDone: (String?) -> Unit) {
    var kind by remember { mutableStateOf(existing?.let { kindOf(it) } ?: "Login") }
    val old = existing?.let { innerOf(it) } ?: JSONObject()
    fun init(k: String) = when {
        k == "urls" -> old.optJSONArray("urls")?.let { a -> (0 until a.length()).joinToString("\n") { a.getString(it) } }.orEmpty()
        k == "totp" -> old.optJSONObject("totp")?.optString("secret_base32").orEmpty()
        old.isNull(k) -> ""
        else -> old.optString(k)
    }
    val v = remember { mutableStateMapOf<String, String>() }
    var title by remember { mutableStateOf(existing?.optString("title").orEmpty()) }
    var folder by remember { mutableStateOf(existing?.optString("folder")?.takeUnless { it == "null" }.orEmpty()) }
    var notes by remember { mutableStateOf(existing?.optString("notes").orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var showGen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val fields: List<Triple<String, String, Boolean>> = when (kind) { // key, label, secret
        "Login" -> listOf(Triple("username", "Username", false), Triple("password", "Password", true), Triple("urls", "Websites (one per line)", false), Triple("totp", "2FA secret (optional)", true))
        "CreditCard" -> listOf(Triple("cardholder_name", "Name on card", false), Triple("number", "Card number", true), Triple("exp_month", "Expiry month", false), Triple("exp_year", "Expiry year", false), Triple("cvv", "CVV", true))
        "Identity" -> listOf(Triple("first_name", "First name", false), Triple("last_name", "Last name", false), Triple("email", "Email", false), Triple("phone", "Phone", false), Triple("address", "Address", false))
        "SecureNote" -> listOf(Triple("content", "Note", true))
        else -> listOf(Triple("issuer", "Issuer", false), Triple("account", "Account", false), Triple("totp", "Secret (base32)", true))
    }
    fun f(k: String) = v[k] ?: init(k)

    if (showGen) {
        GeneratorDialog(onUse = { v["password"] = it; showGen = false }, onDismiss = { showGen = false })
    }

    Screen {
        Title(if (existing == null) "NEW ENTRY" else "EDIT", onBack = { onDone(null) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (existing == null) {
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    KINDS.forEach { (k, label) -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(label, fontFamily = mono(), fontSize = 12.sp) }) }
                }
            }
            OutlinedTextField(title, { title = it }, label = { Text("Title", fontFamily = mono()) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            for ((k, label, secret) in fields) {
                var visible by remember(k) { mutableStateOf(!secret) }
                OutlinedTextField(f(k), { v[k] = it }, label = { Text(label, fontFamily = mono()) },
                    singleLine = k != "urls" && k != "address" && k != "content",
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = if (k == "exp_month" || k == "exp_year") KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
                    trailingIcon = {
                        Row {
                            if (secret) IconButton(onClick = { visible = !visible }) { Icon(Icons.Default.Visibility, "Show") }
                            if (k == "password") IconButton(onClick = { showGen = true }) { Icon(Icons.Default.AutoFixHigh, "Generate") }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            OutlinedTextField(folder, { folder = it }, label = { Text("Folder (optional)", fontFamily = mono()) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            OutlinedTextField(notes, { notes = it }, label = { Text("Notes (optional)", fontFamily = mono()) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            error?.let { Text(it, color = StatusRed, fontFamily = mono(), modifier = Modifier.padding(top = 8.dp)) }
        }
        FortisActionButton(text = "SAVE", isPrimary = true, onClick = {
            if (title.isBlank()) { error = "Title is required."; return@FortisActionButton }
            val oldTotp = old.optJSONObject("totp")
            fun totp(secret: String): Any = if (secret.isBlank()) JSONObject.NULL else
                JSONObject().put("secret_base32", secret.replace(Regex("\\s+"), ""))
                    .put("digits", oldTotp?.optInt("digits", 6) ?: 6).put("period", oldTotp?.optInt("period", 30) ?: 30)
                    .put("algorithm", oldTotp?.optString("algorithm", "Sha1") ?: "Sha1")
            fun opt(k: String): Any = f(k).trim().ifEmpty { null } ?: JSONObject.NULL
            val inner = when (kind) {
                "Login" -> JSONObject().put("username", f("username").trim()).put("password", f("password"))
                    .put("urls", JSONArray(f("urls").lines().map { it.trim() }.filter { it.isNotEmpty() })).put("totp", totp(f("totp")))
                "CreditCard" -> JSONObject().put("cardholder_name", f("cardholder_name").trim()).put("number", f("number").replace(" ", ""))
                    .put("exp_month", f("exp_month").toIntOrNull() ?: 0).put("exp_year", f("exp_year").toIntOrNull() ?: 0).put("cvv", f("cvv").trim())
                "Identity" -> JSONObject().put("first_name", f("first_name").trim()).put("last_name", f("last_name").trim())
                    .put("email", opt("email")).put("phone", opt("phone")).put("address", opt("address"))
                "SecureNote" -> JSONObject().put("content", f("content"))
                else -> {
                    if (f("totp").isBlank()) { error = "The secret is required."; return@FortisActionButton }
                    JSONObject().put("issuer", opt("issuer")).put("account", opt("account")).put("totp", totp(f("totp")))
                }
            }
            val input = JSONObject().put("title", title.trim()).put("folder", folder.trim()).put("notes", notes)
                .put("entry_type", JSONObject().put(kind, inner))
            scope.launch {
                val id = vaultCall({ error = it }) { repo.save(existing?.optString("id"), input) }
                if (id != null) onDone(id)
            }
        })
    }
}

@Composable
private fun GeneratorDialog(onUse: (String) -> Unit, onDismiss: () -> Unit) {
    var length by remember { mutableStateOf(20f) }
    var symbols by remember { mutableStateOf(true) }
    var numbers by remember { mutableStateOf(true) }
    var upper by remember { mutableStateOf(true) }
    val pw = remember(length, symbols, numbers, upper) {
        runCatching { uniffi.fortis_bindings.generatePassword(length.toUInt(), upper, numbers, symbols) }.getOrDefault("")
    }
    var nonce by remember { mutableStateOf(0) }
    val shown = remember(pw, nonce) { if (nonce == 0) pw else runCatching { uniffi.fortis_bindings.generatePassword(length.toUInt(), upper, numbers, symbols) }.getOrDefault(pw) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Generate password", fontFamily = mono()) },
        text = {
            Column {
                Text(shown, fontFamily = mono(), fontSize = 16.sp, modifier = Modifier.padding(bottom = 12.dp))
                Text("Length ${length.toInt()}", fontFamily = mono())
                Slider(length, { length = it }, valueRange = 8f..64f)
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(upper, { upper = it }); Text("A–Z", fontFamily = mono()) }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(numbers, { numbers = it }); Text("0–9", fontFamily = mono()) }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(symbols, { symbols = it }); Text("!@#…", fontFamily = mono()) }
            }
        },
        confirmButton = { TextButton(onClick = { onUse(shown) }) { Text("Use", fontFamily = mono()) } },
        dismissButton = { TextButton(onClick = { nonce++ }) { Text("Again", fontFamily = mono()) } },
    )
}

// ---------------------------------------------------------------- authenticator

@Composable
fun AuthenticatorScreen(viewModel: FortisViewModel, onBack: (() -> Unit)? = null) {
    VaultGate("AUTHENTICATOR", onBack) {
        val context = LocalContext.current
        val repo = remember { VaultRepository.get(context) }
        var entries by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
        var tick by remember { mutableStateOf(0L) }
        LaunchedEffect(Unit) {
            entries = vaultCall({}) { repo.list().first.filter { e -> innerOf(e)?.let { it.has("totp") && !it.isNull("totp") } == true } }.orEmpty()
            while (true) { tick = System.currentTimeMillis() / 1000; delay(1000) }
        }
        Screen {
            Title("AUTHENTICATOR", onBack)
            if (entries.isEmpty()) Text("No 2FA codes yet. Add a 2FA entry or a 2FA secret on a login.", fontFamily = mono(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn {
                items(entries, key = { it.optString("id") }) { e ->
                    val c = remember(tick) { repo.totp(e) }
                    Row(Modifier.fillMaxWidth().clickable {
                        c?.let { SecretClipboard.copy(context, "code", it.first); android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show() }
                    }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(e.optString("title"), fontFamily = mono(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(c?.first?.chunked((c.first.length + 1) / 2)?.joinToString(" ") ?: "—", fontFamily = mono(), fontSize = 26.sp,
                                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                        }
                        Text("${c?.second ?: 0}s", fontFamily = mono(), color = if ((c?.second ?: 30) <= 5) StatusRed else StatusGreen)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}
