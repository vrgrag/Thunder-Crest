package com.thundercrest.thundercrestgame.volt

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.thundercrest.thundercrestgame.BuildConfig
import com.thundercrest.thundercrestgame.MainActivity
import com.thundercrest.thundercrestgame.volt.mark.VoltId
import com.thundercrest.thundercrestgame.volt.pipe.VoltTrack
import com.thundercrest.thundercrestgame.volt.pipe.VoltPush
import com.thundercrest.thundercrestgame.volt.pipe.VoltAsk
import com.thundercrest.thundercrestgame.volt.pipe.VoltNet
import com.thundercrest.thundercrestgame.volt.pipe.VoltHandoff
import com.thundercrest.thundercrestgame.volt.pipe.VoltStore
import com.thundercrest.thundercrestgame.volt.kind.VoltMode
import com.thundercrest.thundercrestgame.volt.kind.VoltReply
import com.thundercrest.thundercrestgame.volt.ui.VoltSplashScreen
import com.thundercrest.thundercrestgame.volt.ui.VoltTheme
import com.thundercrest.thundercrestgame.volt.guard.VoltUrlGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Entry point and router. Shows the loading screen while it decides
 * whether this install belongs in the WebView shell or the native game.
 *
 * The state machine, by persisted [VoltMode]:
 *
 *  **Pending** (first launch)
 *    No connection → straight to the offline screen on the first frame.
 *    Nothing is started and nothing is written; the offline screen
 *    relaunches this router when the link returns.
 *    Connected → ignite AppsFlyer → attribution → config POST → decide.
 *
 *  **Web** (was the shell last time)
 *    A push URL wins outright. Then a cached link that has not expired.
 *    Otherwise a fresh config POST, falling back to the cached link and
 *    only then to the offline screen. A Web install never drops into
 *    the game because one request failed.
 *
 *  **Native** (was the game last time)
 *    The game, always, with no network work at all — which is also what
 *    makes the white part launch with the radio off. Once native, stay
 *    native, including when a push carries a URL.
 *
 * The one rule worth stating on its own: a native verdict is permanent,
 * so it has to be a real one. It is written only when the endpoint
 * genuinely answered *and* there was attribution behind the answer.
 * Everything else leaves the decision open and simply opens the game
 * for this launch.
 */
class VoltHubActivity : ComponentActivity() {

    private lateinit var vault: VoltStore
    private lateinit var link: VoltNet
    private lateinit var gate: VoltAsk

    private val tracker: VoltTrack
        get() = (application as VoltBoot).tracker

    private var progress by mutableFloatStateOf(0.05f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge without FLAG_LAYOUT_NO_LIMITS — the latter marks
        // the activity as translucent in Android's orientation checker
        // and trips "Only fullscreen opaque activities can request
        // orientation" on API 27+. Orientation is declared in the
        // manifest; no runtime setter is needed.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        vault = VoltStore(this)
        link = VoltNet(this)
        gate = VoltAsk()
        VoltPush.ensureChannel(this)

        val mode = resolveMode()
        val pushUrl = VoltPush.extractUrl(intent)

        // A live shell can take the URL itself — no reason to tear down
        // the page the user is looking at and rebuild it from scratch.
        if (pushUrl != null && mode == VoltMode.Web && VoltHandoff.offer(pushUrl)) {
            info("warm push handed to the live shell")
            finish()
            return
        }

        // Installed from a link with the radio off. Straight to the
        // offline screen: no loading bar for a decision that cannot be
        // made, and nothing written that would have to be undone.
        if (mode == VoltMode.Pending && pushUrl == null && !link.hasAnyAdapter()) {
            info("first run with no link, offline first frame")
            startActivity(Intent(this, VoltQuietActivity::class.java))
            finish()
            return
        }

        if (pushUrl != null && mode != VoltMode.Native) vault.stashPushLink(pushUrl)

        setContent {
            VoltTheme {
                VoltSplashScreen(progress = progress)
            }
        }

        lifecycleScope.launch { route(mode) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val pushUrl = VoltPush.extractUrl(intent) ?: return

        when (vault.readMode()) {
            // A native user keeps their game. The notification was
            // already shown; the URL goes no further.
            VoltMode.Native -> info("push tap while native, game stays")
            VoltMode.Web -> {
                if (VoltHandoff.offer(pushUrl)) {
                    finish()
                } else {
                    openShell(pushUrl)
                }
            }
            VoltMode.Pending -> vault.stashPushLink(pushUrl)
        }
    }

    // ─────────────────────────────────────────────────────────
    // State machine
    // ─────────────────────────────────────────────────────────

    /**
     * The persisted mode, except that a QA build with a non-sticky
     * verdict also forgets a native one it wrote earlier.
     *
     * Without this the switch would be useless on the device that
     * needs it: an organic launch locks the install into the game, and
     * `route` returns to the game before any of the re-ask logic is
     * reached. Clearing it here means clicking the OneLink and
     * relaunching is enough to get a second opinion.
     */
    private fun resolveMode(): VoltMode {
        val stored = vault.readMode()
        if (stored != VoltMode.Native) return stored
        if (BuildConfig.DEBUG && !BuildConfig.STICKY_VERDICT) {
            vault.writeMode(VoltMode.Pending)
            info("native verdict cleared for this QA build")
            return VoltMode.Pending
        }
        return stored
    }

    private suspend fun route(mode: VoltMode) {
        // Debug-only escape hatch. Deliberately checked before the mode,
        // because the situation it exists for is being locked into the
        // game by an organic verdict with no way back short of a
        // reinstall. Empty and unreachable in release builds.
        if (BuildConfig.DEBUG && BuildConfig.PROBE_LINK.isNotEmpty()) {
            info("probe link configured, shell forced without asking anyone")
            progress = 1f
            openShell(BuildConfig.PROBE_LINK)
            return
        }

        if (mode == VoltMode.Native) {
            openGame()
            return
        }
        progress = 0.12f

        if (!awaitConnection(mode)) return

        when (mode) {
            VoltMode.Web -> resolveReturning()
            else -> resolveFirstLaunch()
        }
    }

    /**
     * A launch can easily beat the radio to it, so a missing connection
     * on the first frame is worth waiting out briefly before it counts
     * as being offline. Returning users carry their last page into the
     * offline screen so a tap on Retry goes straight back to it.
     */
    private suspend fun awaitConnection(mode: VoltMode): Boolean {
        if (link.hasAnyAdapter()) return true

        val arrived = withTimeoutOrNull(VoltId.CONNECT_GRACE_MS) {
            link.statusStream().first { it == VoltNet.Status.Online }
        } != null
        if (arrived) return true

        val resume = if (mode == VoltMode.Web && vault.hasUsableLink()) {
            vault.readCachedLink()
        } else {
            null
        }
        startActivity(
            Intent(this, VoltQuietActivity::class.java).apply {
                if (!resume.isNullOrEmpty()) {
                    putExtra(VoltQuietActivity.EXTRA_RESUME_URL, resume)
                }
            },
        )
        finish()
        return false
    }

    private suspend fun resolveFirstLaunch() {
        tracker.ignite(this)
        tracker.retrace(this)
        progress = 0.35f

        val reply = askBackend(firstLaunch = true)
        progress = 0.95f

        if (reply.allowed && reply.hasLink) {
            commitWeb(reply)
            progress = 1f
            openShell(reply.link)
            return
        }

        // A "no" sticks for the lifetime of the install, so it has to
        // be one the backend actually gave, with something behind it.
        when {
            !reply.answered ->
                info("endpoint unreachable, game for now, decision left open")
            !tracker.hasAttributionData() ->
                info("nothing behind the answer, game for now, decision left open")
            !BuildConfig.STICKY_VERDICT ->
                info("backend ruled native; not persisted (QA build)")
            else -> {
                vault.writeMode(VoltMode.Native)
                info("backend ruled native (${reply.note})")
            }
        }
        progress = 1f
        openGame()
    }

    private suspend fun resolveReturning() {
        // A push link is why this launch happened; it outranks
        // everything else, including a perfectly fresh cached page.
        val pushed = VoltUrlGate.sanitize(vault.takePushLink())
        if (pushed != null) {
            openShell(pushed)
            return
        }

        // The backend is asked on every return rather than only when
        // the cache has expired. A campaign destination can move at any
        // time, and a link the server sent without an expiry would
        // otherwise be pinned for the life of the install. The cache is
        // the fallback for when the ask does not land, not a way to
        // skip it — which is why the attribution budget here is the
        // short one.
        tracker.ignite(this)
        tracker.retrace(this)
        progress = 0.45f

        val reply = askBackend(firstLaunch = false)
        progress = 0.95f

        val cached = vault.readCachedLink()
        when {
            reply.allowed && reply.hasLink -> {
                commitWeb(reply)
                progress = 1f
                openShell(reply.link)
            }
            // Even a stale page the user can read beats an offline
            // screen they cannot. A Web install never drops into the
            // game because one request came back empty.
            !cached.isNullOrEmpty() -> {
                progress = 1f
                openShell(cached)
            }
            else -> {
                startActivity(Intent(this, VoltQuietActivity::class.java))
                finish()
            }
        }
    }

    private suspend fun askBackend(firstLaunch: Boolean): VoltReply {
        val body = withContext(Dispatchers.IO) {
            tracker.collectBody(firstLaunch).also { gate.decorate(it, this@VoltHubActivity, tracker) }
        }
        progress = if (firstLaunch) 0.7f else 0.75f
        return gate.query(body)
    }

    private fun commitWeb(reply: VoltReply) {
        vault.writeMode(VoltMode.Web)
        reply.link?.let(vault::writeCachedLink)
        vault.writeLinkTtl(reply.ttl ?: 0L)
    }

    // ─────────────────────────────────────────────────────────
    // Exits
    // ─────────────────────────────────────────────────────────

    private fun openShell(url: String?) {
        val target = VoltUrlGate.sanitize(url)
        if (target == null) {
            openGame()
            return
        }
        val next = if (vault.shouldOfferInvite(this)) {
            Intent(this, VoltPermitActivity::class.java)
                .putExtra(VoltPermitActivity.EXTRA_TARGET_URL, target)
        } else {
            Intent(this, VoltPaneActivity::class.java)
                .putExtra(VoltPaneActivity.EXTRA_TARGET_URL, target)
        }
        startActivity(next.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    private fun openGame() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }

    private fun info(message: String) {
        if (BuildConfig.DEBUG) Log.i(TAG, message)
    }

    private companion object {
        const val TAG = "VoltHub"
    }
}
