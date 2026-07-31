package com.example.fortismobile.encrypt

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.example.fortismobile.ui.FortisViewModel
import com.example.fortismobile.network.FortisApiClient
import com.example.fortismobile.domain.VaultEntry

@Composable
fun AuthenticatorScreen(viewModel: FortisViewModel) {
    val activeDevice by viewModel.activeDevice.collectAsState()
    
    var entries by remember { mutableStateOf<List<VaultEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }

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
            delay(1000L) // Update every second
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(16.dp)
    ) {
        Text(
            text = "Authenticator",
            color = Color.White,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        if (isLoading) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.CenterHorizontally))
        } else if (error != null) {
            Text(text = "Error: $error", color = Color.Red, modifier = Modifier.align(Alignment.CenterHorizontally))
        } else if (entries.isEmpty()) {
            Text(text = "No TOTP secrets found in Vault.", color = Color.Gray, modifier = Modifier.align(Alignment.CenterHorizontally))
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
            .background(Color(0xFF1E1E1E))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text = title,
                color = Color.Gray,
                fontSize = 14.sp
            )
            Text(
                text = if (totpCode.length == 6) "${totpCode.substring(0,3)} ${totpCode.substring(3)}" else totpCode,
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp
            )
        }
        
        CircularProgressIndicator(
            progress = animatedProgress,
            color = if (remainingSeconds > 5) Color(0xFF4CAF50) else Color(0xFFF44336),
            trackColor = Color.DarkGray,
            modifier = Modifier.size(36.dp),
            strokeWidth = 4.dp
        )
    }
}
