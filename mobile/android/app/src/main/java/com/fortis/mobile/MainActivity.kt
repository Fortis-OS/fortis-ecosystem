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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.fortis.mobile.ui.components.FrostBackground
import com.fortis.mobile.ui.theme.Frost
import com.fortis.mobile.ui.theme.FrostSans
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fortis.mobile.ui.FortisViewModel
import com.fortis.mobile.ui.RemoteInputScreen
import com.fortis.mobile.ui.screens.*
import com.fortis.mobile.ui.theme.FortisMobileTheme
import com.fortis.mobile.ui.theme.FrostMono

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

/** Routes for the screens that open on top of the tabs. */
private object Routes {
    const val MAIN = "main"
    const val NOTIFICATION_APPS = "notification-apps"
    const val REMOTE_INPUT = "remote-input"
    const val WEBCAM = "webcam"
    const val VAULT = "vault"
    const val AUTHENTICATOR = "authenticator"
}

@Composable
fun FortisMobileApp(viewModel: FortisViewModel = viewModel()) {
    val nav = rememberNavController()
    val activeDevice by viewModel.activeDevice.collectAsState()

    FrostBackground {
        // keep every screen clear of the status bar and the gesture bar
        NavHost(
            navController = nav,
            startDestination = Routes.MAIN,
            modifier = Modifier.statusBarsPadding().navigationBarsPadding(),
        ) {
            composable(Routes.MAIN) {
                // No laptop chosen yet: the laptop list (pairing, sudo setup)
                if (activeDevice == null) DevicesScreen(viewModel = viewModel)
                else MainTabs(viewModel = viewModel, open = { nav.navigate(it) })
            }
            composable(Routes.NOTIFICATION_APPS) {
                NotificationAppsScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.REMOTE_INPUT) {
                val device = activeDevice
                if (device == null) LaunchedEffect(Unit) { nav.popBackStack() }
                else RemoteInputScreen(device = device, onNavigateBack = { nav.popBackStack() })
            }
            composable(Routes.WEBCAM) {
                val device = activeDevice
                if (device == null) LaunchedEffect(Unit) { nav.popBackStack() }
                else com.fortis.mobile.webcam.WebcamScreen(device = device, onBack = { nav.popBackStack() })
            }
            composable(Routes.VAULT) {
                com.fortis.mobile.encrypt.VaultScreen(viewModel = viewModel, onBack = { nav.popBackStack() })
            }
            composable(Routes.AUTHENTICATOR) {
                com.fortis.mobile.encrypt.AuthenticatorScreen(viewModel = viewModel, onBack = { nav.popBackStack() })
            }
        }
    }
}

@Composable
private fun MainTabs(viewModel: FortisViewModel, open: (String) -> Unit) {
    // survives opening a sub-screen and coming back
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(
        "Dashboard" to Icons.Default.Dashboard,
        "Control" to Icons.Default.SettingsRemote,
        "Files" to Icons.Default.Folder,
        "Monitor" to Icons.Default.MonitorHeart,
        "More" to Icons.Default.MoreHoriz,
    )

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),   // the NavHost already keeps clear of the system bars
        containerColor = Color.Transparent,
        contentColor = Frost.text,
        bottomBar = {
            NavigationBar(
                containerColor = Frost.sheet,
                contentColor = Frost.text,
                tonalElevation = 0.dp,
                windowInsets = WindowInsets(0, 0, 0, 0),
            ) {
                tabs.forEachIndexed { index, (label, icon) ->
                    NavigationBarItem(
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label, fontFamily = FrostSans, fontSize = 12.sp) },
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Frost.accent,
                            selectedTextColor = Frost.accent,
                            unselectedIconColor = Frost.muted,
                            unselectedTextColor = Frost.muted,
                            indicatorColor = Frost.accentSoft,
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
                    onOpenRemoteInput = { open(Routes.REMOTE_INPUT) },
                    onOpenFiles = { selectedTab = 2 },
                    onSwitchDevice = { viewModel.clearActiveDevice() }
                )
                1 -> ControlScreen(
                    viewModel = viewModel,
                    onOpenRemoteInput = { open(Routes.REMOTE_INPUT) },
                    onOpenWebcam = { open(Routes.WEBCAM) }
                )
                2 -> FilesScreen(viewModel = viewModel)
                3 -> MonitorScreen(viewModel = viewModel)
                4 -> MoreScreen(
                    viewModel = viewModel,
                    onOpenNotificationApps = { open(Routes.NOTIFICATION_APPS) },
                    onOpenVault = { open(Routes.VAULT) },
                    onOpenAuthenticator = { open(Routes.AUTHENTICATOR) }
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
