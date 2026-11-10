package com.fortis.mobile

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import android.os.Handler
import android.os.Looper
import com.fortis.mobile.vault.VaultSession
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fortis.mobile.ui.FortisViewModel
import com.fortis.mobile.ui.RemoteInputScreen
import com.fortis.mobile.ui.screens.*
import com.fortis.mobile.ui.theme.FortisMobileTheme

class MainActivity : FragmentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val lockVault = Runnable { VaultSession.lock() }
    private val notificationPermission =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) com.fortis.mobile.service.EventsService.start(this)
        }

    // Lock the phone vault 60 s after the app leaves the screen (docs/security §6.3).
    override fun onStop() {
        super.onStop()
        handler.postDelayed(lockVault, VaultSession.BACKGROUND_MS)
    }

    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(lockVault)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.fortis.mobile.service.EventsService.start(this)
        // Sudo prompts are notifications (full-screen intent); without this
        // permission Android drops them silently (Android 13+).
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            FortisMobileTheme {
                FortisMobileApp()
            }
        }
    }
}

@Composable
fun FortisMobileApp(viewModel: FortisViewModel = viewModel()) {
    val activeDevice by viewModel.activeDevice.collectAsState()

    // If no active device, show Device Selection
    if (activeDevice == null) {
        DevicesScreen(viewModel = viewModel)
        return
    }

    // Full-screen overlay states
    var showNotifApps by remember { mutableStateOf(false) }
    var showRemoteInput by remember { mutableStateOf(false) }
    var showWebcam by remember { mutableStateOf(false) }
    var showVault by remember { mutableStateOf(false) }
    var showAuthenticator by remember { mutableStateOf(false) }

    if (showNotifApps) {
        NotificationAppsScreen(onBack = { showNotifApps = false })
        return
    }

    if (showRemoteInput && activeDevice != null) {
        RemoteInputScreen(device = activeDevice!!, onNavigateBack = { showRemoteInput = false })
        return
    }

    if (showWebcam && activeDevice != null) {
        com.fortis.mobile.webcam.WebcamScreen(device = activeDevice!!, onBack = { showWebcam = false })
        return
    }

    if (showVault) {
        com.fortis.mobile.encrypt.VaultScreen(viewModel = viewModel, onBack = { showVault = false })
        return
    }

    if (showAuthenticator) {
        com.fortis.mobile.encrypt.AuthenticatorScreen(viewModel = viewModel, onBack = { showAuthenticator = false })
        return
    }

    // Bottom navigation state
    var selectedTab by remember { mutableStateOf(0) }

    val tabs = listOf(
        Triple("Dashboard", Icons.Default.Dashboard, "Dashboard"),
        Triple("Control", Icons.Default.SettingsRemote, "Control"),
        Triple("Files", Icons.Default.Folder, "Files"),
        Triple("Monitor", Icons.Default.MonitorHeart, "Monitor"),
        Triple("More", Icons.Default.MoreHoriz, "More")
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                tabs.forEachIndexed { index, (label, icon, _) ->
                    NavigationBarItem(
                        icon = { Icon(icon, contentDescription = label) },
                        label = {
                            Text(
                                label.uppercase(),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            indicatorColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            when (selectedTab) {
                0 -> DashboardScreen(
                    viewModel = viewModel,
                    onOpenRemoteInput = { showRemoteInput = true },
                    onOpenFiles = { selectedTab = 2 },
                    onSwitchDevice = { viewModel.clearActiveDevice() }
                )
                1 -> ControlScreen(
                    viewModel = viewModel,
                    onOpenRemoteInput = { showRemoteInput = true },
                    onOpenWebcam = { showWebcam = true }
                )
                2 -> FilesScreen(viewModel = viewModel)
                3 -> MonitorScreen(viewModel = viewModel)
                4 -> MoreScreen(
                    viewModel = viewModel,
                    onOpenNotificationApps = { showNotifApps = true },
                    onOpenVault = { showVault = true },
                    onOpenAuthenticator = { showAuthenticator = true }
                )
            }
        }
    }
}

fun ensureTailscaleActive(context: Context): Boolean {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    var vpnActive = false
    val activeNetwork = connectivityManager.activeNetwork
    if (activeNetwork != null) {
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        if (capabilities != null && capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) {
            vpnActive = true
        }
    }
    if (!vpnActive) {
        val tsIntent = context.packageManager.getLaunchIntentForPackage("com.tailscale.ipn")
        if (tsIntent != null) {
            context.startActivity(tsIntent)
            Toast.makeText(context, "Please turn on Tailscale!", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(context, "Tailscale app not found", Toast.LENGTH_SHORT).show()
        }
        return false
    }
    return true
}

fun formatMicroseconds(micros: Float): String {
    val totalSeconds = (micros / 1000000).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
}
