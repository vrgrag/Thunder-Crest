package com.thundercrest.thundercrestgame.volt.pipe

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.thundercrest.thundercrestgame.BuildConfig
import com.thundercrest.thundercrestgame.R
import com.thundercrest.thundercrestgame.volt.VoltBoot
import com.thundercrest.thundercrestgame.volt.VoltHubActivity
import com.thundercrest.thundercrestgame.volt.kind.VoltMode
import com.thundercrest.thundercrestgame.volt.guard.VoltUrlGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Push notification plumbing.
 *
 * The `CHANNEL_ID` here MUST match the manifest meta-data
 * `com.google.firebase.messaging.default_notification_channel_id`.
 * See the gray-flow pitfalls guide §13.
 */
object VoltPush {

    // [FINGERPRINT] Change per project (and update AndroidManifest.xml).
    const val CHANNEL_ID = "volt_crest_alerts"
    private const val CHANNEL_NAME = "Crest alerts"
    private const val CHANNEL_DESC = "Bonuses, promos, and important updates"
    private const val TAG = "VoltPush"

    /** Our own extras, set when *we* build the tap intent. */
    const val EXTRA_URL = "portal_url"
    const val EXTRA_FROM_PUSH = "portal_from_push"

    /** Payload keys the Firebase SDK forwards verbatim as string extras. */
    private val RAW_URL_KEYS = listOf("url", "link", "target_url")

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = CHANNEL_DESC
            enableLights(true)
            enableVibration(true)
        }
        mgr.createNotificationChannel(channel)
    }

    /**
     * Current FCM token, or `null` on any failure — a missing
     * google-services.json, Play Services absent, a device with no
     * network. Per the config contract the caller must then omit
     * `push_token` and `firebase_project_id` entirely rather than send
     * empty strings.
     */
    suspend fun fetchToken(): String? = suspendCancellableCoroutine { cont ->
        runCatching {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
        }.onFailure {
            if (cont.isActive) cont.resume(null)
        }
    }

    /**
     * The URL a notification tap carried, in either shape it can arrive.
     *
     * A data-only message reaches [VoltPushService], which builds the
     * tap intent with [EXTRA_URL]. A message carrying a `notification`
     * block is drawn by the Firebase SDK itself whenever the app is not
     * in the foreground — that path never runs our service at all, and
     * the tap opens the launcher with the raw `data` payload as plain
     * string extras instead. Reading only our own extra is how a pushed
     * link gets silently dropped and the shell reopens on the previously
     * saved page (pitfalls §32).
     */
    fun extractUrl(intent: Intent?): String? {
        if (intent == null) return null
        val own = intent.getStringExtra(EXTRA_URL)
        val raw = RAW_URL_KEYS.firstNotNullOfOrNull { intent.getStringExtra(it) }
        return VoltUrlGate.sanitize(own ?: raw)
    }

    /** True when this intent came from a notification tap at all. */
    fun isPushTap(intent: Intent?): Boolean {
        if (intent == null) return false
        if (intent.getBooleanExtra(EXTRA_FROM_PUSH, false)) return true
        return RAW_URL_KEYS.any { intent.hasExtra(it) }
    }

    internal fun buildTapIntent(ctx: Context, url: String?): Intent =
        Intent(ctx, VoltHubActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_FROM_PUSH, true)
            if (!url.isNullOrEmpty()) putExtra(EXTRA_URL, url)
        }

    internal fun render(ctx: Context, title: String, body: String, url: String?, imageUrl: String?) {
        ensureChannel(ctx)

        val pending = PendingIntent.getActivity(
            ctx,
            System.currentTimeMillis().toInt(),
            buildTapIntent(ctx, url),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_volt_flame)
            .setColor(ContextCompat.getColor(ctx, R.color.volt_flame_tint))
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        val bitmap = imageUrl?.takeIf { it.isNotEmpty() }?.let(::loadBitmap)
        if (bitmap != null) {
            builder
                .setLargeIcon(bitmap)
                .setStyle(
                    NotificationCompat.BigPictureStyle()
                        .bigPicture(bitmap)
                        .bigLargeIcon(null as Bitmap?),
                )
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }

        val id = (System.currentTimeMillis() and 0x7FFFFFFF).toInt()
        val mgr = NotificationManagerCompat.from(ctx)
        if (!mgr.areNotificationsEnabled()) {
            if (BuildConfig.DEBUG) Log.w(TAG, "tray refused — notifications disabled at OS")
            return
        }
        runCatching { mgr.notify(id, builder.build()) }
            .onFailure {
                if (BuildConfig.DEBUG) Log.w(TAG, "notify failed: ${it.message}")
            }
    }

    /**
     * Re-POST the config body the moment FCM hands us a token. The
     * first launch after an offline install often asks the backend
     * before Play Services has a token, so this is the recovery the
     * contract describes — not a poll, a single event-driven retry.
     */
    suspend fun relayFreshToken(ctx: Context, token: String) {
        if (token.isEmpty()) return
        val app = ctx.applicationContext as? VoltBoot ?: return
        val tracker = runCatching { app.tracker }.getOrNull() ?: return
        val body = tracker.collectBody(firstLaunch = false)
        val ask = VoltAsk()
        ask.decorate(body, ctx, tracker, forcedToken = token)
        ask.query(body)
    }

    private fun loadBitmap(url: String): Bitmap? = runCatching {
        VoltAgent.http.newCall(okhttp3.Request.Builder().url(url).get().build())
            .execute().use { resp ->
                val body = resp.body
                if (!resp.isSuccessful || body == null) null
                else body.byteStream().use { BitmapFactory.decodeStream(it) }
            }
    }.getOrElse {
        if (BuildConfig.DEBUG) Log.w(TAG, "big-picture load failed: ${it.message}")
        null
    }
}

/**
 * Firebase messaging service. Registered from AndroidManifest.xml —
 * the class name is what stays stable, so ProGuard must keep it.
 *
 * URL routing rules, in order:
 *
 *  - A URL that fails [VoltUrlGate] is dropped and the notification
 *    is shown as text. A tap must never open a page the shell cannot
 *    load.
 *  - A user on [VoltMode.Native] keeps their game. The notification
 *    still appears, but the URL is neither handed over nor saved:
 *    flipping someone into a WebView after the fact is a store-review
 *    problem, not a feature.
 *  - A live shell takes the URL directly through [VoltHandoff] and
 *    nothing is persisted — a warm URL is a fact about this moment.
 *  - Otherwise it is stashed for exactly one cold start.
 */
class VoltPushService : FirebaseMessagingService() {

    private val bg = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onDestroy() {
        bg.cancel()
        super.onDestroy()
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Cached so the config POST does not have to wait on Firebase
        // on every launch, and so a rotation is not lost if the next
        // launch happens offline. Then immediately re-POST — an
        // offline-first install typically asked the backend before this
        // callback ever fired, and without a second POST the backend
        // has no token to reach this device.
        val vault = VoltStore(applicationContext)
        vault.writePushToken(token)
        bg.launch { VoltPush.relayFreshToken(applicationContext, token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val notification = message.notification

        val title = data["title"] ?: notification?.title.orEmpty()
        val body = data["body"] ?: notification?.body.orEmpty()
        if (title.isEmpty() && body.isEmpty()) return

        val url = VoltUrlGate.sanitize(data["url"] ?: data["link"] ?: data["target_url"])
        val image = data["image"] ?: notification?.imageUrl?.toString()

        val vault = VoltStore(applicationContext)
        val mode = vault.readMode()

        if (url != null && mode != VoltMode.Native) {
            if (VoltHandoff.offer(url)) {
                // The shell took it; still show the notification so the
                // user knows something arrived, but do not stash a URL
                // that has already been delivered.
                bg.launch { VoltPush.render(applicationContext, title, body, null, image) }
                return
            }
            vault.stashPushLink(url)
        }

        val tapUrl = if (mode == VoltMode.Native) null else url
        bg.launch { VoltPush.render(applicationContext, title, body, tapUrl, image) }
    }
}
