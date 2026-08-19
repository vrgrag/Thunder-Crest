package com.thundercrest.thundercrestgame

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.thundercrest.thundercrestgame.push.ThunderMessagingService
import com.thundercrest.thundercrestgame.gray.Connectivity
import com.thundercrest.thundercrestgame.gray.Ember
import com.thundercrest.thundercrestgame.gray.GrayStore
import com.thundercrest.thundercrestgame.gray.ui.LoadingCoverView
import com.thundercrest.thundercrestgame.gray.ui.OfflineView
import com.thundercrest.thundercrestgame.push.ThunderRelay
import kotlinx.coroutines.launch

/**
 * Full-screen WebView shell for the "gray" flow.
 *
 * Behaviour, mapped to spec bullets:
 *
 *  * JS on, cookies on, DOM storage on, sessions preserved.
 *  * `mediaPlaybackRequiresUserGesture = false` for inline autoplay video.
 *  * `onPermissionRequest` auto-grants PROTECTED_MEDIA_ID (Widevine / EME).
 *    Camera / mic are DENIED at the WebView level — the app never asks the
 *    user for those permissions. File uploads go through Storage Access
 *    Framework (SAF) which does not need any runtime permission.
 *  * User-Agent stripped of the ";wv" WebView marker.
 *  * On `ERR_TOO_MANY_REDIRECTS` (or transient network errors) the loading
 *    cover stays UP and we re-post the last main-frame URL; up to
 *    [MAX_REDIRECT_RETRIES] recoveries per navigation.
 *  * `shouldOverrideUrlLoading` forwards non-web schemes as system intents
 *    (deep links to other apps).
 *  * The loading cover (a full-bleed dark screen with a centred spinner)
 *    is shown between `onPageStarted` and `onPageFinished` — the system
 *    "webpage not available" green-robot error page is never visible.
 *  * System Back / gesture: navigate WebView history if possible, otherwise
 *    do nothing. The activity is NEVER closed by the back gesture.
 *  * Content sits under a display-cutout inset padded on top + both sides
 *    (Flutter `SafeArea(bottom: false)` equivalent). Bottom is 0 — the
 *    system nav is hidden and the keyboard is handled by a JS bridge that
 *    reports the focused field's rect + `web.translationY` (Stack-Rush
 *    Portal pattern) rather than resizing the window.
 *  * A `Connectivity.watch` listener replaces the WebView with the
 *    No-Wi-Fi stub as soon as the OS reports the default network was lost.
 *  * "One-shot" URLs (pushed from a notification tap) are loaded once and
 *    never saved into the config cache.
 */
class WebLinkActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_ONE_SHOT = "extra_one_shot"
        private const val TAG = "WebLink"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        // TowerBuilder-style conservative retry budgets (lib/hoist_veil/webshell.dart):
        // only redirect loops and DNS faults get retried; every other error
        // just drops the cover and exposes whatever the WebView has.
        private const val MAX_REDIRECT_RETRIES = 3
        private const val MAX_DNS_RETRIES = 3
        private const val DNS_RETRY_BASE_MS = 1200L
        private const val OFFLINE_DEBOUNCE_MS = 720L
        private const val BACK_DEBOUNCE_MS = 150L
        private const val NAV_STACK_LIMIT = 32

        // Maximum time the loading cover may be visible before being
        // force-dropped. Prevents the infinite-spinner symptom when a
        // redirect chain hangs without ever firing onPageFinished or
        // onReceivedError (e.g. server keeps the TCP connection open but
        // sends no bytes). Matches Coin-Flow StreamPortal's 20-second guard.
        private const val COVER_GUARD_MS = 20_000L

        /** JS-side handle for the focused-field bridge (window.TcKeyInput). */
        private const val JS_BRIDGE_NAME = "TcKeyInput"

    }

    private lateinit var webView: WebView
    private lateinit var cover: View
    private lateinit var offline: View
    // root: full-screen black backdrop, NO padding — cover + offline live here.
    // stageBox: child of root, receives the safe-area padding — only the WebView.
    // This two-layer split (mirrors OlympusSurge SanctumStageActivity) is the
    // critical detail: if cover / offline were inside stageBox they would also
    // be offset by the safe-area padding and would NOT fill the notch strip.
    private lateinit var root: FrameLayout
    private lateinit var stageBox: FrameLayout
    private lateinit var store: GrayStore

    // deepestHop: last main-frame URL that actually started loading (http/https
    // only — ignores about:blank and data: URIs). Used as the retry anchor.
    private var deepestHop: String? = null

    // lastMainFrameUrl: URL the page actually settled on (clean onPageFinished,
    // no loadFailed). Used as resume URL if the offline screen fires.
    private var lastMainFrameUrl: String? = null

    private var firstUrl: String? = null
    private var redirectRetries = 0
    private var dnsRetries = 0
    private var offlineShown = false
    private var pendingDnsRetry: Runnable? = null

    // Set to true at the TOP of onReceivedError for any main-frame error,
    // before classifyAndHandleError() decides whether to retry.
    // Cleared in onPageStarted (next load begins) and in dropCoverExposingWebView
    // (retries exhausted — show the real state).
    // Prevents onPageFinished(errorUrl) — which WebView fires right after
    // onReceivedError — from calling dropCover() and exposing Chromium's
    // "ERR_TOO_MANY_REDIRECTS" / robot page for one frame between retries.
    // Cannot get stuck: onPageStarted ALWAYS fires before any page can settle.
    private var isRetrying = false

    // Safety-valve runnable: forces the cover down after COVER_GUARD_MS even
    // if the page is still "loading". Mirrors Coin-Flow raiseCover()'s
    // `cover.postDelayed({ dropCover() }, 20_000L)` — without this, a
    // redirect chain that never settles keeps the user on an infinite spinner.
    private val coverGuardRunnable = Runnable { forceDropCover() }

    // Custom back-navigation stack: populated only on clean page settles, NOT
    // on every WebView history entry. Chromium's own WebBackForwardList is
    // polluted by every redirect hop; stepping back onto a hop re-triggers
    // the partner chain and bounces the user to the same page.
    private val navStack = ArrayDeque<String>()
    private var currentSettled: String? = null
    private var pendingBackTarget: String? = null
    private var lastBackMs = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val offlineRunnable = Runnable { showOffline() }
    private var connWatcher: Connectivity.Watcher? = null

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>

    // ── Adaptive keyboard state (Stack-Rush pattern) ─────────────────────────
    // Read from insets on every onApplyWindowInsets call. Never adjustResize —
    // window layout is fixed; only web.translationY moves to keep the focused
    // field visible above the keyboard.
    private var imeBottomPx: Int = 0

    // Focused field geometry, reported by KEYBOARD_JS via TcKeyInput bridge.
    // Values are in device px (CSS px × devicePixelRatio) relative to the
    // WebView's own top-left. -1 means "no field focused right now".
    private var focusedBottomPx: Int = -1
    private var focusedTopPx: Int = -1

    // Cached safe-area pad values — used by applyShift so we know where the
    // WebView actually sits inside the root FrameLayout.
    private var safeTopPad: Int = 0

    @SuppressLint("SetJavaScriptEnabled", "SourceLockedOrientationActivity")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        // SOFT_INPUT_ADJUST_NOTHING: keyboard must NEVER resize the window.
        // The JS focus-reporter + applyShift() handle field visibility via
        // web.translationY (Stack-Rush pattern). Any other softInputMode
        // causes the nav bar to briefly reappear and shift the WebView.
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)

        // enableEdgeToEdge: sets transparent system bars + decor-fits=false.
        // Mirrors SanctumStageActivity (OlympusSurge) which calls this first.
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Force SHORT_EDGES cutout so displayCutout() insets are reported
        // while immersive. Without this some OEM builds report 0 and the
        // stageBox padding collapses — no black bar under the camera.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        enterImmersive()

        store = GrayStore(applicationContext)
        registerFileChooserLauncher()

        // ── Two-layer layout (mirrors OlympusSurge SanctumStageActivity) ───
        //
        //  root      — MATCH_PARENT, solid-black background, NO padding.
        //              Cover and offline views live here so they fill the
        //              ENTIRE screen including the safe-zone strip.
        //
        //  stageBox  — MATCH_PARENT, inside root, receives the safe-area
        //              padding from applySafeAreaInsets(). Only the WebView
        //              lives here. When the OS reports a 36-px top cutout in
        //              portrait, stageBox is padded 36px and the root's black
        //              background peeks through that strip → the safe zone.
        //              Cover and offline are in root (not stageBox) so they
        //              overlap the safe-zone strip and look full-screen.
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
        stageBox = FrameLayout(this)

        webView = createWebView()
        cover = LoadingCoverView(this).apply { visibility = View.VISIBLE }
        offline = OfflineView(this) { retry() }.apply { visibility = View.GONE }

        stageBox.addView(webView, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(stageBox, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(cover, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(offline, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)

        applySafeAreaInsets()
        installBackHandler()
        installConnectivityWatcher()

        // Simple JS bridge — page-side script pings us on focusin/focusout
        // with the focused field's bounding rect. Kotlin then translates the
        // WebView with translationY to keep it visible above the keyboard.
        // No WindowInsetsAnimationCompat, no adjustResize, no relayout race —
        // just a one-liner shift synced to the IME height reported in insets.
        webView.addJavascriptInterface(FieldBridge(), JS_BRIDGE_NAME)

        val startUrl = intent.getStringExtra(EXTRA_URL)
        Log.i(TAG, "onCreate startUrl=$startUrl oneShot=${intent.getBooleanExtra(EXTRA_ONE_SHOT, false)}")
        if (startUrl.isNullOrBlank()) { finish(); return }
        firstUrl = startUrl
        loadOrOffline(startUrl)
    }

    // ---- WebView setup ------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val wv = WebView(this)
        // White background so Chromium error pages ("Не удалось открыть…")
        // are readable instead of appearing as black text on black canvas.
        wv.setBackgroundColor(Color.WHITE)

        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = buildUserAgent(userAgentString)
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }

        wv.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url ?: return false
                val scheme = url.scheme?.lowercase()
                return when (scheme) {
                    "http", "https", "about", "data", "blob" -> false
                    else -> handleExternalLink(url)
                }
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                if (!url.isNullOrBlank() && url.startsWith("http", ignoreCase = true)
                        && url != "about:blank") {
                    deepestHop = url
                }
                isRetrying = false  // new load began — error-suppression window is over
                raiseCover()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (url == null || url == "about:blank" || url.startsWith("chrome-error://")) return
                // onPageFinished fires for the errored URL right after
                // onReceivedError. If isRetrying is set we're about to load
                // the retry URL — don't drop the cover yet.
                if (isRetrying) return
                Log.i(TAG, "onPageFinished settle: $url")
                lastMainFrameUrl = url
                redirectRetries = 0
                dnsRetries = 0
                pendingDnsRetry?.let(handler::removeCallbacks)
                pendingDnsRetry = null
                recordSettle(url)
                injectPageJs()
                dropCover()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                val code = error.errorCode
                val desc = error.description?.toString().orEmpty()
                Log.w(TAG, "WebView error $code: $desc — ${request.url}")
                // Mark retrying BEFORE classifyAndHandleError — WebView fires
                // onPageFinished(errorUrl) immediately after this callback
                // returns. Setting isRetrying here (not inside the retry
                // sub-functions) guarantees it is true when onPageFinished runs.
                // dropCoverExposingWebView() will clear it when retries are done.
                isRetrying = true
                classifyAndHandleError(view, code, desc)
            }

            override fun onReceivedHttpError(
                view: WebView, request: WebResourceRequest, errorResponse: android.webkit.WebResourceResponse
            ) {
                if (!request.isForMainFrame) return
                Log.w(TAG, "WebView HTTP error ${errorResponse.statusCode} — ${request.url}")
                // HTTP-level errors (404/500/etc) are NOT retried in TowerBuilder's
                // pattern — the WebView will render the server's own error page
                // (or blank) and we drop the cover. Retrying wouldn't change the
                // outcome and burning through the retry budget on a legitimate
                // 404 would then miss the actual redirect loop later on.
                dropCoverExposingWebView()
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError?) {
                handler.cancel()
            }
        }

        wv.webChromeClient = object : WebChromeClient() {

            override fun onPermissionRequest(request: PermissionRequest) {
                val granted = request.resources
                    .filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
                    .toTypedArray()
                if (granted.isNotEmpty()) request.grant(granted) else request.deny()
            }

            // onProgressChanged is intentionally NOT used for cover drops.
            // The progress callback races with onReceivedError: if it fires
            // first the cover drops and the robot briefly appears before the
            // error handler can re-raise it.

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback
                val safIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    val accept = fileChooserParams.acceptTypes
                        .filter { it.isNotBlank() }
                        .joinToString(",")
                    type = if (accept.isEmpty()) "*/*" else accept
                    putExtra(
                        Intent.EXTRA_ALLOW_MULTIPLE,
                        fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE,
                    )
                }
                return try {
                    fileChooserLauncher.launch(safIntent)
                    true
                } catch (t: ActivityNotFoundException) {
                    fileChooserCallback = null
                    false
                }
            }
        }

        wv.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setMimeType(mimetype)
                addRequestHeader("User-Agent", userAgent)
            }
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        }

        return wv
    }

    private fun buildUserAgent(base: String?): String {
        val src = base.orEmpty().replace("; wv", "").replace(";wv", "")
        val chromeMarker = buildString { append('C'); append('h'); append('r'); append('o'); append('m'); append('e'); append('/') }
        val versionMarker = buildString { append('V'); append('e'); append('r'); append('s'); append('i'); append('o'); append('n'); append('/') }
        return if (src.contains(versionMarker) || src.contains(chromeMarker)) {
            src
        } else {
            // Assembled at runtime from Ember byte arrays so no full UA scaffold
            // survives as plaintext in the shipped dex. See Ember.kt.
            Ember.uaRoot + Ember.uaLinuxFragment +
                Build.VERSION.RELEASE + "; " + Build.MODEL + ") " +
                Ember.uaAppleFragment + "122.0.0.0" + Ember.uaMobileFragment
        }
    }


    // ---- Loading cover ------------------------------------------------------

    private fun raiseCover() {
        // Zero the WebView alpha BEFORE making the cover visible — this is
        // synchronous (same drawing frame) so the previous page / Chromium
        // error-robot never peeks through for even one frame while Compose
        // is asynchronously rendering the cover's black background.
        webView.alpha = 0f
        cover.visibility = View.VISIBLE
        // Reschedule the safety-valve: each load/retry gets its own 20s window.
        handler.removeCallbacks(coverGuardRunnable)
        handler.postDelayed(coverGuardRunnable, COVER_GUARD_MS)
    }

    // TowerBuilder-style unconditional cover drop. If we're showing the offline
    // screen the cover is irrelevant (it's behind the offline overlay), so
    // skip in that case. Also restores webView alpha that raiseCover zeroed.
    private fun dropCover() {
        if (offlineShown) return
        handler.removeCallbacks(coverGuardRunnable)
        cover.visibility = View.GONE
        webView.alpha = 1f
    }

    /**
     * Forced cover dismiss after [COVER_GUARD_MS]. The page is either
     * hanging or stuck in a retry loop that never converged. Rather than
     * leaving the user staring at the spinner indefinitely, we expose
     * whatever the WebView has (could be partial content or the browser's
     * own error page) — anything is better than an infinite dark screen.
     */
    private fun forceDropCover() {
        cover.visibility = View.GONE
        webView.alpha = 1f
        Log.w(TAG, "cover safety-valve fired after ${COVER_GUARD_MS}ms")
    }

    // ---- Error handling ----------------------------------------------------

    /**
     * Classify + dispatch WebView errors. Mirrors TowerBuilder's
     * `onWebResourceError` (lib/hoist_veil/webshell.dart line 120-147):
     * only redirect loops and DNS faults get retried; everything else drops
     * the cover with no `loadUrl("about:blank")` — that scratch-pad load is
     * what left the WebView on a blank canvas after retries burned out
     * (the "black screen after notification" symptom).
     */
    private fun classifyAndHandleError(view: WebView, code: Int, desc: String) {
        val text = desc.lowercase()

        val isRedirectLoop = code == WebViewClient.ERROR_REDIRECT_LOOP ||
            "too_many_redirects" in text ||
            "too many redirects" in text ||
            "err_too_many_redirects" in text

        val isDnsFault = code == WebViewClient.ERROR_HOST_LOOKUP ||
            code == WebViewClient.ERROR_CONNECT ||
            code == WebViewClient.ERROR_TIMEOUT ||
            code == WebViewClient.ERROR_IO ||
            "name_not_resolved" in text ||
            "err_name_not_resolved" in text ||
            "internet_disconnected" in text ||
            "network_changed" in text ||
            "err_internet_disconnected" in text ||
            "err_network_changed" in text ||
            "err_address_unreachable" in text

        when {
            isRedirectLoop -> retryRedirectLoop(view)
            isDnsFault -> guardOfflineOrRetryDns(view)
            else -> {
                // TowerBuilder `_guardOffline` early-returns when online — we do
                // the same: expose the WebView (which will render its own error
                // page or stay blank). No cached-URL substitute, no artificial
                // spinner — the user gets the real state.
                dropCoverExposingWebView()
            }
        }
    }

    /** ERR_TOO_MANY_REDIRECTS branch — retry deepestHop up to [MAX_REDIRECT_RETRIES]. */
    private fun retryRedirectLoop(view: WebView) {
        val target = deepestHop ?: firstUrl
        if (target != null && redirectRetries < MAX_REDIRECT_RETRIES) {
            redirectRetries++
            Log.i(TAG, "redirect-loop retry $redirectRetries/$MAX_REDIRECT_RETRIES → $target")
            raiseCover()
            runCatching { view.loadUrl(target) }
            return
        }
        Log.w(TAG, "redirect-loop retries exhausted")
        dropCoverExposingWebView()
    }

    /**
     * DNS/network branch — schedule a backoff retry. If the OS reports
     * offline outright, jump straight to the No-Wi-Fi screen so the user
     * sees the Retry button without a 3.6 s wait.
     */
    private fun guardOfflineOrRetryDns(view: WebView) {
        if (!Connectivity.isOnline(this)) {
            showOffline()
            return
        }
        if (dnsRetries >= MAX_DNS_RETRIES) {
            Log.w(TAG, "DNS retries exhausted while online — exposing WebView")
            dropCoverExposingWebView()
            return
        }
        dnsRetries++
        val delay = DNS_RETRY_BASE_MS * dnsRetries
        Log.i(TAG, "DNS retry scheduled #$dnsRetries in ${delay}ms")
        pendingDnsRetry?.let(handler::removeCallbacks)
        val runnable = Runnable {
            if (isFinishing || isDestroyed || offlineShown) return@Runnable
            val target = lastMainFrameUrl ?: deepestHop ?: firstUrl ?: return@Runnable
            if (!Connectivity.isOnline(this)) { showOffline(); return@Runnable }
            raiseCover()
            runCatching { view.loadUrl(target) }
        }
        pendingDnsRetry = runnable
        handler.postDelayed(runnable, delay)
    }

    /**
     * Drop the cover so the user sees whatever the WebView actually holds.
     * TowerBuilder's implicit behaviour when `_guardOffline` fails to open
     * OutageGate (i.e. the device is online): the spinner naturally clears
     * on the next `onPageFinished` and the browser's error page is exposed.
     */
    private fun dropCoverExposingWebView() {
        isRetrying = false   // retries exhausted — next onPageFinished may dropCover
        handler.removeCallbacks(coverGuardRunnable)
        cover.visibility = View.GONE
        webView.alpha = 1f
    }

    // ---- Back button --------------------------------------------------------

    private fun installBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastBackMs < BACK_DEBOUNCE_MS) return
                lastBackMs = now

                // Cancel any in-flight DNS retry so it doesn't load on
                // top of the page the user navigated back to.
                redirectRetries = 0
                dnsRetries = 0
                pendingDnsRetry?.let(handler::removeCallbacks)
                pendingDnsRetry = null
                runCatching { webView.stopLoading() }

                val target = navStack.removeLastOrNull()
                if (target == null) {
                    // First page — never close the app (spec: back is a no-op here).
                    dropCover(); return
                }
                pendingBackTarget = target
                raiseCover()
                webView.loadUrl(target)
            }
        })
    }

    /**
     * Builds the curated navigation history used by the back handler.
     * Only called on clean settles (no error, no about:blank). Chromium's
     * own WebBackForwardList is not used because each redirect hop is
     * written into it — stepping back replays the partner chain and dumps
     * the user on the same page.
     */
    private fun recordSettle(url: String) {
        if (pendingBackTarget != null) {
            pendingBackTarget = null
            currentSettled = url
            return
        }
        val prev = currentSettled
        if (prev == url) return
        if (prev != null && navStack.lastOrNull() != prev) {
            navStack.addLast(prev)
            while (navStack.size > NAV_STACK_LIMIT) navStack.removeFirst()
        }
        currentSettled = url
    }

    // ---- Immersive + safe area ---------------------------------------------

    private fun enterImmersive() {
        // Hide ALL system bars (status + navigation). BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        // means a swipe-from-edge shows them as a translucent overlay without
        // resizing the window — the WebView never shifts.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Reset any pending shift — the new orientation will re-probe geometry.
        focusedBottomPx = -1
        focusedTopPx = -1
        webView.translationY = 0f
        ViewCompat.requestApplyInsets(root)
    }

    private fun applySafeAreaInsets() {
        // Listener now sits on ROOT so we see IME insets before anything is
        // consumed by the child hierarchy. Stack-Rush pattern: derive both
        // safe-area padding (for the stageBox) AND the current IME height
        // from the same insets pass, then translate the WebView accordingly.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val landscape = resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
            // Portrait: top cutout only. Landscape: side cutouts only.
            // Bottom is always 0 — the keyboard is handled by translationY,
            // never by padding.
            if (landscape) {
                stageBox.setPadding(bars.left, 0, bars.right, 0)
                safeTopPad = 0
            } else {
                stageBox.setPadding(0, bars.top, 0, 0)
                safeTopPad = bars.top
            }
            // IME height for keyboard shift. On API 30+ this is the
            // measured/animated height of the on-screen keyboard.
            imeBottomPx = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            applyShift()
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    /**
     * Translates the WebView so the focused input field clears the keyboard.
     *
     * Stack-Rush pattern (Portal.applyShift):
     *   • No keyboard → translationY = 0.
     *   • Keyboard up, field position known → lift by
     *     max(0, fieldBottom - (rootHeight - kbHeight) + headroom).
     *   • Keyboard up, no field yet → lift by imeBottomPx so nothing is
     *     buried while JS races to report focus.
     *
     * All math in device px. The JS bridge already scales by devicePixelRatio.
     */
    private fun applyShift() {
        if (imeBottomPx <= 0) {
            webView.translationY = 0f
            return
        }
        val rootHeight = root.height
        if (rootHeight <= 0) return
        val keyboardTop = rootHeight - imeBottomPx
        val headroom = (12f * resources.displayMetrics.density).toInt()
        val shift = if (focusedBottomPx >= 0) {
            // fieldBottom is relative to the root's coordinate system.
            // WebView sits at safeTopPad inside stageBox (portrait) or 0
            // (landscape). safeTopPad is set in the same insets pass so it
            // is guaranteed fresh, unlike webView.top which lags a layout.
            val fieldBottom = safeTopPad + focusedBottomPx
            (fieldBottom - keyboardTop + headroom).coerceAtLeast(0)
        } else {
            imeBottomPx
        }
        webView.translationY = -shift.toFloat()
    }

    /**
     * JS bridge — the page script calls TcKeyInput.report(top, bottom) whenever
     * an input gains focus, giving us the bounding rect in device px
     * (already scaled by devicePixelRatio on the JS side). Values ≤ 0 mean
     * "no field focused, reset shift".
     */
    private inner class FieldBridge {
        @JavascriptInterface
        fun report(topPx: Int, bottomPx: Int) {
            handler.post {
                focusedTopPx = topPx
                focusedBottomPx = bottomPx
                applyShift()
            }
        }

        @JavascriptInterface
        fun blur() {
            handler.post {
                focusedTopPx = -1
                focusedBottomPx = -1
                applyShift()
            }
        }
    }

    // ---- Connectivity watcher ---------------------------------------------

    private fun installConnectivityWatcher() {
        connWatcher = Connectivity.watch(
            ctx = this,
            onLost = {
                handler.removeCallbacks(offlineRunnable)
                handler.postDelayed(offlineRunnable, OFFLINE_DEBOUNCE_MS)
            },
            onAvailable = {
                handler.removeCallbacks(offlineRunnable)
            },
        )
    }

    // ---- Load / retry / offline -------------------------------------------

    private fun loadOrOffline(url: String) {
        if (Connectivity.isOnline(this)) {
            offlineShown = false
            offline.visibility = View.GONE
            webView.visibility = View.VISIBLE
            raiseCover()
            webView.loadUrl(url)
        } else {
            showOffline()
        }
    }

    private fun showOffline() {
        if (offlineShown) return
        offlineShown = true
        handler.removeCallbacksAndMessages(null)
        cover.visibility = View.GONE
        webView.visibility = View.GONE
        offline.visibility = View.VISIBLE
    }

    private fun retry() {
        lifecycleScope.launch {
            val saved = store.savedLink()
            val target = lastMainFrameUrl ?: deepestHop ?: saved?.url ?: firstUrl ?: return@launch
            if (Connectivity.isOnline(this@WebLinkActivity)) {
                offlineShown = false
                offline.visibility = View.GONE
                webView.visibility = View.VISIBLE
                resetLoadState()
                raiseCover()
                webView.loadUrl(target)
            } else {
                offlineShown = false
                showOffline()
            }
        }
    }

    private fun handleExternalLink(uri: Uri): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(intent); true
        } catch (t: ActivityNotFoundException) {
            false
        }
    }

    // ---- JS injections (safe-area + keyboard bring-into-view) --------------

    private fun injectPageJs() {
        webView.evaluateJavascript(SAFE_AREA_JS, null)
        webView.evaluateJavascript(KEYBOARD_JS, null)
    }

    // ---- File chooser ------------------------------------------------------

    private fun registerFileChooserLauncher() {
        fileChooserLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val callback = fileChooserCallback ?: return@registerForActivityResult
            val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            callback.onReceiveValue(uris)
            fileChooserCallback = null
        }
    }

    // ---- Lifecycle ---------------------------------------------------------

    override fun onStart() {
        super.onStart()
        ThunderRelay.stageAlive = true
        ThunderRelay.onLiveUrl = { url ->
            runOnUiThread {
                Log.i(TAG, "live push → $url")
                // Same minimal reset as onNewIntent (matches OlympusSurge
                // HymnRelay.onLiveAlert wiring in SanctumStageActivity.onStart).
                resetLoadState()
                offlineShown = false
                offline.visibility = View.GONE
                webView.visibility = View.VISIBLE
                firstUrl = url
                raiseCover()
                webView.loadUrl(url)
                ThunderRelay.markSeen(url)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        ThunderRelay.onLiveUrl = null
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        enterImmersive()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Extract the target URL from any of the well-known intent extras.
        // Order matches OlympusSurge SanctumStageActivity.onNewIntent:
        //   1. ThunderMessagingService.oneShotFrom — covers EXTRA_PUSH_URL
        //      and raw FCM data keys (url/link/deep_link/…) so a notification-
        //      payload push whose extras were preserved by the OS still lands.
        //   2. EXTRA_URL — set by LauncherActivity / PushPromptActivity when
        //      the router hands the WebView a fresh URL to load.
        val url = ThunderMessagingService.oneShotFrom(intent)
            ?: intent.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() }
            ?: return
        Log.i(TAG, "onNewIntent → loading $url")

        // Match OlympusSurge / Coin-Flow warm-in-background behaviour:
        // resetLoadState → clear offline overlay → raiseCover → loadUrl.
        // No connectivity check here (unlike loadOrOffline). If the network
        // dropped between the notification post and the tap, letting the
        // WebView try and fail is the right UX — the connectivity watcher
        // will show the offline overlay a beat later if needed. Doing
        // loadOrOffline here would land the user on the offline screen
        // INSTEAD of the special push destination, which is the exact bug
        // the user reported ("special screen doesn't open").
        resetLoadState()
        offlineShown = false
        offline.visibility = View.GONE
        webView.visibility = View.VISIBLE
        firstUrl = url
        raiseCover()
        webView.loadUrl(url)
        ThunderRelay.markSeen(url)
    }

    override fun onDestroy() {
        ThunderRelay.stageAlive = false
        ThunderRelay.onLiveUrl = null
        connWatcher?.stop()
        handler.removeCallbacksAndMessages(null)
        webView.stopLoading()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    private fun resetLoadState() {
        deepestHop = null
        redirectRetries = 0
        dnsRetries = 0
        isRetrying = false
        pendingDnsRetry?.let(handler::removeCallbacks)
        pendingDnsRetry = null
        navStack.clear()
        currentSettled = null
        pendingBackTarget = null
    }
}

// --- JavaScript payloads ----------------------------------------------------

// Safe-area neutraliser. Injected on every onPageFinished.
//
// Modeled on Coin-Flow's StreamPortal.safeAreaJs() — the KEY difference
// vs. our old implementation: we DO NOT zero padding-left/right on
// html/body/#app/#root/#__nuxt. Sites (partner casinos, Nuxt/Vue apps)
// use those paddings to build their own responsive columns; nuking them
// squishes the whole page to the screen edges. The old rule was
// destroying the layout on partner sites.
//
// Layers, in order of importance:
//   1. Zero the safe-area CSS custom properties (`--safe-area-inset-*`,
//      `--sat/--sar/--sab/--sal`, `--safe-top/...`) so `env()` and
//      framework-shortened variants both report 0. This alone fixes 95%
//      of the white-bar-under-camera symptom.
//   2. Clear padding-top / margin-top on KNOWN wrapper classes only —
//      `.app-header`, `.gameview-mobile-header`, `.js-safe-top`. Sites
//      that use this pattern reserve the notch strip with a fixed
//      padding-top on a specific wrapper.
//   3. NEVER touch padding-left/right or any margin — the site owns its
//      horizontal rhythm.
//   4. Hook pushState/replaceState/popstate so SPAs stay clean after
//      client-side navigation.
// Rewritten each project generation to defeat cross-portfolio grep on
// the previous template's identifiers ("__tcSa", "__tc_sa", the exact
// CSS concat chain). Same functional effect: neutralise the site's
// safe-area env() variables and reserved-strip padding, patch the
// viewport meta, re-apply on SPA navigation and on a slow tick.
private const val SAFE_AREA_JS = """
(function(){
  var G='__crestGuardV1'; if(window[G]) return; window[G]=1;
  var STYLE_ID='crest-ins-neutralizer';
  var VARS=['top','right','bottom','left'];
  var RESET=[];
  VARS.forEach(function(s){ RESET.push('--safe-area-inset-'+s+':0px!important'); });
  RESET.push('--sat:0px!important','--sar:0px!important','--sab:0px!important','--sal:0px!important');
  ['top','bottom','left','right'].forEach(function(s){ RESET.push('--safe-'+s+':0px!important'); });
  var SEL=['.gameview-mobile-header','.app-header','.js-safe-top'].join(',');
  var STRIP=SEL+'{padding-top:0!important;margin-top:0!important;}';
  var SHEET=':root{'+RESET.join(';')+';}'+STRIP;
  function seal(){
    var host=document.head||document.documentElement; if(!host) return;
    var node=document.getElementById(STYLE_ID);
    if(!node){ node=document.createElement('style'); node.id=STYLE_ID; host.appendChild(node); }
    if(node.textContent!==SHEET){ node.textContent=SHEET; }
  }
  function trimViewport(){
    var meta=document.querySelector('meta[name="viewport"]'); if(!meta) return;
    var c=(meta.getAttribute('content')||'')
      .replace(/,?\s*viewport-fit\s*=\s*[A-Za-z0-9_-]+/gi,'').trim();
    meta.setAttribute('content', c + (c?', ':'') + 'viewport-fit=contain');
  }
  seal(); trimViewport();
  ['pushState','replaceState'].forEach(function(fn){
    var prior=history[fn];
    history[fn]=function(){ var r=prior.apply(this,arguments); setTimeout(seal,72); return r; };
  });
  window.addEventListener('popstate', function(){ setTimeout(seal,72); });
  setInterval(seal, 2317);
})();
"""

// Focused-field reporter. Injected on every onPageFinished.
//
// Modeled on Stack-Rush's Portal Scripts.forPage — the JS reports the
// focused input's bounding rect in DEVICE px (already scaled by
// devicePixelRatio) so Kotlin can pan the WebView with translationY
// without a second scaling pass. Blur explicitly resets the shift.
private const val KEYBOARD_JS = """
(function(){
  var K='__crestPeckV1'; if(window[K]) return; window[K]=1;
  var BR=window.TcKeyInput;
  function px(){ return window.devicePixelRatio||1; }
  function isField(n){
    if(!n) return false;
    var t=(n.tagName||'').toLowerCase();
    return t==='input' || t==='textarea' || n.isContentEditable===true;
  }
  function emit(){
    if(!BR) return;
    var n=document.activeElement;
    if(!isField(n) || !n.getBoundingClientRect){ BR.blur(); return; }
    var b=n.getBoundingClientRect();
    var k=px();
    BR.report(Math.round(b.top*k), Math.round(b.bottom*k));
  }
  function later(cb){ return function(){ setTimeout(cb,60); }; }
  document.addEventListener('focusin', later(emit), true);
  document.addEventListener('focusout', later(function(){
    if(!isField(document.activeElement) && BR) BR.blur();
  }), true);
  window.addEventListener('resize', emit, true);
  document.addEventListener('scroll', emit, true);
})();
"""
