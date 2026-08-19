package com.thundercrest.thundercrestgame.push

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.thundercrest.thundercrestgame.ThunderApp
import com.thundercrest.thundercrestgame.WebLinkActivity
import com.thundercrest.thundercrestgame.gray.GrayStore
import com.thundercrest.thundercrestgame.gray.ui.PushPromptScreen
import com.thundercrest.thundercrestgame.ui.ThunderCrestTheme
import kotlinx.coroutines.launch

/**
 * Shows the pre-permission "Yes, I want bonuses!" screen before triggering the
 * system push-permission dialog. Only shown in WebView mode, and only if:
 *   * The runtime permission is not already granted, AND
 *   * The user has not permanently declined on the system dialog, AND
 *   * At least [PROMPT_COOLDOWN_MS] have passed since the last prompt.
 *
 * When the gate passes (or the prompt completes), the activity finishes and
 * hands control to [WebLinkActivity] with the URL it was launched with.
 */
class PushPromptActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_ONE_SHOT = "extra_one_shot"
        const val PROMPT_COOLDOWN_MS = 3L * 24 * 60 * 60 * 1000 // 3 days

        /**
         * Convenience: launches the prompt if it should be shown, otherwise
         * jumps straight to the WebView.
         */
        fun open(activity: ComponentActivity, url: String, oneShot: Boolean = false) {
            activity.startActivity(
                Intent(activity, PushPromptActivity::class.java)
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_ONE_SHOT, oneShot)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    private lateinit var store: GrayStore

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveGray()
    }

    private fun enterImmersiveGray() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        lifecycleScope.launch {
            if (!granted) {
                // Denied via the system dialog — never show the custom screen again.
                store.setNotifPermanentlyDenied(true)
            }
            proceed()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Full immersive — no navigation bar, no status bar over the promo artwork.
        // Mirrors LauncherActivity.enterImmersiveGray() — must stay in sync.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enterImmersiveGray()
        store = GrayStore(applicationContext)

        lifecycleScope.launch {
            if (!shouldPrompt()) {
                proceed(); return@launch
            }
            renderPrompt()
        }
    }

    private suspend fun shouldPrompt(): Boolean {
        // Debug knob: always show the promo screen to let QA verify the UI.
        if (ThunderApp.get().ridge.forceShowPushPrompt) return true

        // On API < 33 POST_NOTIFICATIONS doesn't exist as a runtime permission;
        // ContextCompat returns GRANTED by default. Treat those devices as
        // "not yet prompted" — the promo screen is a marketing surface, not
        // just a permission gate, so we show it regardless.
        val needsRuntimePerm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        if (needsRuntimePerm) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) return false
        }

        if (store.notifPermanentlyDenied()) return false

        val last = store.notifLastPromptMs()
        if (last == 0L) return true
        return System.currentTimeMillis() - last >= PROMPT_COOLDOWN_MS
    }

    private fun renderPrompt() {
        setContent {
            ThunderCrestTheme {
                PushPromptScreen(
                    onAccept = { onAccept() },
                    onSkip = { onSkip() },
                )
            }
        }
    }

    private fun onAccept() {
        lifecycleScope.launch {
            store.setNotifLastPromptMs(System.currentTimeMillis())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Runtime permission only exists on API 33+. On older Android,
                // notifications are on by default — skip the system dialog.
                requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                proceed()
            }
        }
    }

    private fun onSkip() {
        lifecycleScope.launch {
            store.setNotifLastPromptMs(System.currentTimeMillis())
            proceed()
        }
    }

    private fun proceed() {
        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) { finish(); return }
        val oneShot = intent.getBooleanExtra(EXTRA_ONE_SHOT, false)
        startActivity(
            Intent(this, WebLinkActivity::class.java)
                .putExtra(WebLinkActivity.EXTRA_URL, url)
                .putExtra(WebLinkActivity.EXTRA_ONE_SHOT, oneShot)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }
}
