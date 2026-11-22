package com.fortis.mobile.backup

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.fortis.mobile.data.PhoneStatus
import com.fortis.mobile.data.StartResult
import com.fortis.mobile.ui.toast

/**
 * Opened by the "Backup waiting" notification. Has no UI: it starts Termux backup mode
 * (`fortis-mobile start`) the same way as the Dashboard button, from the foreground, then closes.
 */
class BackupModeActivity : ComponentActivity() {
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else {
            toast("Fortis needs Termux's \"Run command\" permission to start backup mode.", long = true)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) start()
    }

    private fun start() {
        when (val r = PhoneStatus.startTermuxDaemon(this)) {
            StartResult.NeedsPermission -> { permission.launch(PhoneStatus.TERMUX_PERMISSION); return }
            StartResult.Started -> {
                BackupRequests.dismiss(this)
                toast("Backup mode started. The laptop will push the backup.")
            }
            is StartResult.Failed -> toast(r.reason, long = true)
        }
        finish()
    }
}
