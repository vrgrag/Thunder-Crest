package com.thundercrest.thundercrestgame.volt.pipe

import android.app.Activity
import android.content.Context
import android.util.Log
import com.appsflyer.AppsFlyerConversionListener
import com.appsflyer.AppsFlyerLib
import com.appsflyer.deeplink.DeepLinkListener
import com.appsflyer.deeplink.DeepLinkResult
import com.thundercrest.thundercrestgame.BuildConfig
import com.thundercrest.thundercrestgame.volt.mark.VoltId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AppsFlyer wrapper, split into two deliberate halves.
 *
 * [prime] wires the callbacks and belongs in
 * [com.thundercrest.thundercrestgame.volt.VoltBoot]. It puts nothing on the
 * wire; what it does is let the SDK register its activity-lifecycle
 * hooks before the first Activity exists. Doing this from an Activity
 * that is already on screen means the SDK never sees the foreground
 * transition for this launch, and the install stays queued until the
 * next one — the attribution comes back empty and the user is filed as
 * organic.
 *
 * [ignite] is the half that talks to AppsFlyer, and the router only
 * calls it once it has confirmed a live connection. Starting the SDK
 * with no route out produces an immediate failure callback, which is
 * indistinguishable from a genuine organic install.
 *
 * Note what is deliberately absent: there is no second opinion sought
 * on an `Organic` verdict. The SDK's own map already *is* the GCD
 * result in 6.x — it fetches `install_data/v5.0` on a signed sharded
 * host and calls straight through. Re-asking the public `v4.0` endpoint
 * with a dev key (which an earlier revision of this class did) can only
 * ever answer `400 App ID is incorrect`, because that API wants an
 * account token. The re-check could not succeed, and it cost every
 * organic launch a five-second stall on the loading screen.
 */
class VoltTrack(private val ctx: Context) {

    private val primed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)

    @Volatile private var conversion = CompletableDeferred<Map<String, Any?>>()

    /** Last settled conversion map, kept so [retrace] can judge it. */
    @Volatile private var settled: Map<String, Any?>? = null

    /** Shortens the wait after a retrace — the SDK is warm by then. */
    @Volatile private var retraced = false

    private val deepLinkBag = linkedMapOf<String, Any?>()
    private val deepLink = CompletableDeferred<Unit>()

    // ─────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────

    /** Register callbacks. Application-time, no network. */
    fun prime() {
        if (!primed.compareAndSet(false, true)) return

        val key = VoltId.attributionKey
        if (key.isEmpty()) {
            warn("no dev key packed — attribution resolves empty")
            resolveEmpty()
            return
        }

        val wired = runCatching {
            val af = AppsFlyerLib.getInstance()
            af.setDebugLog(BuildConfig.DEBUG)
            af.subscribeForDeepLink(deepLinkListener)
            af.init(key, conversionListener, ctx.applicationContext)
        }
        if (wired.isFailure) {
            warn("SDK refused to wire up: ${wired.exceptionOrNull()?.message}")
            resolveEmpty()
        }
    }

    /**
     * Start the SDK. Must be handed a real [host] Activity — the
     * application context works for `init` but leaves `start` without
     * the foreground signal it exists to report.
     */
    fun ignite(host: Activity) {
        prime()
        if (VoltId.attributionKey.isEmpty()) return
        if (!started.compareAndSet(false, true)) return

        val lit = runCatching { AppsFlyerLib.getInstance().start(host) }
        if (lit.isFailure) {
            warn("SDK refused to start: ${lit.exceptionOrNull()?.message}")
            resolveEmpty()
        }
    }

    /**
     * Ask again after the connection came back.
     *
     * A launch that began with the radio off gets an immediate empty
     * callback, and that empty map would otherwise be the answer the
     * config POST is built from. If the first reading produced nothing
     * and we now have a route out, the question is worth re-opening.
     * A reading that did carry data is left alone — re-asking would
     * only trade a good answer for a race.
     */
    fun retrace(host: Activity) {
        if (!started.get()) {
            ignite(host)
            return
        }
        val last = settled ?: return
        if (last.isNotEmpty()) return

        settled = null
        retraced = true
        conversion = CompletableDeferred()
        runCatching { AppsFlyerLib.getInstance().start(host) }
        info("attribution re-asked now that the link is up")
    }

    // ─────────────────────────────────────────────────────────
    // Collection
    // ─────────────────────────────────────────────────────────

    /**
     * Awaits the conversion map and the deep link in parallel, then
     * returns the merged body. The deep link runs on its own shorter
     * budget: it is optional context, never the thing being waited for.
     *
     * @param firstLaunch picks the cold-install budget over the much
     *   shorter one a returning user should not have to sit through.
     */
    suspend fun collectBody(firstLaunch: Boolean): JSONObject = coroutineScope {
        val budget = when {
            retraced -> VoltId.ATTRIBUTION_TIMEOUT_MS_RETRACE
            firstLaunch -> VoltId.ATTRIBUTION_TIMEOUT_MS
            else -> VoltId.ATTRIBUTION_TIMEOUT_MS_RESUME
        }

        val linkWait = async {
            withTimeoutOrNull(VoltId.DEEP_LINK_TIMEOUT_MS) { deepLink.await() }
        }
        val dataWait = async {
            withTimeoutOrNull(budget) { conversion.await() } ?: emptyMap()
        }
        linkWait.await()
        val install = dataWait.await()

        // Conversion fields are authoritative; deep-link keys only fill
        // gaps they leave. Device fields are stamped by the caller and
        // overwrite both.
        JSONObject().apply {
            install.forEach { (k, v) -> if (v != null) put(k, v.toString()) }
            deepLinkBag.forEach { (k, v) -> if (v != null && !has(k)) put(k, v.toString()) }
        }
    }

    /**
     * True when the SDK produced actual attribution content. The router
     * needs this to tell a real "organic" verdict apart from an answer
     * the endpoint gave with nothing behind it.
     */
    fun hasAttributionData(): Boolean = settled?.isNotEmpty() == true

    fun deviceId(): String? = runCatching {
        AppsFlyerLib.getInstance().getAppsFlyerUID(ctx)
    }.getOrNull()

    // ─────────────────────────────────────────────────────────
    // Listeners
    // ─────────────────────────────────────────────────────────

    private val conversionListener = object : AppsFlyerConversionListener {
        override fun onConversionDataSuccess(data: MutableMap<String, Any>?) {
            val payload: Map<String, Any?> = data.orEmpty().toMap()
            info("conversion arrived, af_status=${payload["af_status"]}")
            settle(payload)
        }

        override fun onConversionDataFail(err: String?) {
            warn("conversion failed: $err")
            settle(emptyMap())
        }

        override fun onAppOpenAttribution(map: MutableMap<String, String>?) {
            map?.forEach { (k, v) -> deepLinkBag[k] = v }
        }

        override fun onAttributionFailure(err: String?) {
            warn("open attribution failed: $err")
        }
    }

    private val deepLinkListener = DeepLinkListener { result ->
        if (result.status != DeepLinkResult.Status.FOUND) {
            if (!deepLink.isCompleted) deepLink.complete(Unit)
            return@DeepLinkListener
        }
        runCatching {
            val click = result.deepLink.clickEvent
            click.keys().forEach { key -> deepLinkBag[key] = click.opt(key) }
        }
        if (!deepLink.isCompleted) deepLink.complete(Unit)
    }

    // ─────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────

    private fun settle(data: Map<String, Any?>) {
        settled = data
        if (!conversion.isCompleted) conversion.complete(data)
    }

    private fun resolveEmpty() {
        settle(emptyMap())
        if (!deepLink.isCompleted) deepLink.complete(Unit)
    }

    private fun info(message: String) {
        if (BuildConfig.DEBUG) Log.i(TAG, message)
    }

    private fun warn(message: String) {
        if (BuildConfig.DEBUG) Log.w(TAG, message)
    }

    private companion object {
        const val TAG = "VoltTrack"
    }
}
