package com.thundercrest.thundercrestgame.gray

/**
 * Which shell the app should render.
 *
 *  - [UNSET] — decision not made yet (first launch, no successful reply yet).
 *  - [WEBVIEW] — config returned `ok=true` at least once, so we keep opening the WebView.
 *  - [GAME] — config replied negatively, we permanently fall back to the native game.
 */
enum class AppMode { UNSET, WEBVIEW, GAME }

/**
 * Parsed response from the config endpoint.
 */
data class ConfigResponse(
    val ok: Boolean,
    val url: String?,
    val expires: Long?,
    val message: String?,
)

/**
 * Saved WebView link state — the last URL the config endpoint gave us.
 */
data class SavedLink(
    val url: String,
    val expires: Long,
) {
    fun isExpired(nowSeconds: Long = System.currentTimeMillis() / 1000): Boolean =
        expires in 1..nowSeconds
}
