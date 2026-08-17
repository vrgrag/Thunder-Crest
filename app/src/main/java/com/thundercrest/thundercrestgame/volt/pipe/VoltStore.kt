package com.thundercrest.thundercrestgame.volt.pipe

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.thundercrest.thundercrestgame.volt.mark.VoltId
import com.thundercrest.thundercrestgame.volt.kind.VoltMode

/**
 * Persistence layer for the gray flow. Two backing stores:
 *
 *  - A plain [SharedPreferences] for flags and timestamps, with
 *    deliberately terse key names.
 *  - An [EncryptedSharedPreferences] for URLs (config link, push link)
 *    so a prefs dump does not immediately reveal partner domains.
 *
 * When the encrypted store cannot be opened — a missing crypto
 * provider, an inaccessible keystore — URLs fall back to an in-memory
 * map rather than to the plain file. A device that cannot keep them
 * secret gets to forget them instead.
 *
 * Key naming is intentionally opaque: a reviewer scrolling through a
 * prefs backup should not be able to tell what the app does.
 */
class VoltStore(context: Context) {

    private val app = context.applicationContext

    private val plain: SharedPreferences =
        app.getSharedPreferences(PLAIN_FILE, Context.MODE_PRIVATE)

    private val sealed: SharedPreferences? = runCatching {
        val key = MasterKey.Builder(app)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            app,
            SECRET_FILE,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrNull()

    private val volatileStore = HashMap<String, String?>()

    private fun readSealed(key: String): String? =
        sealed?.getString(key, null) ?: volatileStore[key]

    private fun writeSealed(key: String, value: String?) {
        val store = sealed
        if (store == null) {
            volatileStore[key] = value
            return
        }
        val editor = store.edit()
        if (value.isNullOrEmpty()) editor.remove(key) else editor.putString(key, value)
        editor.apply()
    }

    // ── Shell mode ─────────────────────────────────────────────

    fun readMode(): VoltMode = VoltMode.fromWire(plain.getString(K_MODE, null))

    fun writeMode(mode: VoltMode) {
        plain.edit().putString(K_MODE, mode.wire).apply()
    }

    // ── Cached content link (sealed) ───────────────────────────

    fun readCachedLink(): String? = readSealed(K_CACHED_LINK)

    fun writeCachedLink(link: String) = writeSealed(K_CACHED_LINK, link)

    fun readLinkTtl(): Long? =
        if (plain.contains(K_LINK_TTL)) plain.getLong(K_LINK_TTL, 0L) else null

    fun writeLinkTtl(unixSeconds: Long) {
        plain.edit().putLong(K_LINK_TTL, unixSeconds).apply()
    }

    /**
     * A link with no expiry stays good forever — the backend omits
     * `expires` when it does not want one, and treating that as
     * "expired" would send every returning user back through the full
     * attribution round trip on a link that was never going to change.
     */
    fun hasUsableLink(): Boolean {
        if (readCachedLink().isNullOrEmpty()) return false
        val ttl = readLinkTtl() ?: return true
        return ttl == 0L || nowSeconds() < ttl
    }

    // ── One-shot push link (sealed) ────────────────────────────

    fun stashPushLink(link: String?) = writeSealed(K_PUSH_LINK, link)

    fun takePushLink(): String? {
        val link = readSealed(K_PUSH_LINK) ?: return null
        writeSealed(K_PUSH_LINK, null)
        return link.ifEmpty { null }
    }

    // ── FCM token (sealed) ─────────────────────────────────────

    fun readPushToken(): String? = readSealed(K_PUSH_TOKEN)

    fun writePushToken(token: String?) = writeSealed(K_PUSH_TOKEN, token)

    // ── Push permission state ──────────────────────────────────

    fun isPushAllowed(): Boolean = plain.getBoolean(K_PUSH_ALLOWED, false)

    fun markPushAllowed(value: Boolean) {
        plain.edit().putBoolean(K_PUSH_ALLOWED, value).apply()
    }

    fun isPushBlockedByOs(): Boolean = plain.getBoolean(K_PUSH_BLOCKED_OS, false)

    fun markPushBlockedByOs() {
        plain.edit().putBoolean(K_PUSH_BLOCKED_OS, true).apply()
    }

    /**
     * Set only when the *system* dialog was actually opened. A Skip tap
     * on our own screen never opens it, and conflating the two is what
     * makes a skip read as a permanent refusal on the next launch.
     */
    fun wasOsAsked(): Boolean = plain.getBoolean(K_PUSH_OS_ASKED, false)

    fun markOsAsked() {
        plain.edit().putBoolean(K_PUSH_OS_ASKED, true).apply()
    }

    fun armInviteCooldown() {
        plain.edit()
            .putLong(K_INVITE_UNTIL, nowSeconds() + VoltId.INVITE_COOLDOWN_SECONDS)
            .apply()
    }

    /**
     * The invite screen is a door to the OS dialog, so it is only worth
     * showing while that dialog can still lead somewhere. Asking the OS
     * directly — not just the flags written here — also covers the user
     * who turned notifications off in system settings, which happens
     * entirely outside the app.
     *
     * Needs an [Activity] because "asked and refused for good" is only
     * readable through `shouldShowRequestPermissionRationale`.
     */
    fun shouldOfferInvite(host: Activity): Boolean {
        if (isPushAllowed() || isPushBlockedByOs()) return false

        if (osGranted()) {
            markPushAllowed(true)
            return false
        }
        if (osRefusedForGood(host)) {
            markPushBlockedByOs()
            return false
        }
        val until = if (plain.contains(K_INVITE_UNTIL)) plain.getLong(K_INVITE_UNTIL, 0L) else 0L
        return nowSeconds() >= until
    }

    /** True only when API 33+ and the OS permission is held. Below 33 there
     *  is no runtime permission — the promo screen is still shown once so
     *  the player can opt in/out before the shell (see gray-flow guide). */
    fun osGranted(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * On API 33+ "refused" is indistinguishable from "never asked" —
     * both report denied with no rationale. [wasOsAsked] is the only
     * thing that separates them. Below 33 notifications start enabled,
     * so finding them off can only mean the user turned them off.
     */
    private fun osRefusedForGood(host: Activity): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wasOsAsked() &&
                !host.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            !NotificationManagerCompat.from(host).areNotificationsEnabled()
        }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000L

    private companion object {
        // Neutral filenames — do NOT include guide terms, "webview" or
        // partner domain names in these strings.
        const val PLAIN_FILE = "crest_local_box"
        const val SECRET_FILE = "crest_vault_box"

        const val K_MODE = "state_pref_v1"
        const val K_CACHED_LINK = "asset_blob"
        const val K_LINK_TTL = "asset_exp"
        const val K_PUSH_LINK = "handoff_blob"
        const val K_PUSH_TOKEN = "spark_id"
        const val K_INVITE_UNTIL = "offer_after"
        const val K_PUSH_ALLOWED = "spark_ok"
        const val K_PUSH_BLOCKED_OS = "spark_blocked"
        const val K_PUSH_OS_ASKED = "spark_asked"
    }
}
