package com.thundercrest.thundercrestgame.volt

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.thundercrest.thundercrestgame.volt.pipe.VoltNet
import com.thundercrest.thundercrestgame.volt.pipe.VoltPush
import com.thundercrest.thundercrestgame.volt.pipe.VoltStore
import com.thundercrest.thundercrestgame.volt.ui.VoltQuietScreen
import com.thundercrest.thundercrestgame.volt.ui.VoltTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * No-connection screen.
 *
 * Its own Activity rather than a stage inside the router, for one
 * reason: the router's decision pipeline is a coroutine that must not
 * be left suspended waiting for a user who may never come back. Losing
 * the connection ends that launch cleanly, and coming back starts a new
 * one from the top.
 *
 * Two ways out, and the difference matters. With a [EXTRA_RESUME_URL]
 * the shell already had a page and goes straight back to it. Without
 * one this was a first launch that began offline, so the router has to
 * run its decision from the beginning — that is the first time
 * AppsFlyer is asked anything, and short-circuiting it here would
 * settle the install as organic before the SDK ever spoke.
 */
class VoltQuietActivity : ComponentActivity() {

    private lateinit var link: VoltNet
    private var resumeUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        link = VoltNet(this)
        resumeUrl = intent.getStringExtra(EXTRA_RESUME_URL)

        setContent {
            VoltTheme {
                var busy by remember { mutableStateOf(false) }
                VoltQuietScreen(
                    busy = busy,
                    onRetry = {
                        if (!busy) {
                            busy = true
                            lifecycleScope.launch {
                                if (!attemptResume()) busy = false
                            }
                        }
                    },
                )
            }
        }

        // The user should not have to tap anything when the radio comes
        // back on its own. The probe still runs — an adapter appearing
        // is not the same as the internet being reachable through it.
        lifecycleScope.launch {
            link.statusStream().collect { status ->
                if (status == VoltNet.Status.Online) attemptResume()
            }
        }
    }

    private suspend fun attemptResume(): Boolean {
        if (!link.isReachable()) return false
        // Give Play Services a moment on a radio that just came back
        // so the router's config POST can carry a token.
        withTimeoutOrNull(3_000L) { VoltPush.fetchToken() }
            ?.also { VoltStore(this).writePushToken(it) }
        val saved = resumeUrl
        val next = if (!saved.isNullOrEmpty()) {
            Intent(this, VoltPaneActivity::class.java)
                .putExtra(VoltPaneActivity.EXTRA_TARGET_URL, saved)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        } else {
            Intent(this, VoltHubActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(next)
        finish()
        return true
    }

    companion object {
        const val EXTRA_RESUME_URL = "volt_resume_url"
    }
}
