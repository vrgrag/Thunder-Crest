package com.thundercrest.thundercrestgame.volt

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.thundercrest.thundercrestgame.BuildConfig
import com.thundercrest.thundercrestgame.R
import com.thundercrest.thundercrestgame.volt.mark.VoltId
import com.thundercrest.thundercrestgame.volt.pipe.VoltNet
import com.thundercrest.thundercrestgame.volt.pipe.VoltHandoff
import com.thundercrest.thundercrestgame.volt.pipe.VoltAgent
import com.thundercrest.thundercrestgame.volt.pipe.VoltStore
import com.thundercrest.thundercrestgame.volt.guard.VoltUrlGate
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full-screen WebView shell.
 *
 * Everything here exists because of something that went wrong on a real
 * device, so the sections are worth reading before editing:
 *
 *  - **Loading cover.** An affiliate entry chain is several full page
 *    loads; each hop commits and paints whatever it carries, usually a
 *    tracking pixel on black. The cover is the same artwork the router
 *    showed, held over the chain so the two read as one screen.
 *  - **Redirect loops.** Chromium gives up after 20 hops and real
 *    chains are routinely longer, so a loop is an ordinary condition to
 *    be resumed from where it stalled — not restarted from the top.
 *  - **Renderer death.** The process can be reclaimed under memory
 *    pressure; the dead WebView cannot even be asked what it was
 *    showing, so the last settled URL is what there is to go on.
 *  - **Connectivity.** The OS only reports a network that goes away.
 *    A VPN over switched-off Wi-Fi keeps every capability it had, so
 *    the heartbeat ends in a real probe rather than another capability
 *    read.
 *  - **Cutouts.** Top inset in portrait, left and right in landscape,
 *    plus a CSS injection that stops the page adding its own band on
 *    top of the one the window already reserved.
 */
class VoltPaneActivity : ComponentActivity() {

    private lateinit var container: FrameLayout
    private lateinit var web: WebView
    private lateinit var vault: VoltStore
    private lateinit var link: VoltNet

    /** Last main-frame URL that actually settled. */
    private var settledUrl: String? = null

    /**
     * Deepest main-frame URL seen, settled or not. A stalled chain
     * resumes from here — reloading the entry point only walks the same
     * hops again and burns the budget on the identical loop.
     */
    private var deepestHop: String? = null

    private var redirectRetries = 0
    private var entryPointRetried = false
    private var rendererRecoveries = 0

    /** A failed load still reaches onPageFinished; without this it resets the budget. */
    private var loadFailed = false

    /** True once a page has *stayed* on screen — the chain is over. */
    private var chainSettled = false

    /**
     * First page the user could actually read, after affiliate hops
     * finished. System back returns here from nested pages and is a
     * no-op on this URL itself — walking into the hops before it just
     * 30x's forward again.
     */
    private var landingUrl: String? = null

    private var cover: View? = null
    private var coverTimer: Job? = null

    @Volatile private var resumed = false
    @Volatile private var leftForOffline = false

    /** A loss that arrived while backgrounded, owed a screen on resume. */
    @Volatile private var offlineDeferred = false

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val callback = filePathCallback ?: return@registerForActivityResult
        filePathCallback = null
        callback.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
                ?: emptyArray(),
        )
    }

    // ─────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Orientation is declared in the manifest. Calling
        // setRequestedOrientation() here throws on API 27+ because the
        // edge-to-edge window reads as translucent.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        allowCutoutArea()

        vault = VoltStore(this)
        link = VoltNet(this)
        VoltHandoff.shellAlive = true

        container = FrameLayout(this)
        container.setBackgroundColor(Color.BLACK)
        container.fitsSystemWindows = false
        setContentView(container)
        installInsetRule()
        cloakStatusBand()

        buildWebView()

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    retreatTowardLanding()
                }
            },
        )

        val initial = resolveInitialUrl()
        if (initial == null) {
            info("no URL to load, finishing")
            finish()
            return
        }

        // Raised before the load, not on the first onPageStarted: the
        // frames in between are exactly what the router would hand over to.
        raiseCover()
        web.loadUrl(initial)

        watchConnectivity()
        startHeartbeat()

        lifecycleScope.launch {
            delay(VoltId.SAFE_AREA_DELAY_MS)
            injectSafeAreaKill()
        }
    }

    private fun resolveInitialUrl(): String? {
        val fromIntent = intent.getStringExtra(EXTRA_TARGET_URL)
        val pushed = vault.takePushLink()
        return VoltUrlGate.sanitize(pushed)
            ?: VoltUrlGate.sanitize(fromIntent)
            ?: VoltUrlGate.sanitize(vault.readCachedLink())
    }

    override fun onStart() {
        super.onStart()
        leftForOffline = false
        VoltHandoff.attach { url ->
            runOnUiThread { runCatching { web.loadUrl(url) } }
        }
        VoltHandoff.drain()?.let { url ->
            info("parked push URL delivered")
            runCatching { web.loadUrl(url) }
        }
    }

    /**
     * Time spent in another app is exactly when a connection is lost
     * without a request being in flight to notice, and it is also the
     * window in which [goOffline] is not allowed to start anything.
     */
    override fun onResume() {
        super.onResume()
        resumed = true
        cloakStatusBand()
        val deferred = offlineDeferred
        offlineDeferred = false
        lifecycleScope.launch {
            val alive = link.isReachable()
            when {
                !alive -> goOffline("offline on resume")
                deferred -> restoreAfterLoss()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) cloakStatusBand()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onStop() {
        VoltHandoff.detach()
        super.onStop()
    }

    override fun onDestroy() {
        VoltHandoff.detach()
        VoltHandoff.shellAlive = false
        runCatching {
            web.stopLoading()
            container.removeView(web)
            web.destroy()
        }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        leftForOffline = false

        val pushed = VoltUrlGate.sanitize(vault.takePushLink())
            ?: VoltUrlGate.sanitize(intent.getStringExtra(EXTRA_TARGET_URL))
            ?: return

        val current = web.url
        if (current.isNullOrEmpty() || current == BLANK || current != pushed) {
            info("new intent, loading target")
            landingUrl = null
            web.loadUrl(pushed)
        }
    }

    // ─────────────────────────────────────────────────────────
    // WebView construction
    // ─────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView() {
        val view = WebView(this)
        val s: WebSettings = view.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = true
        s.allowContentAccess = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.mediaPlaybackRequiresUserGesture = false

        // Pinned to the browser default. Left alone, the WebView
        // multiplies every font by the system font scale, and Samsung
        // ships plenty of devices set well above 1.0 out of the box —
        // the page then lays out against text that is 15-30% larger than
        // its CSS says, which reads as "the sizes are wrong" everywhere
        // at once. A real browser tab is what the site was built
        // against, so that is what it gets.
        s.textZoom = 100

        s.loadsImagesAutomatically = true
        s.blockNetworkImage = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.userAgentString = VoltAgent.value

        // Popups stay in this view. Asking for real second windows is
        // what makes the WebView demand a host for them and throw
        // "Parent WebView cannot host its own popup window".
        s.setSupportMultipleWindows(false)
        s.javaScriptCanOpenWindowsAutomatically = true

        view.setBackgroundColor(Color.BLACK)
        view.isHorizontalScrollBarEnabled = false
        view.isVerticalScrollBarEnabled = false
        view.webViewClient = pageClient
        view.webChromeClient = chromeClient

        web = view
        container.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(view, true)
    }

    /**
     * Builds a fresh WebView after a renderer death and puts the last
     * good page back. The dead one cannot be reused for anything —
     * including being asked what it was showing.
     */
    private fun replaceWebView() {
        val resumeAt = settledUrl ?: deepestHop ?: vault.readCachedLink() ?: return
        val dead = web
        container.removeView(dead)
        runCatching { dead.destroy() }
        buildWebView()
        web.loadUrl(resumeAt)
    }

    // ─────────────────────────────────────────────────────────
    // Loading cover
    // ─────────────────────────────────────────────────────────

    /**
     * Note what this is not: a snapshot of the view. Drawing a
     * hardware-accelerated WebView into a software canvas yields solid
     * black, which is precisely the "black screen between redirects"
     * the cover exists to replace.
     */
    private fun raiseCover() {
        coverTimer?.cancel()
        coverTimer = null

        cover?.let { existing ->
            existing.animate().cancel()
            existing.alpha = 1f
            return
        }

        val frame = FrameLayout(this)
        frame.setBackgroundColor(Color.BLACK)
        frame.isClickable = true

        val art = ImageView(this)
        art.setImageResource(coverArt())
        art.scaleType = ImageView.ScaleType.CENTER_CROP
        frame.addView(
            art,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        val spinner = ProgressBar(this)
        spinner.isIndeterminate = true
        spinner.indeterminateTintList = ColorStateList.valueOf(COVER_ACCENT)
        val spinnerParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        )
        spinnerParams.bottomMargin = (56f * resources.displayMetrics.density).toInt()
        frame.addView(spinner, spinnerParams)

        cover = frame
        // The window root, not [container]: that one is padded away
        // from the cutout, and a frame stopping short of it would not
        // line up with the router's screen the cover continues.
        coverHost().addView(
            frame,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        // A page that never reports back must not hold the screen for good.
        coverTimer = lifecycleScope.launch {
            delay(VoltId.COVER_MAX_MS)
            if (cover === frame) {
                info("loading cover timed out")
                dropCover(0L)
            }
        }
    }

    /**
     * @param after grace to wait for another hop before deciding this
     *   page is the destination. A hop's script runs after its own load
     *   finishes, so the next navigation starts a moment *later* than
     *   this one ended — waiting is the only way to tell a chain that
     *   is still going from one that has arrived. [raiseCover] cancels
     *   this, which keeps the cover from blinking between hops.
     */
    private fun dropCover(after: Long = VoltId.CHAIN_SETTLE_MS) {
        val current = cover ?: return
        coverTimer?.cancel()
        coverTimer = lifecycleScope.launch {
            delay(after)
            if (cover !== current) return@launch
            chainSettled = true
            pinLandingPane()
            cover = null
            current.animate().alpha(0f).setDuration(150L).withEndAction {
                coverHost().removeView(current)
            }.start()
        }
    }

    private fun coverHost(): FrameLayout = findViewById(android.R.id.content)

    private fun coverArt(): Int =
        if (isLandscape()) R.drawable.volt_splash_land else R.drawable.volt_splash_port

    // ─────────────────────────────────────────────────────────
    // WebViewClient
    // ─────────────────────────────────────────────────────────

    private val pageClient = object : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
            val target = req.url?.toString() ?: return false
            val scheme = target.substringBefore(':').lowercase()

            // A tap proves a page the user could read is on screen, so
            // whatever it leads to is a navigation, not another hop.
            if (req.isForMainFrame && req.hasGesture()) chainSettled = true

            return when {
                scheme in WEB_SCHEMES -> {
                    // Cleartext is refused app-wide, so a main-frame hop
                    // onto http:// dies on ERR_CLEARTEXT_NOT_PERMITTED —
                    // an error page with no way back. Redo it on TLS
                    // instead of losing the chain (pitfalls §15).
                    val upgraded = VoltUrlGate.sanitize(target)
                    if (req.isForMainFrame && upgraded != null && upgraded != target) {
                        info("cleartext hop upgraded to https")
                        deepestHop = upgraded
                        view.post { if (!isFinishing && !isDestroyed) view.loadUrl(upgraded) }
                        true
                    } else {
                        if (req.isForMainFrame) deepestHop = target
                        false
                    }
                }
                scheme == "intent" -> {
                    openIntentUri(target)
                    true
                }
                // Banks, wallets, messengers, stores. Handing these to
                // the WebView only produces ERR_UNKNOWN_URL_SCHEME, and
                // the list of schemes worth knowing has no end.
                else -> {
                    openExternally(target)
                    true
                }
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            loadFailed = false
            // shouldOverrideUrlLoading does not see every server-side
            // 30x, so the URL the engine committed to is the other half
            // of the trail.
            if (url != BLANK) deepestHop = url
            if (url != BLANK && !chainSettled) raiseCover()
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (loadFailed || url == BLANK) return
            redirectRetries = 0
            entryPointRetried = false
            settledUrl = url
            deepestHop = url
            CookieManager.getInstance().flush()
            injectSafeAreaKill()
            injectKeyboardScrollFix()
            dropCover()
        }

        override fun onReceivedError(
            view: WebView,
            req: WebResourceRequest,
            err: WebResourceError,
        ) {
            if (!req.isForMainFrame) return
            loadFailed = true

            val code = err.errorCode
            val description = runCatching { err.description?.toString() }.getOrNull().orEmpty()
            info("main-frame error $code on ${req.url?.host}")

            // A custom scheme reaching this point was already handed to
            // the system; the page behind it is still fine.
            if (code == ERROR_UNSUPPORTED_SCHEME) {
                dropCover(0L)
                return
            }

            // -1007 is Chromium's own ERR_TOO_MANY_REDIRECTS, which
            // some WebView builds surface instead of the framework code.
            val looping = code == ERROR_REDIRECT_LOOP ||
                code == ERROR_TOO_MANY_REQUESTS ||
                code == -1007 ||
                description.contains("too_many", ignoreCase = true)
            if (looping) {
                resumeChain(view, req.url?.toString().orEmpty())
                return
            }

            if (code in NETWORK_ERRORS || !link.hasAnyAdapter()) {
                view.stopLoading()
                view.loadUrl(BLANK)
                goOffline("main-frame network error $code")
                return
            }

            // Anything else: the page is what it is. Never leave the
            // user under an overlay waiting on a load that already failed.
            dropCover(0L)
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            info("render process gone, crashed=${detail.didCrash()}")
            if (isFinishing || view !== web) {
                runCatching { view.destroy() }
                return true
            }
            if (rendererRecoveries >= VoltId.RENDERER_RECOVERY_MAX) {
                goOffline("renderer recovery budget exhausted")
                return true
            }
            rendererRecoveries++
            replaceWebView()
            return true
        }
    }

    private val chromeClient = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            // Backstop for a page that reports progress but never a
            // finished load. about:blank is only ever loaded on the way
            // out, so its progress says nothing about the real page.
            if (newProgress < 100 || view.url == BLANK) return
            dropCover()
        }

        override fun onShowFileChooser(
            view: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams,
        ): Boolean {
            filePathCallback?.onReceiveValue(emptyArray())
            filePathCallback = callback
            return try {
                // The system chooser grants per-URI read permission, so
                // no storage permission is ever requested.
                filePicker.launch(params.createIntent())
                true
            } catch (_: Throwable) {
                filePathCallback = null
                false
            }
        }
    }

    /**
     * ERR_TOO_MANY_REDIRECTS recovery.
     *
     * Three things this gets right that the obvious version does not.
     * It resumes from [deepestHop] rather than the entry point, because
     * [settledUrl] has by then been overwritten with the page the chain
     * *started* from. It posts the reload instead of calling loadUrl
     * from inside the callback, since the engine is still unwinding the
     * failed navigation and would swallow or defer a re-entrant load.
     * And when the budget is gone it hands the page back rather than
     * leaving the user under the cover until its own timeout.
     */
    private fun resumeChain(view: WebView, failedUrl: String) {
        if (redirectRetries < VoltId.REDIRECT_RETRY_MAX) {
            redirectRetries++
            info("redirect loop, resume attempt $redirectRetries")
            queueLoad(view, deepestHop ?: failedUrl)
            return
        }

        // The chain itself is stuck; the entry point the backend named
        // usually still resolves, and the cookies picked up along the
        // way are often what it was missing.
        val entryPoint = vault.readCachedLink()
        if (!entryPointRetried && !entryPoint.isNullOrEmpty() && entryPoint != deepestHop) {
            entryPointRetried = true
            info("redirect budget spent, retrying the configured entry point")
            queueLoad(view, entryPoint)
            return
        }

        info("redirect chain unresolvable, handing the page back")
        dropCover(0L)
    }

    private fun queueLoad(view: WebView, url: String) {
        view.postDelayed({
            if (!isFinishing && !isDestroyed) view.loadUrl(url)
        }, RETRY_PAUSE_MS)
    }

    // ─────────────────────────────────────────────────────────
    // Links the WebView cannot take
    // ─────────────────────────────────────────────────────────

    private fun openExternally(url: String) {
        val intent = runCatching {
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }.getOrNull() ?: return
        launchOrIgnore(intent)
    }

    /**
     * `intent://` URIs name a target app and usually carry a
     * `browser_fallback_url`, so there are three things to try before
     * the user is left looking at nothing.
     */
    private fun openIntentUri(url: String) {
        val parsed = runCatching {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        }.getOrNull() ?: return

        val fallback = parsed.getStringExtra("browser_fallback_url")
        parsed.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        parsed.addCategory(Intent.CATEGORY_BROWSABLE)
        parsed.component = null
        parsed.selector = null

        if (launchOrIgnore(parsed)) return

        // The named app may be missing while another handles the scheme.
        parsed.`package` = null
        if (launchOrIgnore(parsed)) return

        VoltUrlGate.sanitize(fallback)?.let { web.loadUrl(it) }
    }

    private fun launchOrIgnore(intent: Intent): Boolean =
        runCatching { startActivity(intent) }.isSuccess

    // ─────────────────────────────────────────────────────────
    // Connectivity
    // ─────────────────────────────────────────────────────────

    private fun watchConnectivity() {
        lifecycleScope.launch {
            link.statusStream().collect { status ->
                if (status == VoltNet.Status.Offline) goOffline("default network lost")
            }
        }
    }

    private fun startHeartbeat() {
        lifecycleScope.launch {
            while (true) {
                delay(VoltId.HEARTBEAT_MS)
                if (!resumed || leftForOffline) continue
                if (!link.isReachable()) goOffline("network unreachable")
            }
        }
    }

    private fun goOffline(why: String) {
        if (leftForOffline) return

        // Android refuses an activity start from the background, and
        // the call would fail silently while this flag claimed the
        // screen had been shown. onResume settles it instead.
        if (!resumed) {
            offlineDeferred = true
            info("offline while backgrounded ($why), deferred to resume")
            return
        }
        leftForOffline = true
        info("offline ($why)")

        val resumeAt = settledUrl ?: web.url
        runCatching {
            web.stopLoading()
            web.loadUrl(BLANK)
        }
        startActivity(
            Intent(this, VoltQuietActivity::class.java).apply {
                if (!resumeAt.isNullOrEmpty() && resumeAt != BLANK) {
                    putExtra(VoltQuietActivity.EXTRA_RESUME_URL, resumeAt)
                }
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
    }

    /**
     * The link came back while the app was in the background. Nothing
     * navigated at the time, so if the loss had already emptied the
     * view the interrupted page has to be put back — otherwise the user
     * returns to a black screen on a working connection.
     */
    private fun restoreAfterLoss() {
        val current = web.url
        if (!current.isNullOrEmpty() && current != BLANK) return
        val resumeAt = settledUrl ?: deepestHop ?: vault.readCachedLink() ?: return
        info("link back after a loss, reloading")
        web.loadUrl(resumeAt)
    }

    // ─────────────────────────────────────────────────────────
    // Insets / safe area
    // ─────────────────────────────────────────────────────────

    private fun allowCutoutArea() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    /**
     * Immersive shell: status strip and nav buttons are both gone.
     * [safeArea] pads only for the camera cutout — not for bar sizes
     * the user cannot see anymore.
     *
     * Re-applied from onResume / onWindowFocusChanged / rotation because
     * a swipe from an edge (and several OEM overlays) puts the chrome back.
     */
    private fun cloakStatusBand() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        if (::container.isInitialized) container.requestApplyInsets()
    }

    /**
     * System back walks the WebView history **one page at a time**.
     * The only exception: if the next step would land in affiliate
     * tracker hops *before* [landingUrl], jump straight to the landing
     * page instead — those entries 30x forward again.
     *
     * Already on the landing page: no-op. The shell is not finished.
     */
    private fun retreatTowardLanding() {
        if (!::web.isInitialized) return
        pinLandingPane()
        val home = landingUrl
        val current = web.url
        if (current.isNullOrEmpty() || current == BLANK) return
        if (home != null && samePane(current, home)) return

        val list = web.copyBackForwardList()
        val idx = list.currentIndex
        var homeIdx = -1
        if (home != null) {
            for (i in 0 until list.size) {
                val item = list.getItemAtIndex(i)?.url ?: continue
                if (samePane(item, home)) {
                    homeIdx = i
                    break
                }
            }
        }

        if (homeIdx >= 0 && idx > 0 && idx - 1 < homeIdx) {
            web.goBackOrForward(homeIdx - idx)
            return
        }
        if (web.canGoBack()) {
            web.goBack()
            return
        }
        if (home != null && !samePane(current, home)) {
            web.loadUrl(home)
        }
    }

    /**
     * First page on the destination site, not the last hop of the
     * affiliate chain. History is typically:
     *   tracker → tracker → /preland (first) → /lobby (main)
     * Pinning the last settled URL would make back a no-op on main.
     */
    private fun pinLandingPane() {
        if (landingUrl != null) return
        if (!::web.isInitialized) return
        val now = settledUrl ?: web.url ?: return
        if (now.isEmpty() || now == BLANK) return

        val site = siteKey(now)
        val list = web.copyBackForwardList()
        var firstOnSite: String? = null
        for (i in 0 until list.size) {
            val item = list.getItemAtIndex(i)?.url ?: continue
            if (item == BLANK) continue
            if (siteKey(item) == site) {
                firstOnSite = item
                break
            }
        }
        landingUrl = firstOnSite ?: now
        info("landing pinned: $landingUrl")
    }

    private fun siteKey(url: String): String {
        val host = runCatching { Uri.parse(url).host }.getOrNull()
            ?.lowercase()
            ?.removePrefix("www.")
            ?: return url
        val parts = host.split('.')
        return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host
    }

    private fun samePane(a: String, b: String): Boolean {
        fun strip(raw: String): String {
            var s = raw
            val hash = s.indexOf('#')
            if (hash >= 0) s = s.substring(0, hash)
            if (s.endsWith('/') && s.count { it == '/' } > 2) {
                s = s.dropLast(1)
            }
            return s
        }
        return strip(a) == strip(b)
    }

    /**
     * Insets the WebView to the display cutout, and to nothing else.
     *
     * With both bars hidden the panel is the viewport, so there is no
     * band of it standing behind a bar to reserve. The camera hole is
     * the one thing left that is physically not screen: in portrait it
     * eats the top strip, in landscape whichever edge it rotated to.
     * Padding status/nav insets on top of that is what produced the
     * thick black side rails in landscape — the bars are gone, their
     * inset sizes must not be reserved.
     *
     * IME is excluded, so opening the keyboard does not re-inset the
     * view and fight the scroll fix.
     */
    private fun installInsetRule() {
        container.setOnApplyWindowInsetsListener { view, insets ->
            val safe = safeArea(insets)
            view.setPadding(safe[0], safe[1], safe[2], safe[3])
            insets
        }
        container.requestApplyInsets()
    }

    /** left, top, right, bottom — the display cutout only. */
    private fun safeArea(insets: WindowInsets): IntArray {
        val cutout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            insets.displayCutout
        } else {
            null
        }
        return intArrayOf(
            cutout?.safeInsetLeft ?: 0,
            cutout?.safeInsetTop ?: 0,
            cutout?.safeInsetRight ?: 0,
            cutout?.safeInsetBottom ?: 0,
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        cloakStatusBand()
        container.requestApplyInsets()
        // The cover carries per-orientation artwork and outlives a rotation.
        ((cover as? FrameLayout)?.getChildAt(0) as? ImageView)?.setImageResource(coverArt())
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // ─────────────────────────────────────────────────────────
    // JS injections
    // ─────────────────────────────────────────────────────────

    /**
     * The window already pads for the cutout, so a page that also
     * honours `env(safe-area-inset-*)` would leave a second empty band
     * on top of ours. Zeroing those variables removes it.
     *
     * What this must never do is touch the page's own box model. An
     * earlier revision zeroed padding and margin on `html, body, #app,
     * #root` — but sites build their gutters with exactly those
     * declarations, and the whole layout got squeezed flat against both
     * edges. Only `padding-top`, and only on wrappers known to add a
     * status-bar offset of their own.
     */
    private fun injectSafeAreaKill() {
        val js = """(function () {
          if (window.__voltSa) return; window.__voltSa = true;
          var ID = '__volt_sa';
          var CSS = ':root{' +
              '--safe-area-inset-top:0px!important;' +
              '--safe-area-inset-right:0px!important;' +
              '--safe-area-inset-bottom:0px!important;' +
              '--safe-area-inset-left:0px!important;' +
              '--sat:0px!important;--sar:0px!important;' +
              '--sab:0px!important;--sal:0px!important;' +
              '--safe-top:0px!important;--safe-bottom:0px!important;' +
              '--safe-left:0px!important;--safe-right:0px!important;' +
            '}' +
            '.gameview-mobile-header,.app-header,.js-safe-top{' +
              'padding-top:0!important;margin-top:0!important;' +
            '}';
          function kbOpen(){
            if (!window.visualViewport) return false;
            return window.visualViewport.height < window.innerHeight * 0.75;
          }
          function apply(){
            if (kbOpen()) return;
            var head = document.head || document.documentElement; if (!head) return;
            var m = document.querySelector('meta[name="viewport"]');
            // A page with no viewport meta is laid out against a 980px
            // canvas and then scaled to fit, which is how a mobile site
            // ends up unreadably small in a wrapper. Give it the meta a
            // mobile browser would have found.
            if (!m) {
              m = document.createElement('meta');
              m.setAttribute('name','viewport');
              m.setAttribute('content','width=device-width, initial-scale=1, viewport-fit=contain');
              head.appendChild(m);
            } else if (!/viewport-fit\s*=\s*contain/i.test(m.getAttribute('content')||'')) {
              var c = (m.getAttribute('content')||'').replace(/,?\s*viewport-fit\s*=\s*\w+/ig,'').trim();
              m.setAttribute('content', c + (c ? ', ' : '') + 'viewport-fit=contain');
            }
            var s = document.getElementById(ID);
            if (!s) { s = document.createElement('style'); s.id = ID; head.appendChild(s); }
            if (s.textContent !== CSS) s.textContent = CSS;
            if (head.lastElementChild !== s) head.appendChild(s);
          }
          apply();
          ['pushState','replaceState'].forEach(function (fn) {
            var o = history[fn]; history[fn] = function () {
              var r = o.apply(this, arguments);
              setTimeout(apply, 80); setTimeout(apply, 400); return r;
            };
          });
          window.addEventListener('popstate', function(){ setTimeout(apply, 80); });
          setInterval(apply, 2500);
        })();""".trimIndent()
        runCatching { web.evaluateJavascript(js, null) }
    }

    /**
     * Single-pass scroll-into-view on the focused input, using
     * `behavior:'auto'` — never `smooth`, which loses the race against
     * the IME re-layout and lands the field half off screen.
     */
    private fun injectKeyboardScrollFix() {
        val js = """(function(){
          if (window.__voltKb) return; window.__voltKb = true;
          document.addEventListener('focusin', function(e){
            var el = e.target;
            if (!el || (el.tagName !== 'INPUT' && el.tagName !== 'TEXTAREA')) return;
            setTimeout(function(){
              try { el.scrollIntoView({behavior:'auto',block:'center',inline:'nearest'}); } catch(_){}
            }, 350);
          });
        })();""".trimIndent()
        runCatching { web.evaluateJavascript(js, null) }
    }

    private fun info(message: String) {
        if (BuildConfig.DEBUG) Log.i(TAG, message)
    }

    companion object {
        const val EXTRA_TARGET_URL = "volt_target_url"

        private const val TAG = "VoltAsk"
        private const val BLANK = "about:blank"
        private const val RETRY_PAUSE_MS = 60L

        /** Gold accent shared with the router's loading screen. */
        private const val COVER_ACCENT = 0xFF63BEF8.toInt()

        /** Everything the WebView itself can take. */
        private val WEB_SCHEMES =
            setOf("http", "https", "about", "data", "blob", "file", "javascript")

        /** Host lookup, connect, timeout, IO, proxy failures. */
        private val NETWORK_ERRORS = setOf(-2, -6, -7, -8, -11)
    }
}
