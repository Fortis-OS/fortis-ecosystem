package com.example.fortismobile.encrypt

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.example.fortismobile.ui.FortisViewModel
import com.example.fortismobile.network.FortisApiClient
import com.example.fortismobile.domain.VaultEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(viewModel: FortisViewModel) {
    var searchQuery by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<VaultEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    
    val activeDevice by viewModel.activeDevice.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(activeDevice) {
        if (activeDevice != null) {
            isLoading = true
            error = null
            try {
                entries = FortisApiClient.fetchVaultEntries()
            } catch (e: Exception) {
                error = e.message
            } finally {
                isLoading = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(16.dp)
    ) {
        Text(
            text = "Personal Vault",
            color = Color.White,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search your vault...", color = Color.Gray) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White) },
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.White,
                unfocusedBorderColor = Color.DarkGray,
                focusedContainerColor = Color(0xFF1E1E1E),
                unfocusedContainerColor = Color(0xFF1E1E1E),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            )
        )

        if (isLoading) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.CenterHorizontally))
        } else if (error != null) {
            Text(text = "Error: $error", color = Color.Red, modifier = Modifier.align(Alignment.CenterHorizontally))
        } else {
            val filtered = entries.filter { it.title.contains(searchQuery, ignoreCase = true) }
            if (filtered.isEmpty()) {
                Text(text = "No entries found.", color = Color.Gray, modifier = Modifier.align(Alignment.CenterHorizontally))
            } else {
                LazyColumn {
                    items(filtered) { item ->
                        VaultItemCard(item)
                    }
                }
            }
        }
    }
}

@Composable
fun VaultItemCard(item: VaultEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1E1E1E))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = "Lock",
            tint = Color.White,
            modifier = Modifier.size(32.dp).padding(end = 12.dp)
        )
        Column {
            Text(
                text = item.title,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )
            val subText = when (item.entryType) {
                "Login" -> item.username ?: "No username"
                "SecureNote" -> "Secure Note"
                else -> item.entryType
            }
            Text(
                text = subText,
                color = Color.Gray,
                fontSize = 14.sp
            )
        }
    }
}
