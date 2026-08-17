package com.thundercrest.thundercrestgame.volt.mark

import com.thundercrest.thundercrestgame.volt.cipher.VoltPacked

/**
 * Single access point for app-wide constants used by the gray flow.
 *
 * Identity values live as plain strings — they are unavoidably in the
 * APK anyway (package name shows up in the manifest). Endpoints and
 * SDK keys resolve lazily through [VoltPacked] so plaintext never
 * lands in the binary.
 *
 * [FINGERPRINT] Do NOT reuse [PACKAGE_ID] / [MARKET_ID] /
 * [DISPLAY_NAME] across projects — even suffix collisions cluster.
 */
object VoltId {

    // ─────────────────────────────────────────────────────────
    // Identity — must match app/build.gradle.kts + AndroidManifest.xml
    // ─────────────────────────────────────────────────────────
    const val PACKAGE_ID: String = "com.thundercrest.thundercrestgame"
    const val MARKET_ID: String = "com.thundercrest.thundercrestgame"
    const val DISPLAY_NAME: String = "Thunder Crest"

    // ─────────────────────────────────────────────────────────
    // Resolved endpoints / credentials (decoded lazily)
    // ─────────────────────────────────────────────────────────
    val configEndpoint: String get() = VoltPacked.configEndpoint()
    val attributionKey: String get() = VoltPacked.attributionKey()
    val messagingProject: String get() = VoltPacked.messagingProject()

    // ─────────────────────────────────────────────────────────
    // Timing knobs (seconds)
    // ─────────────────────────────────────────────────────────

    /** Push-invite cooldown after a Skip tap — 3 days (TZ). */
    const val INVITE_COOLDOWN_SECONDS: Long = 3L * 24 * 60 * 60

    /** Per-target timeout of the TCP reachability probe. */
    const val REACH_PROBE_TIMEOUT_MS: Int = 2_400

    /** Debounce for the connectivity-drop stream outside boot. */
    const val OFFLINE_DEBOUNCE_MS: Long = 700L

    /**
     * How long the router waits for a connection to appear before it
     * gives up and shows the offline screen. Covers the very common
     * case of the radio still associating while the app is launching.
     */
    const val CONNECT_GRACE_MS: Long = 3_200L

    /** Attribution await budget on first launch. */
    const val ATTRIBUTION_TIMEOUT_MS: Long = 28_000L

    /** Attribution await budget on returning launches. */
    const val ATTRIBUTION_TIMEOUT_MS_RESUME: Long = 10_000L

    /** Shorter budget after a retrace — the SDK is already warm. */
    const val ATTRIBUTION_TIMEOUT_MS_RETRACE: Long = 8_000L

    /** Deep link await budget. */
    const val DEEP_LINK_TIMEOUT_MS: Long = 5_000L

    /** Config endpoint timeout. */
    const val GATE_TIMEOUT_MS: Long = 15_000L

    /**
     * How long the router waits for an FCM token on a live network.
     * After an offline-first install the Play Services handshake often
     * needs more than a couple of seconds; a short budget is how the
     * first config POST goes out without `push_token` and the backend
     * never learns how to reach this device.
     */
    const val TOKEN_WAIT_MS: Long = 8_000L

    // ─────────────────────────────────────────────────────────
    // WebView shell knobs
    // ─────────────────────────────────────────────────────────

    /**
     * Interval of the reachability heartbeat while the shell is up.
     * The OS only reports a network that goes *away*; a VPN over a
     * switched-off Wi-Fi keeps every capability it had and never
     * fires a callback, so the shell has to ask the wire itself.
     */
    const val HEARTBEAT_MS: Long = 5_500L

    /**
     * How long a page must hold the screen before the entry redirect
     * chain counts as finished. Long enough for the next hop's script
     * or meta-refresh to fire, short enough to read as the tail of the
     * loading screen.
     */
    const val CHAIN_SETTLE_MS: Long = 600L

    /** Hard ceiling on the loading cover, finished page or not. */
    const val COVER_MAX_MS: Long = 20_000L

    /** Attempts to resume an over-long affiliate redirect chain. */
    const val REDIRECT_RETRY_MAX: Int = 5

    /** Renderer crash recoveries per shell before giving up. */
    const val RENDERER_RECOVERY_MAX: Int = 3

    /** Delay before re-injecting the safe-area CSS after first paint. */
    const val SAFE_AREA_DELAY_MS: Long = 850L
}
