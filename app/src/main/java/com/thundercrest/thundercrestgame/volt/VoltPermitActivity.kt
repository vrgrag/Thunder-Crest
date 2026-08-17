package com.thundercrest.thundercrestgame.volt

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import com.thundercrest.thundercrestgame.volt.pipe.VoltStore
import com.thundercrest.thundercrestgame.volt.ui.VoltPermitScreen
import com.thundercrest.thundercrestgame.volt.ui.VoltTheme

/**
 * Push-permission opt-in, shown once between the router and the shell.
 *
 * Standing on its own rather than living inside the router removes the
 * polling loop the previous version needed: the router used to suspend
 * on a flag while a Compose screen somewhere else waited for a tap.
 * Here the Activity result *is* the continuation.
 *
 * Either button leads to the same place. This screen is a courtesy ask
 * before the OS dialog, never a gate on the content — a user who taps
 * Skip gets the shell just as fast as one who accepts.
 */
class VoltPermitActivity : ComponentActivity() {

    private lateinit var vault: VoltStore
    private var targetUrl: String? = null

    private val askOs = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // A refusal in the system dialog is final. The OS would hand
        // out another attempt, but the user has already answered the
        // one question this screen exists to ask, and coming back in
        // three days only asks it again.
        if (granted) vault.markPushAllowed(true) else vault.markPushBlockedByOs()
        proceed()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        vault = VoltStore(this)
        targetUrl = intent.getStringExtra(EXTRA_TARGET_URL)

        setContent {
            VoltTheme {
                VoltPermitScreen(
                    onAccept = ::onAccept,
                    onSkip = {
                        vault.armInviteCooldown()
                        proceed()
                    },
                )
            }
        }
    }

    private fun onAccept() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            vault.markPushAllowed(true)
            proceed()
            return
        }
        if (vault.osGranted()) {
            vault.markPushAllowed(true)
            proceed()
            return
        }
        vault.markOsAsked()
        askOs.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Straight into the shell, deliberately not back through the
     * router: the loading screen has had its one showing for this
     * launch, and bringing it back after the permission dialog reads
     * as the app restarting.
     */
    private fun proceed() {
        startActivity(
            Intent(this, VoltPaneActivity::class.java)
                .putExtra(VoltPaneActivity.EXTRA_TARGET_URL, targetUrl)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
    }

    companion object {
        const val EXTRA_TARGET_URL = "volt_permit_target"
    }
}
