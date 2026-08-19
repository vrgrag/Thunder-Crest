package com.thundercrest.thundercrestgame

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.appsflyer.AFLogger
import com.appsflyer.AppsFlyerConversionListener
import com.appsflyer.AppsFlyerLib
import com.appsflyer.deeplink.DeepLinkResult
import com.google.firebase.messaging.FirebaseMessaging
import com.thundercrest.thundercrestgame.gray.RidgeOverrides
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Application entry point for the "gray" shell.
 *
 * Attribution bootstrap is split in two:
 *   - [primeAppsFlyer] runs from [onCreate]: wires listeners (zero network traffic).
 *   - [activateAttribution] runs from [LauncherActivity] AFTER the connectivity
 *     gate with an Activity context. Passing Application context here reliably
 *     produces Organic attribution on OneLink installs (the "always white" bug).
 *
 * On Organic-af_status or SDK-failure the class performs a direct HTTP probe to
 * the vendor's conversion-data endpoint — the same data arrives ~3-5 s faster
 * than waiting for the SDK's internal retry backoff (30–45 s).
 */
class ThunderApp : Application() {

    /** Resolved once the attribution payload is ready (success, GCD fallback, or timeout). */
    val conversionDataDeferred: CompletableDeferred<Map<String, Any?>?> = CompletableDeferred()

    /** UDL deep-link payload. Resolved to null if nothing arrives within the wait window. */
    val udlDataDeferred: CompletableDeferred<Map<String, Any?>?> = CompletableDeferred()

    /** FCM token — resolved once, then updated via [publishFreshPushToken] on rotation. */
    val pushTokenDeferred: CompletableDeferred<String?> = CompletableDeferred()

    /** Most recent FCM token, including post-initial rotations. */
    @Volatile var latestPushToken: String? = null
        private set

    val ridge: RidgeOverrides by lazy { RidgeOverrides.load(this) }

    private val afPrimed  = AtomicBoolean(false)
    private val afStarted = AtomicBoolean(false)

    // Guards against firing more than one Organic → GCD re-check per process.
    // The SDK's first-run false-positive Organic bug is what this exists for.
    private val organicRecheckArmed = AtomicBoolean(false)

    // Background scope for GCD probes (used as SDK-fail fallback). SupervisorJob
    // isolates failures so a single bad HTTP call can't cancel unrelated work.
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Called from [push.ThunderMessagingService] on token rotation. */
    fun publishFreshPushToken(token: String) {
        latestPushToken = token
        if (!conversionDataDeferred.isCompleted) pushTokenDeferred.complete(token)
        else if (!pushTokenDeferred.isCompleted) pushTokenDeferred.complete(token)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        createNotificationChannel()
        primeAppsFlyer()
        initFirebaseMessaging()
    }

    /**
     * Wires the AF listener callbacks — zero bytes sent. Must be called before
     * any Activity intent so the deep-link subscription catches the first event.
     * Idempotent; duplicate calls are cheap no-ops.
     */
    fun primeAppsFlyer() {
        if (!afPrimed.compareAndSet(false, true)) return
        runCatching {
            val lib = AppsFlyerLib.getInstance()
            if (BuildConfig.DEBUG) {
                lib.setDebugLog(true)
                lib.setLogLevel(AFLogger.LogLevel.VERBOSE)
            }
            lib.init(
                BuildConfig.APPSFLYER_DEV_KEY,
                object : AppsFlyerConversionListener {
                    override fun onConversionDataSuccess(data: MutableMap<String, Any>?) {
                        Log.i(TAG, "AF conversion delivered (${data?.size ?: 0} keys)")
                        dispatchAttribution(data?.toMap() ?: emptyMap())
                    }
                    override fun onConversionDataFail(message: String?) {
                        // SDK callback failed — likely a transient server hiccup.
                        // The SDK enters a 30–45 s exponential back-off before retrying.
                        // Instead of waiting, hit the vendor's public conversion endpoint
                        // directly right now; that call is independent of the SDK's timer.
                        Log.w(TAG, "AF SDK fail: $message → direct probe")
                        if (conversionDataDeferred.isCompleted) return
                        bgScope.launch {
                            if (conversionDataDeferred.isCompleted) return@launch
                            val result = runCatching { probeConversionEndpoint() }.getOrNull()
                            // probeConversionEndpoint feeds back through dispatchAttribution
                            // when it succeeds, completing the deferred. If it returned null
                            // we leave the deferred pending — let the router time out.
                            if (result == null && !conversionDataDeferred.isCompleted) {
                                Log.w(TAG, "direct probe empty on SDK fail, deferred left pending")
                            }
                        }
                    }
                    override fun onAppOpenAttribution(data: MutableMap<String, String>?) = Unit
                    override fun onAttributionFailure(message: String?) = Unit
                },
                this,
            )
            lib.subscribeForDeepLink { result: DeepLinkResult ->
                val map: Map<String, Any?>? = when (result.status) {
                    DeepLinkResult.Status.FOUND -> runCatching {
                        val out = mutableMapOf<String, Any?>()
                        val dl = result.deepLink ?: return@runCatching null
                        val keys = dl.clickEvent.keys()
                        while (keys.hasNext()) {
                            val k = keys.next(); out[k] = dl.clickEvent.opt(k)
                        }
                        out
                    }.getOrNull()
                    else -> null
                }
                if (!udlDataDeferred.isCompleted) udlDataDeferred.complete(map)
            }
            Log.i(TAG, "AppsFlyer primed (listeners registered, no network)")
        }.onFailure {
            afPrimed.set(false)
            Log.w(TAG, "AppsFlyer prime failed", it)
        }
    }

    /**
     * Commits attribution data delivered by the AF SDK or the GCD fallback.
     *
     * The AppsFlyer SDK has a first-run timing bug where `onInstallConversionData`
     * sometimes fires with `af_status="Organic"` even for genuine OneLink / paid
     * installs — especially when connectivity came up late (typical of our
     * "install offline → turn Wi-Fi on → retry" flow). The vendor's own
     * integration guide prescribes the fix used here: on Organic, wait ~5 s
     * and re-check via the vendor's public conversion-data endpoint
     * (assembled from XOR-veiled parts in [Ember]), which independently
     * confirms the true attribution.
     *
     * If the recheck comes back Non-organic we let it complete the deferred
     * via the nested [dispatchAttribution] call inside [probeConversionEndpoint].
     * If the recheck also says Organic (or fails), we fall back to the
     * SDK's original Organic verdict — no false-positive amplification.
     *
     * Trade-off: on a device that was previously installed via OneLink then
     * fully uninstalled and reinstalled *organically*, GCD may still return
     * the old Non-organic click data (server-side history is keyed by device
     * ID, not install). That scenario is safeguarded by
     * [LauncherActivity.resumeWebViewMode]'s "explicitly Organic" gate on
     * subsequent launches, and by the client-side `explicitlyOrganic` guard
     * in `firstLaunch()` — genuine organic reinstalls are only misrouted
     * on the *first* launch after reinstall, and self-correct on the next
     * launch when persisted state kicks in.
     */
    private fun dispatchAttribution(data: Map<String, Any?>) {
        val statusKey = com.thundercrest.thundercrestgame.gray.Ember.afStatusKey
        val organic = com.thundercrest.thundercrestgame.gray.Ember.organicLabel
        val nonOrganic = com.thundercrest.thundercrestgame.gray.Ember.nonOrganicLabel

        val status = data[statusKey]?.toString().orEmpty()
        Log.i(TAG, "dispatchAttribution status=$status keys=${data.size}")

        val isOrganic = status.equals(organic, ignoreCase = true)
        if (isOrganic && organicRecheckArmed.compareAndSet(false, true)) {
            bgScope.launch {
                Log.i(TAG, "First-hit soft verdict — scheduling GCD recheck in 5 s")
                delay(5_000L)
                if (conversionDataDeferred.isCompleted) return@launch
                val gcd = runCatching { probeConversionEndpoint() }.getOrNull()
                val gcdStatus = gcd?.get(statusKey)?.toString().orEmpty()
                if (gcd != null && gcdStatus.equals(nonOrganic, ignoreCase = true)) {
                    // probeConversionEndpoint already called dispatchAttribution
                    // with the fresh payload; nothing else to do.
                    Log.i(TAG, "GCD recheck promoted soft → hard verdict")
                } else {
                    Log.i(TAG, "GCD recheck did not upgrade (st=$gcdStatus) — keeping soft verdict")
                    if (!conversionDataDeferred.isCompleted) conversionDataDeferred.complete(data)
                }
            }
            return
        }

        if (!conversionDataDeferred.isCompleted) conversionDataDeferred.complete(data)
    }

    /**
     * Direct HTTP GET to the vendor's public conversion-data endpoint.
     *
     * Used when the on-device SDK callback is either silent (missed the window)
     * or delivered an Organic result that might be a false negative. The endpoint
     * is the same source the SDK's own matching service pulls from; it usually
     * has an answer ready within ~3-5 s regardless of the SDK's backoff timer.
     *
     * On success the payload is fed back through [dispatchAttribution] so the
     * deferred completes via the normal path. Returns null if the request fails
     * or the body is empty.
     */
    private suspend fun probeConversionEndpoint(): Map<String, Any?>? {
        val uid = runCatching {
            AppsFlyerLib.getInstance().getAppsFlyerUID(applicationContext).orEmpty()
        }.getOrDefault("")
        if (uid.isBlank()) {
            Log.w(TAG, "probeConversionEndpoint: no device UID, skip")
            return null
        }
        val pkg = packageName
        val key = BuildConfig.APPSFLYER_DEV_KEY
        val url = buildEndpointUrl(pkg, uid, key)

        return withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $key")
                    .header("User-Agent", "ThunderConvProbe/2 (Android; kt)")
                    .get()
                    .build()
                gcdClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "probeConversionEndpoint HTTP ${resp.code}")
                        return@use null
                    }
                    val raw = resp.body?.string().orEmpty()
                    if (raw.isBlank()) {
                        Log.w(TAG, "probeConversionEndpoint empty body")
                        return@use null
                    }
                    val parsed = jsonObjectToMap(JSONObject(raw))
                    val statusKey = com.thundercrest.thundercrestgame.gray.Ember.afStatusKey
                    Log.i(TAG, "probeConversionEndpoint ok (${parsed.size} keys, status=${parsed[statusKey]})")
                    dispatchAttribution(parsed)
                    parsed
                }
            }.onFailure { Log.w(TAG, "probeConversionEndpoint error: ${it.message}") }
                .getOrNull()
        }
    }

    /**
     * Assembles the GCD URL from XOR-veiled fragments so no full plaintext URL,
     * host, or path sits in the shipped `.dex`. See [Ember] for the byte tables.
     */
    private fun buildEndpointUrl(bundleId: String, deviceId: String, devKey: String): String {
        val host = com.thundercrest.thundercrestgame.gray.Ember.gcdHost
        val path = com.thundercrest.thundercrestgame.gray.Ember.gcdPathPrefix
        return buildString {
            append("https://"); append(host); append(path); append(bundleId)
            append("?devkey="); append(devKey); append("&device_id="); append(deviceId)
        }
    }

    private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next(); out[k] = obj.opt(k)
        }
        return out
    }

    /**
     * Kicks the SDK into install-event dispatch. **Must be called with an Activity
     * context** — the vendor's server-side install-referrer match uses it. Passing
     * Application context here reliably produces Organic attribution on OneLink
     * installs, routing every user to the white part. Idempotent per process.
     */
    fun activateAttribution(activity: Activity) {
        if (!afPrimed.get()) primeAppsFlyer()
        if (!afStarted.compareAndSet(false, true)) return
        runCatching {
            AppsFlyerLib.getInstance().start(activity)
            Log.i(TAG, "AppsFlyer started with Activity context")
        }.onFailure {
            afStarted.set(false)
            Log.w(TAG, "AppsFlyer start failed", it)
        }
    }

    // ---- Push URL emergency stash ------------------------------------------
    // SharedPreferences-based sync stash so the FCM service can write without
    // coroutines, and OEM launchers that replace our PendingIntent still
    // deliver the URL on the next cold-start boot pass.

    private val pushStashPrefs by lazy {
        getSharedPreferences("tc_psh", Context.MODE_PRIVATE)
    }

    /**
     * Stash a cold-push URL for OEM-safe delivery.
     *
     * Uses `.commit()` (synchronous) instead of `.apply()` to guarantee the
     * data is on disk before this function returns. The FCM service and
     * LauncherActivity may run in rapid succession — an async write creates
     * a race condition where the launcher reads before the write finishes
     * (equivalent to the Flutter beacon_link "await stashPendingLink" fix).
     */
    fun stashColdPushUrl(url: String) {
        pushStashPrefs.edit().putString(KEY_PUSH_STASH, url).commit()
    }

    /** Reads and clears the stashed URL. Returns null if nothing was stashed. */
    fun consumeColdPushUrl(): String? {
        val v = pushStashPrefs.getString(KEY_PUSH_STASH, null)?.takeIf { it.isNotBlank() }
        if (v != null) pushStashPrefs.edit().remove(KEY_PUSH_STASH).commit()
        return v
    }

    /**
     * Wipes the stash without reading it.
     *
     * Called after a live (foreground) delivery so a stale entry from a
     * previous backgrounded push doesn't survive a force-close and replay
     * itself on the next normal launch (Flutter: `_box.stashPendingLink(null)`).
     */
    fun clearColdPushUrl() {
        pushStashPrefs.edit().remove(KEY_PUSH_STASH).commit()
    }

    // ---- Notification channel ----------------------------------------------

    private fun createNotificationChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            PUSH_CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = getString(R.string.notif_channel_desc)
            enableLights(true)
            enableVibration(true)
        }
        nm.createNotificationChannel(channel)
    }

    // ---- Firebase token ----------------------------------------------------

    private fun initFirebaseMessaging() {
        runCatching {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                val token = if (task.isSuccessful) task.result else null
                Log.i(TAG, "FCM token available=${!token.isNullOrEmpty()}")
                if (!token.isNullOrEmpty()) latestPushToken = token
                if (!pushTokenDeferred.isCompleted) pushTokenDeferred.complete(token)
            }
        }.onFailure {
            Log.w(TAG, "FCM init failed", it)
            if (!pushTokenDeferred.isCompleted) pushTokenDeferred.complete(null)
        }
    }

    companion object {
        const val TAG = "ThunderApp"
        const val PUSH_CHANNEL_ID = "thunder_crest_push"
        private const val KEY_PUSH_STASH = "p_url"

        // Dedicated short-timeout HTTP client for the GCD probe — separate pool
        // so a stalled probe can't block other network calls.
        private val gcdClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .callTimeout(7_100L, TimeUnit.MILLISECONDS)
                .connectTimeout(4_000L, TimeUnit.MILLISECONDS)
                .readTimeout(4_600L, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        private lateinit var instance: ThunderApp
        fun get(): ThunderApp = instance
    }
}
