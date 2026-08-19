package com.thundercrest.thundercrestgame.push

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.thundercrest.thundercrestgame.LauncherActivity
import com.thundercrest.thundercrestgame.R
import com.thundercrest.thundercrestgame.ThunderApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

/**
 * FCM entry point.
 *
 *  * `onNewToken` — publishes the fresh token to [ThunderApp.pushTokenDeferred]
 *    and stores it so the next config request picks it up.
 *  * `onMessageReceived` — renders a local notification with the custom flame
 *    icon and any inline image. The message URL travels through the
 *    notification's PendingIntent extras, NOT the persistent store — this
 *    keeps the URL scoped to a single tap and lets [LauncherActivity] route
 *    it cold-start OR warm-start with the same code path.
 *
 * Payload contract:
 *  * `data.url` (also accepts `link`, `uri`, `href`) — one-shot URL to open.
 *  * `notification.title` / `notification.body` — headline; also accepts
 *    `data.title` / `data.body` for CRMs that send data-only pushes.
 *  * `notification.image` or `data.image` — big picture attachment.
 */
class ThunderMessagingService : FirebaseMessagingService() {

    // SupervisorJob so a single failed image download does not cancel siblings.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        Log.i(TAG, "New FCM token")
        val app = applicationContext as? ThunderApp ?: return
        app.publishFreshPushToken(token)
    }

    override fun onMessageReceived(msg: RemoteMessage) {
        val note = msg.notification
        val data = msg.data

        val title = firstNonEmpty(
            note?.title, data["title"], data["notification_title"], data["header"]
        ) ?: getString(R.string.app_name)
        val body = firstNonEmpty(
            note?.body, data["body"], data["message"], data["text"]
        ).orEmpty()
        val imageUrl = firstNonEmpty(
            note?.imageUrl?.toString(),
            data["image"], data["imageUrl"], data["image_url"], data["picture"]
        )
        val url = extractLink(data)
        val messageId = msg.messageId

        Log.i(TAG, "onMessageReceived id=$messageId stageAlive=${ThunderRelay.stageAlive} " +
            "hasUrl=${!url.isNullOrBlank()} url=$url title='$title'")

        // Route the URL through the unified dispatch gate.
        // Returns true when a live WebView consumed it — no banner needed.
        if (!url.isNullOrBlank() && dispatchPushUrl(url)) {
            Log.i(TAG, "live dispatch → running stage, no banner")
            return
        }

        // Show the banner IMMEDIATELY then update with the image if available.
        val notifId = messageId?.hashCode() ?: System.currentTimeMillis().toInt()
        postNotification(title, body, null, url, messageId, notifId)

        if (!imageUrl.isNullOrBlank()) {
            scope.launch {
                val image = downloadBitmap(imageUrl)
                if (image != null) {
                    postNotification(title, body, image, url, messageId, notifId)
                }
            }
        }
    }

    private fun postNotification(
        title: String,
        body: String,
        image: Bitmap?,
        url: String?,
        messageId: String?,
        notifId: Int,
    ) {
        val intent = buildTapIntent(url)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        // Same notifId → same requestCode → the image re-post reuses the intent.
        val pending = PendingIntent.getActivity(this, notifId, intent, flags)

        val builder = NotificationCompat.Builder(this, ThunderApp.PUSH_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setContentIntent(pending)

        if (image != null) {
            builder.setLargeIcon(image)
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(image)
                    .bigLargeIcon(null as Bitmap?)
            )
        } else if (body.isNotBlank()) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }

        // Diagnostic gate — silent-drop reasons on modern Android are
        // (a) POST_NOTIFICATIONS runtime perm not granted (API 33+),
        // (b) app-level notifications disabled by user, or
        // (c) our channel disabled. Log each condition explicitly so
        // "notifications not arriving" complaints are traceable in Logcat.
        val nmc = NotificationManagerCompat.from(this)
        val nm = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
        val hasPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else true
        val appEnabled = nmc.areNotificationsEnabled()
        val channelEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm?.getNotificationChannel(ThunderApp.PUSH_CHANNEL_ID)?.importance !=
                NotificationManager.IMPORTANCE_NONE
        } else true
        Log.i(TAG, "postNotification permGranted=$hasPerm appEnabled=$appEnabled " +
            "channelEnabled=$channelEnabled id=$notifId")

        if (!hasPerm) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted — banner will be dropped by OS. " +
                "User must accept the runtime permission (PushPromptActivity → Accept).")
        }
        if (!appEnabled) {
            Log.w(TAG, "App notifications disabled by user in system settings.")
        }
        if (!channelEnabled) {
            Log.w(TAG, "Channel '${ThunderApp.PUSH_CHANNEL_ID}' importance=NONE — user muted our channel.")
        }

        runCatching {
            nmc.notify(messageId, notifId, builder.build())
            Log.i(TAG, "notify() called ok tag=$messageId id=$notifId")
        }.onFailure { Log.w(TAG, "notify failed", it) }
    }

    /**
     * URL routing gate — mirrors TowerBuilder's `BeaconLink._dispatch`
     * (lib/scaffold_link/beacon_link.dart line 249-259):
     *
     * ```
     * void _dispatch(String link) {
     *   final void Function(String)? live = onLink;
     *   if (live != null) {
     *     live(link);
     *     _box.stashPendingLink(null);     // clear stash
     *   } else {
     *     _box.stashPendingLink(link);     // stash for router
     *   }
     * }
     * ```
     *
     * Mutually exclusive: either a live foreground WebView consumes the URL
     * right now (in which case the stash is cleared so a force-close cannot
     * replay it on next boot), or the URL is stashed for [LauncherActivity]
     * to pick up on its next `startFlow` — whether that's a cold boot from
     * the notification tap OR a warm-in-background tap that goes through
     * the router before hitting the WebView.
     *
     * @return true when live delivery occurred (no banner needed).
     */
    private fun dispatchPushUrl(url: String): Boolean {
        val app = applicationContext as? ThunderApp
        if (ThunderRelay.deliver(url)) {
            Log.i(TAG, "dispatchPushUrl: live foreground delivery, clearing stash")
            app?.clearColdPushUrl()
            return true
        }
        // Not delivered live — persist for the router to pick up on the
        // next startFlow (either cold-start via notification tap or warm
        // resume). Uses .commit() (synchronous) so LauncherActivity's
        // consumeColdPushUrl() sees the write even if the tap fires
        // immediately after this service returns.
        app?.stashColdPushUrl(url)
        Log.i(TAG, "dispatchPushUrl: stashed for router (stageAlive=${ThunderRelay.stageAlive})")
        return false
    }

    /**
     * Builds the tap intent — TowerBuilder single-entry pattern
     * (lib/hoist_veil/hoist_router.dart + lib/scaffold_link/beacon_link.dart).
     *
     * Every notification tap lands on [LauncherActivity]. The router reads
     * the pending push URL first thing in `startFlow` and immediately
     * hand-offs to WebLink via `openWebViewNow` — no splash render, no config
     * gate. If a WebLink is already alive further down the task stack, the
     * `CLEAR_TOP | SINGLE_TOP` flags on the WebLink start intent will
     * reuse that instance via `onNewIntent(url)` rather than create a fresh
     * one. If not, a fresh WebLink is created.
     *
     * We deliberately do NOT target [WebLinkActivity] directly here even in
     * the warm-in-background case. The previous warm-direct route (matching
     * OlympusSurge/Coin-Flow) landed on the running WebView's `onNewIntent`,
     * but a `loadUrl` on a WebView whose state had drifted (offline overlay
     * up, cover stuck from a redirect retry, or a partial about:blank load)
     * produced the "infinite spinner" symptom the user kept reporting.
     * Routing through the launcher lets us reset from a known-good state
     * every time. Additionally, a live [ThunderRelay.deliver] still handles
     * the foreground case with no notification shown at all.
     */
    private fun buildTapIntent(url: String?): Intent {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_SINGLE_TOP or
            Intent.FLAG_ACTIVITY_CLEAR_TOP

        return Intent(this, LauncherActivity::class.java).apply {
            addFlags(flags)
            if (!url.isNullOrBlank()) {
                putExtra(LauncherActivity.EXTRA_PUSH_URL, url)
                Log.i(TAG, "buildTapIntent: → LauncherActivity + push URL $url")
            } else {
                Log.i(TAG, "buildTapIntent: → LauncherActivity (no URL)")
            }
        }
    }

    private fun downloadBitmap(url: String): Bitmap? = runCatching {
        // Bounded connect/read timeout so a slow CDN never blocks the whole
        // notification for more than ~4 seconds — the heads-up is already up.
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 4_000
        conn.readTimeout = 4_000
        conn.instanceFollowRedirects = true
        conn.inputStream.use(BitmapFactory::decodeStream)
    }.onFailure { Log.w(TAG, "image download failed: ${it.message}") }.getOrNull()

    private fun firstNonEmpty(vararg candidates: String?): String? =
        candidates.firstOrNull { !it.isNullOrBlank() }

    private fun extractLink(data: Map<String, String>): String? {
        for (k in URL_KEYS) {
            val v = data[k]?.trim()
            if (!v.isNullOrEmpty()) return v
        }
        return null
    }

    /**
     * Reads the one-shot push URL from any launch/new intent.
     * Checks our own extra first, then the raw FCM data keys that survive as
     * intent extras when the system tray shows the banner (notification-payload
     * pushes), and finally the intent's data URI.
     */
    companion object {
        const val TAG = "ThunderFCM"
        const val EXTRA_PUSH_URL = "tc.extra.push_url"

        // Comprehensive list of key names used by different CRM platforms.
        // Order matters — first non-blank hit wins.
        val URL_KEYS = listOf(
            "href", "target_url", "url", "webviewUrl",
            "deep_link", "u", "link", "deeplink",
            "action_url", "uri", "webview_url", "push_url",
        )

        fun oneShotFrom(intent: android.content.Intent?): String? {
            if (intent == null) return null
            intent.getStringExtra(EXTRA_PUSH_URL)
                ?.takeIf { it.isNotBlank() }?.let { return it }
            for (k in URL_KEYS) {
                intent.getStringExtra(k)?.trim()
                    ?.takeIf { it.isNotEmpty() }?.let { return it }
            }
            val dataUri = intent.data?.toString()?.trim()
            if (!dataUri.isNullOrEmpty() &&
                (dataUri.startsWith("http://") || dataUri.startsWith("https://"))
            ) return dataUri
            return null
        }
    }
}
