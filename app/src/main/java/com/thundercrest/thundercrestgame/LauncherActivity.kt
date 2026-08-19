package com.thundercrest.thundercrestgame

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.appsflyer.AppsFlyerLib
import com.thundercrest.thundercrestgame.gray.AppMode
import com.thundercrest.thundercrestgame.gray.ConfigApi
import com.thundercrest.thundercrestgame.gray.ConfigBody
import com.thundercrest.thundercrestgame.gray.ConfigResponse
import com.thundercrest.thundercrestgame.gray.Connectivity
import com.thundercrest.thundercrestgame.gray.GrayStore
import com.thundercrest.thundercrestgame.gray.ui.LoadingScreen
import com.thundercrest.thundercrestgame.gray.ui.NoWifiScreen
import com.thundercrest.thundercrestgame.push.PushPromptActivity
import com.thundercrest.thundercrestgame.push.ThunderMessagingService
import com.thundercrest.thundercrestgame.ui.ThunderCrestTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sole entry point. Decides which shell to open, in this priority order:
 *
 *  1. **Push URL in the launch intent** (`EXTRA_PUSH_URL`) — the highest
 *     priority: the user just tapped a push notification. Route straight
 *     to the WebView with `oneShot=true` so this URL never overwrites the
 *     cached config link. If we are offline, keep the URL in the store as
 *     "pending" so Retry from the No-Wi-Fi screen still opens it.
 *  2. **`ridge.probeLink`** (debug builds only) — forces WebView mode with
 *     the QA probe URL, bypassing the config request entirely.
 *  3. **Persisted mode**:
 *      * `GAME` → open the native game immediately; no config request.
 *      * `WEBVIEW` → open the cached URL (refresh in the background if
 *        expired; fall back to cache on backend error).
 *      * `UNSET` → first launch: request the config, save the outcome,
 *        route accordingly.
 *  4. **Offline** at any of the above steps that require the network →
 *     the No-Wi-Fi stub with Retry.
 */
class LauncherActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PUSH_URL = "extra_push_url"
        private const val TAG = "Launcher"

        // All three run in PARALLEL — the total wait is max(AF, UDL, TOKEN)
        // not their sum. The AF window is large enough to swallow the
        // Organic → GCD recheck path (5 s delay + ~7 s GCD HTTP call, see
        // ThunderApp.dispatchAttribution). Without ≥ 13 s the recheck
        // times out and the "OneLink install → offline → retry" flow
        // routes to the game instead of the WebView.
        private val CONVERSION_TIMEOUT_MS = if (BuildConfig.DEBUG) 14_000L else 16_000L
        private val UDL_WAIT_MS           = if (BuildConfig.DEBUG) 3_000L  else  5_000L
        private val TOKEN_TIMEOUT_MS      = if (BuildConfig.DEBUG) 3_000L  else  5_000L

        // ── Splash-bar milestones (0..1). See LoadingScreen KDoc. ──────────
        private const val CREST_SPARK  = 0.01f   // bar starts at 1 %
        private const val CREST_LATTICE = 0.18f  // connectivity ok
        private const val CREST_ORACLE  = 0.48f  // AF SDK fired
        private const val CREST_SEAL    = 0.82f  // config received
    }

    private lateinit var store: GrayStore
    private lateinit var api: ConfigApi
    private var flowJob: Job? = null

    // Pending navigation action — set by the flow, executed by the
    // LoadingScreen's onReady callback after the bar animation finishes.
    // Both writes (coroutine / main thread) and reads (Compose) happen on
    // the main thread so no synchronisation is required.
    private var pendingNav: (() -> Unit)? = null

    // Splash-bar progress driven by real pipeline milestones (0..1).
    // See LoadingScreen KDoc for the checkpoint map.
    private var splashProgress by mutableFloatStateOf(CREST_SPARK)

    // Background ticker — slowly advances splashProgress toward a ceiling
    // while we wait for an async operation, so the bar never stalls at a
    // checkpoint. Cancelled / restarted by advanceTo().
    private var tickerJob: Job? = null

    // True while the No-Wi-Fi screen should be shown instead of the splash.
    private var showOfflineScreen by mutableStateOf(false)

    // Human-readable stage label shown under the progress bar.
    private var stageLabel by mutableStateOf<String?>(null)

    // Silent POST_NOTIFICATIONS fallback — kicks the system dialog once per
    // process when the PushPromptActivity path never fired (push-driven cold
    // start, or user tapped Skip on the first-launch prompt). Without this,
    // Android 13+ users who never see the promo get their FCM banners
    // silently dropped for the lifetime of the install.
    private val postNotifPermRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.i(TAG, "POST_NOTIFICATIONS fallback grant=$granted")
    }
    private var notifPermFallbackFired = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Gray loading/no-wifi screens must be fully immersive — no status bar
        // and no navigation bar visible. Mirrors WebLinkActivity.enterImmersive.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enterImmersiveGray()
        allowDrawUnderCutout()

        store = GrayStore(applicationContext)
        api = ConfigApi()

        if (BuildConfig.DEBUG) dumpIntentDebug(intent, "onCreate")

        // ── TowerBuilder pattern: pending push URL wins over EVERYTHING.
        // Read the stash + intent extras BEFORE we even render the splash so
        // a notification tap hands off to WebLink instantly, with no
        // LoadingScreen flash and no chance of the config gate blocking.
        //
        // This is the equivalent of HoistRouter._drive():
        //   final String? pending = await widget.box.takePendingLink();
        //   if (pending != null) { _toWeb(pending); return; }
        val instantPushUrl = resolveInstantPushUrl(intent)
        if (!instantPushUrl.isNullOrBlank()) {
            Log.i(TAG, "onCreate: instant push URL → WebLink ($instantPushUrl)")
            ThunderApp.get().clearColdPushUrl()
            intent.removeExtra(EXTRA_PUSH_URL)
            for (k in ThunderMessagingService.URL_KEYS) intent.removeExtra(k)
            commitWebviewModeIfUnsetAndOpen(instantPushUrl)
            return
        }

        setContent {
            ThunderCrestTheme {
                if (showOfflineScreen) {
                    NoWifiScreen(onRetry = {
                        showOfflineScreen = false
                        splashProgress = CREST_SPARK
                        stageLabel = null
                        pendingNav = null
                        startFlow()
                    })
                } else {
                    LoadingScreen(
                        progress = splashProgress,
                        stageLabel = stageLabel,
                        onReady = { pendingNav?.invoke() },
                    )
                }

                LaunchedEffect(Unit) {
                    // Request POST_NOTIFICATIONS here — ONLY in the normal
                    // splash flow, not on the push fast-path (where we call
                    // finish() almost immediately and firing a permission
                    // dialog on a dying activity causes undefined behaviour).
                    maybeRequestPostNotificationsPermission()
                    startFlow()
                }
            }
        }
    }

    /**
     * Synchronously resolves the push URL that must be honoured before the
     * router even shows the splash. Order matches TowerBuilder `_drive()`:
     *   1. The launch/new intent's extras (our own [EXTRA_PUSH_URL] + any
     *      raw FCM data keys that survived the tap).
     *   2. The OEM-safe SharedPreferences stash written by
     *      [ThunderMessagingService.dispatchPushUrl] via `.commit()`.
     * Returns null when neither source has a URL.
     */
    private fun resolveInstantPushUrl(source: Intent?): String? {
        val fromIntent = extractPushUrlFromIntent(source)
        if (!fromIntent.isNullOrBlank()) return fromIntent
        return ThunderApp.get().consumeColdPushUrl()
    }

    /**
     * Fire-and-forget commit of AppMode.WEBVIEW for pushes that land on a
     * fresh install (`store.mode() == UNSET`). We do NOT block the hand-off
     * on the DataStore write: worst case a process kill before the write
     * lands means the next launch might briefly evaluate the config gate
     * before the user gets the WebView — an acceptable trade-off for the
     * "notification tap is instant" invariant. Launched on the main
     * dispatcher via [lifecycleScope] so it stays sequenced with the
     * `openWebViewNow` call directly below it.
     */
    private fun commitWebviewModeIfUnsetAndOpen(url: String) {
        lifecycleScope.launch {
            if (store.mode() == AppMode.UNSET) {
                store.setMode(AppMode.WEBVIEW)
                store.saveLink(url, 0L)
                Log.i(TAG, "instant push on UNSET → committed WEBVIEW channel")
            }
            openWebViewNow(url, oneShot = true, skipPromptGate = true)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveGray()
    }

    /**
     * Silent POST_NOTIFICATIONS request for Android 13+ users who never
     * flowed through PushPromptActivity. Called at most once per process
     * (guarded by [notifPermFallbackFired]) and only when the permission is
     * actually missing — skipped when already granted or when the OS
     * doesn't require it (API < 33). On denial the OS remembers and won't
     * show the dialog again (subject to Android's "don't ask twice"
     * policy) — that's fine, we just log it in [postNotification].
     */
    private fun maybeRequestPostNotificationsPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (notifPermFallbackFired) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            Log.i(TAG, "POST_NOTIFICATIONS already granted")
            return
        }
        notifPermFallbackFired = true
        Log.i(TAG, "POST_NOTIFICATIONS not granted → firing runtime request")
        runCatching { postNotifPermRequest.launch(Manifest.permission.POST_NOTIFICATIONS) }
            .onFailure { Log.w(TAG, "postNotifPermRequest.launch failed", it) }
    }

    /** Hides ALL system bars for the gray loading / no-wifi screens. */
    private fun enterImmersiveGray() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (BuildConfig.DEBUG) dumpIntentDebug(intent, "onNewIntent")
        setIntent(intent)
        flowJob?.cancel()

        // Same instant-push fast path as onCreate — warm tap on a push
        // notification hands off to WebLink without ever re-rendering the
        // splash. If a WebLink is already alive further down the stack,
        // openWebViewNow's CLEAR_TOP|SINGLE_TOP flags will deliver
        // onNewIntent(url) to it rather than spawn a fresh WebView.
        val instantPushUrl = resolveInstantPushUrl(intent)
        if (!instantPushUrl.isNullOrBlank()) {
            Log.i(TAG, "onNewIntent: instant push URL → WebLink ($instantPushUrl)")
            ThunderApp.get().clearColdPushUrl()
            intent.removeExtra(EXTRA_PUSH_URL)
            for (k in ThunderMessagingService.URL_KEYS) intent.removeExtra(k)
            commitWebviewModeIfUnsetAndOpen(instantPushUrl)
            return
        }

        showOfflineScreen = false
        splashProgress = CREST_SPARK
        stageLabel = null
        pendingNav = null
        startFlow()
    }

    // ---- Flow ---------------------------------------------------------------

    /**
     * Runs the routing pipeline. Navigation never fires inline — instead the
     * function stores a [pendingNav] lambda and advances [splashProgress] to
     * 1.0. The [LoadingScreen]'s `onReady` callback fires ~360 ms later (after
     * the bar animation lands) and calls [pendingNav].
     *
     * Offline bail-outs flip [showOfflineScreen] directly and return early.
     */
    private fun startFlow() {
        flowJob?.cancel()
        flowJob = lifecycleScope.launch {
            // Start from 1 % and slowly animate toward the connectivity gate
            // while we process push URLs / DataStore reads.
            advanceTo(CREST_SPARK, CREST_LATTICE - 0.03f, durationMs = 800L)

            // (1) Push URL from a notification tap — highest priority.
            //
            // IMPORTANT: we do NOT gate on connectivity here. The user tapped a
            // notification deliberately — they expect to land on that URL, not on
            // a "no-wifi" stub. If the device is offline, WebLinkActivity's own
            // connectivity watcher fires and shows the inline offline screen with
            // a Retry button; the URL is preserved so Retry resumes the exact page.
            // Gating connectivity here caused the "tap notification → no-wifi screen
            // instead of the expected page" symptom on any network hiccup during app
            // launch (VPN hand-off, hotel WiFi association, MIUI dual-SIM switch).
            val pushUrl = extractPushUrlFromIntent(intent)
            if (!pushUrl.isNullOrBlank()) {
                Log.i(TAG, "Push URL detected → opening WebView (fast path, no splash)")
                intent.removeExtra(EXTRA_PUSH_URL)
                for (k in ThunderMessagingService.URL_KEYS) intent.removeExtra(k)
                // Wipe the OEM stash so the same URL doesn't replay on the next
                // normal launch. The intent extras already carry the URL, so the
                // stash is redundant here — leaving it would trigger the "force-close
                // → open normally → sees old URL" symptom (Flutter Problem 2 fix).
                ThunderApp.get().clearColdPushUrl()
                if (store.mode() == AppMode.UNSET) {
                    Log.i(TAG, "Push URL on UNSET → committing WEBVIEW channel")
                    store.setMode(AppMode.WEBVIEW)
                    store.saveLink(pushUrl, 0L)
                }
                // Fast path — skip the splash progress-bar animation entirely.
                // Stack-Rush Dispatcher.routePage: when a push URL is present,
                // it goes directly to Portal without any loading UI. The URL
                // is known immediately (no config request needed), so any
                // splash flash between tap and WebView is pure delay.
                openWebViewNow(pushUrl, oneShot = true, skipPromptGate = true)
                return@launch
            }

            // (1b) SharedPreferences stash — written by ThunderMessagingService when
            // OEM launchers replace our PendingIntent and drop the URL from extras.
            val coldStash = ThunderApp.get().consumeColdPushUrl()
            if (!coldStash.isNullOrBlank()) {
                Log.i(TAG, "Cold-push stash URL → WebView (fast path)")
                if (store.mode() == AppMode.UNSET) {
                    store.setMode(AppMode.WEBVIEW); store.saveLink(coldStash, 0L)
                }
                openWebViewNow(coldStash, oneShot = true, skipPromptGate = true)
                return@launch
            }

            // (1c) DataStore stash — tap while offline, the URL was queued.
            val stashed = store.consumePendingPushUrl()
            if (!stashed.isNullOrBlank()) {
                Log.i(TAG, "DataStore stash URL → WebView (fast path)")
                openWebViewNow(stashed, oneShot = true, skipPromptGate = true)
                return@launch
            }

            // Debug knob: stickyVerdict=false makes every launch look like
            // first-install so QA can retrigger the full flow without reinstall.
            val effectiveMode = if (!ThunderApp.get().ridge.stickyVerdict) {
                Log.i(TAG, "ridge.stickyVerdict=false → forcing UNSET")
                store.setMode(AppMode.UNSET)
                store.clearSavedLink()
                AppMode.UNSET
            } else {
                store.mode()
            }

            when (effectiveMode) {
                AppMode.GAME    -> scheduleGame()
                AppMode.WEBVIEW -> resumeWebViewMode()
                AppMode.UNSET   -> firstLaunch()
            }
        }
    }

    private suspend fun firstLaunch() {
        val app = ThunderApp.get()

        // Debug: force WebView with a QA probe link, skip full pipeline.
        app.ridge.probeLink?.let {
            store.setMode(AppMode.WEBVIEW)
            store.saveLink(it, Long.MAX_VALUE / 1000)
            scheduleWebView(it)
            return
        }

        // Connectivity gate.
        advanceTo(CREST_LATTICE, CREST_ORACLE - 0.04f)
        stageLabel = null
        if (!Connectivity.isOnline(this)) { stopTicker(); showOfflineScreen = true; return }

        // AppsFlyer attribution — can take up to CONVERSION_TIMEOUT_MS
        // (14 s debug / 16 s release, sized to swallow the Organic → GCD
        // recheck path in ThunderApp.dispatchAttribution). Ticker slowly
        // fills toward VERDICT while we wait.
        advanceTo(CREST_ORACLE, CREST_SEAL - 0.04f, durationMs = 12000L)
        app.activateAttribution(this)

        val (result, afStatus) = requestConfigWithDiag() ?: run {
            stopTicker(); showOfflineScreen = true; return
        }

        stopTicker()
        splashProgress = maxOf(splashProgress, CREST_SEAL)
        stageLabel = null

        // Client-side attribution gate — "not explicitly organic".
        //
        // We used to demand an explicit af_status=Non-organic here, but that
        // broke the "OneLink install → offline → retry when online" flow:
        // AppsFlyer SDK boots for the first time on the retry pass and
        // frequently misses the 10 s conversion-data timeout on a just-woken
        // network. The result was af_status=null → treated as "not
        // non-organic" → routed to the white game even though the backend
        // returned ok=true with a valid URL from a genuine OneLink.
        //
        // New rule: trust the backend's positive verdict UNLESS the SDK
        // explicitly said the install is Organic. Null / timeout / empty
        // conversion data no longer overrides the backend. Genuinely organic
        // installs are still guarded by (a) the backend returning ok=false
        // and (b) the resumeWebViewMode() "organic reinstall" check below.
        val explicitlyOrganic = afStatus?.equals(com.thundercrest.thundercrestgame.gray.Ember.organicLabel, ignoreCase = true) == true

        if (BuildConfig.DEBUG) {
            val goGray = result.ok && !result.url.isNullOrBlank() && !explicitlyOrganic
            val verdict = if (goGray) "GRAY → ${result.url?.take(80)}"
                          else "WHITE — st=$afStatus expl=$explicitlyOrganic ok=${result.ok} msg=${result.message}"
            Log.i(TAG, "firstLaunch verdict: $verdict")
        }

        if (result.ok && !result.url.isNullOrBlank() && !explicitlyOrganic) {
            store.setMode(AppMode.WEBVIEW)
            store.saveLink(result.url, result.expires ?: 0L)
            scheduleWebView(result.url)
        } else {
            store.setMode(AppMode.GAME)
            scheduleGame()
        }
    }

    private suspend fun resumeWebViewMode() {
        val app = ThunderApp.get()
        val saved = store.savedLink()
        advanceTo(CREST_LATTICE, CREST_ORACLE - 0.04f, durationMs = 1200L)
        val online = Connectivity.isOnline(this)

        if (saved == null) {
            if (!online) { stopTicker(); showOfflineScreen = true; return }
            firstLaunch(); return
        }
        if (!online) { stopTicker(); showOfflineScreen = true; return }

        // Start the AF SDK — needed both for the organic-reinstall check
        // below and for the silent config refresh on expired links.
        app.activateAttribution(this)

        // ── Organic-reinstall gate ──────────────────────────────────────────
        // Wait briefly for the AppsFlyer SDK to deliver the current
        // attribution. If the result is explicitly "Organic", the user
        // reinstalled without a OneLink (or the lookback window expired).
        // In that case we discard the cached WEBVIEW verdict and route to
        // the native game — just like a genuine first organic launch would.
        //
        // Why here and not just firstLaunch(): when the developer tests via
        // Android Studio / `adb install`, app data is NOT wiped between
        // runs. A previous OneLink session leaves mode=WEBVIEW in DataStore,
        // so startFlow() calls resumeWebViewMode() instead of firstLaunch()
        // and we'd skip the attribution check entirely without this gate.
        val convMap = withTimeoutOrNull(CONVERSION_TIMEOUT_MS) {
            app.conversionDataDeferred.await()
        } ?: emptyMap()

        val statusKey = com.thundercrest.thundercrestgame.gray.Ember.afStatusKey
        val organicMark = com.thundercrest.thundercrestgame.gray.Ember.organicLabel
        val localAfStatus = convMap[statusKey]?.toString()
        if (localAfStatus?.equals(organicMark, ignoreCase = true) == true) {
            Log.i(TAG, "resumeWebViewMode: organic → clearing WEBVIEW mode → GAME")
            store.setMode(AppMode.GAME)
            store.clearSavedLink()
            stopTicker()
            splashProgress = maxOf(splashProgress, CREST_SEAL)
            scheduleGame()
            return
        }

        if (BuildConfig.DEBUG) {
            Log.i(TAG, "resumeWebViewMode: st=$localAfStatus → staying in WEBVIEW path")
        }

        if (saved.isExpired()) {
            advanceTo(CREST_ORACLE, CREST_SEAL - 0.04f, durationMs = 5000L)
            val result = requestConfig()
            stopTicker()
            splashProgress = maxOf(splashProgress, CREST_SEAL)
            stageLabel = null
            if (result?.ok == true && !result.url.isNullOrBlank()) {
                store.saveLink(result.url, result.expires ?: 0L)
                scheduleWebView(result.url)
            } else {
                scheduleWebView(saved.url)
            }
        } else {
            // Cached link is fresh — navigate immediately, refresh silently.
            scheduleWebView(saved.url)
            lifecycleScope.launch {
                val fresh = requestConfig()
                if (fresh?.ok == true && !fresh.url.isNullOrBlank()) {
                    store.saveLink(fresh.url, fresh.expires ?: 0L)
                }
            }
        }
    }

    /** Returns Pair(response, af_status sent) or null when offline. */
    private suspend fun requestConfigWithDiag(): Pair<ConfigResponse, String?>? {
        val app = ThunderApp.get()

        // The `forceAfStatus` debug knob is honoured by ConfigBody (it overwrites
        // af_status right before the POST). We DO NOT use it as a fast-path
        // signal that lets us skip the real AppsFlyer wait — the vendor's
        // conversion payload carries dozens of downstream parameters
        // (`campaign`, `campaign_id`, `agency`, `af_siteid`, `af_sub1..5`,
        // `adgroup`, `adgroup_id`, etc.) that the backend maps to sub_id_2..7
        // and extra_param_2..6. Skipping the wait shipped an almost-empty
        // body and produced the "0 parameters" symptom the user reported —
        // only bundle_id, os, af_id, media_source=debug_override arrived,
        // every other slot on the backend was blank.
        val forcedAfStatus = if (BuildConfig.DEBUG) app.ridge.forceAfStatus else null

        // Fan-out: all three SDK callbacks are awaited simultaneously so the
        // total wait equals the SLOWEST one, not their sum.
        val (convMap, udlMap, token) = coroutineScope {
            val cDef = async {
                withTimeoutOrNull(CONVERSION_TIMEOUT_MS) {
                    app.conversionDataDeferred.await()
                } ?: emptyMap()
            }
            val uDef = async { withTimeoutOrNull(UDL_WAIT_MS)      { app.udlDataDeferred.await()   } ?: emptyMap() }
            val tDef = async { withTimeoutOrNull(TOKEN_TIMEOUT_MS)  { app.pushTokenDeferred.await() } ?: app.latestPushToken }
            Triple(cDef.await() ?: emptyMap<String, Any?>(), uDef.await() ?: emptyMap(), tDef.await())
        }

        val statusKey2 = com.thundercrest.thundercrestgame.gray.Ember.afStatusKey
        val afStatus = forcedAfStatus
            ?: convMap[statusKey2]?.toString()
            ?: udlMap[statusKey2]?.toString()
        Log.i(TAG, "config request: convKeys=${convMap.keys.size} " +
            "udlKeys=${udlMap.keys.size} status=$afStatus token=${!token.isNullOrEmpty()} " +
            "(forceAfStatus=$forcedAfStatus)")

        val afUid = AppsFlyerLib.getInstance().getAppsFlyerUID(this)
        val body = ConfigBody.build(
            ctx = this,
            conversionData = convMap,
            udlData = udlMap,
            appsFlyerUid = afUid,
            pushToken = token,
            firebaseProjectId = BuildConfig.FIREBASE_PROJECT_NUMBER,
            overrides = app.ridge,
        )
        if (BuildConfig.DEBUG) {
            // Stack-Rush pattern: dump the FULL body being sent so the "0
            // parameters" symptom is diagnosable in Logcat. Print each key
            // with its value (truncated if huge) so we can see exactly what
            // AppsFlyer delivered vs. what was empty.
            val keys = mutableListOf<String>()
            val it = body.keys()
            while (it.hasNext()) keys.add(it.next())
            Log.i(TAG, "config body: uid=$afUid keys=${keys.sorted()}")
            for (k in keys.sorted()) {
                val v = body.opt(k)?.toString().orEmpty().take(160)
                Log.i(TAG, "  $k = $v")
            }
        }
        return api.fetch(body)?.let { it to afStatus }
    }

    /** Convenience wrapper used by resumeWebViewMode (no diag needed). */
    private suspend fun requestConfig(): ConfigResponse? =
        requestConfigWithDiag()?.first

    // ---- Navigation (deferred through splash handoff) -----------------------

    /**
     * Jump to [floor] immediately, then launch a background coroutine that
     * eases the bar toward [ceiling] over [durationMs] ms.  Calling again
     * cancels the previous ticker and starts a new one.  The real checkpoint
     * update (`splashProgress = NEXT_CP`) always wins via `maxOf`, so the
     * ticker never overshoots beyond [ceiling].
     */
    private fun advanceTo(floor: Float, ceiling: Float, durationMs: Long = 2500L) {
        splashProgress = maxOf(splashProgress, floor)
        tickerJob?.cancel()
        tickerJob = lifecycleScope.launch {
            val start = splashProgress
            val steps = (durationMs / 32L).toInt().coerceAtLeast(1)
            for (i in 1..steps) {
                if (!isActive) break
                val t = i.toFloat() / steps
                // Ease-out quad: fast start, slow end — bar moves quickly
                // at first then decelerates as it approaches the ceiling,
                // creating a "waiting" feel without stalling completely.
                val eased = 1f - (1f - t) * (1f - t)
                val next = start + (ceiling - start) * eased
                splashProgress = maxOf(splashProgress, next)
                delay(32L)
            }
        }
    }

    /** Stop the background ticker without changing [splashProgress]. */
    private fun stopTicker() { tickerJob?.cancel(); tickerJob = null }

    /**
     * Stores the WebView navigation as [pendingNav] and drives [splashProgress]
     * to 1.0. Actual activity start happens ~360 ms later when the
     * LoadingScreen's `onReady` fires and the progress bar animation lands.
     *
     * @param skipPromptGate  `true` for push-driven URLs — user already has push
     *   enabled, so the permission promo is redundant and the link must land fast.
     */
    private fun scheduleWebView(url: String, oneShot: Boolean = false, skipPromptGate: Boolean = false) {
        pendingNav = {
            if (skipPromptGate) {
                startActivity(
                    Intent(this, WebLinkActivity::class.java)
                        .putExtra(WebLinkActivity.EXTRA_URL, url)
                        .putExtra(WebLinkActivity.EXTRA_ONE_SHOT, oneShot)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            } else {
                PushPromptActivity.open(this, url, oneShot)
            }
            finish()
        }
        stopTicker()
        splashProgress = 1f
    }

    /**
     * Immediate WebView hand-off — used for push-driven URLs where the target
     * is already known and any splash-bar animation is pure delay. Mirrors
     * Stack-Rush Dispatcher.openPortal: `startActivity(next); finish()` with
     * no progress bar in between.
     *
     * `FLAG_ACTIVITY_CLEAR_TOP | SINGLE_TOP` routes through an existing
     * WebLinkActivity if one is on the stack (delivers `onNewIntent`), so a
     * warm-in-background tap lands on the same instance the user was already
     * looking at — no second WebView creation, no cover flash.
     */
    private fun openWebViewNow(url: String, oneShot: Boolean, skipPromptGate: Boolean) {
        pendingNav = null
        if (skipPromptGate) {
            startActivity(
                Intent(this, WebLinkActivity::class.java)
                    .putExtra(WebLinkActivity.EXTRA_URL, url)
                    .putExtra(WebLinkActivity.EXTRA_ONE_SHOT, oneShot)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        } else {
            PushPromptActivity.open(this, url, oneShot)
        }
        overridePendingTransition(0, 0)
        finish()
    }

    private fun scheduleGame() {
        pendingNav = {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            finish()
        }
        stopTicker()
        splashProgress = 1f
    }

    // ---- Push extras extraction --------------------------------------------

    /**
     * Extracts the push URL from a launch/new intent. Delegates to
     * [ThunderMessagingService.oneShotFrom] which handles our own extra,
     * raw FCM data keys, and the intent data URI — covering data-only
     * pushes, notification+data pushes, and OEM launcher shortcuts.
     */
    private fun extractPushUrlFromIntent(source: Intent?): String? {
        val explicit = source?.getStringExtra(EXTRA_PUSH_URL)
        if (!explicit.isNullOrBlank()) return explicit
        return ThunderMessagingService.oneShotFrom(source)
    }

    /**
     * Dumps the launch intent's action, data URI, and all string extras to
     * Logcat. Use this in debug builds to diagnose push-routing failures —
     * when a tap isn't routing, grab this dump and check whether the URL
     * is in the extras at all and which key it arrived under.
     */
    /**
     * Extends the window into the display-cutout region so the full-bleed
     * key art (Loading, NoWifi, PushPrompt backgrounds) paints all the way
     * to the top edge — no reserved OS black strip over the notch.
     * The WebLinkActivity keeps its own insets treatment (black top bar);
     * this only applies to the launcher/gray screens.
     */
    private fun allowDrawUnderCutout() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = mode }
    }

    private fun dumpIntentDebug(src: Intent?, phase: String) {
        if (src == null) { Log.d(TAG, "[$phase] intent = null"); return }
        Log.d(TAG, "[$phase] action=${src.action} data=${src.data} cats=${src.categories}")
        val extras = src.extras
        if (extras == null) { Log.d(TAG, "[$phase] extras = null"); return }
        Log.d(TAG, "[$phase] extras keys=${extras.keySet()}")
        for (k in extras.keySet()) {
            @Suppress("DEPRECATION")
            Log.d(TAG, "[$phase]   $k = ${extras.get(k)}")
        }
    }

}


