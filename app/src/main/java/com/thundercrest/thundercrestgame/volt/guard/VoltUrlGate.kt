package com.thundercrest.thundercrestgame.volt.guard

import android.net.Uri

/**
 * Shape check and normalisation for every URL that enters the flow from
 * outside — the config endpoint's answer, a push payload, a deep link.
 *
 * Deliberately *not* an allowlist. Partner domains rotate constantly
 * and a hardcoded list would silently blackhole a live campaign; the
 * only thing worth asserting here is that the string is web content
 * the WebView can actually take. Anything else — `intent://`,
 * `market://`, a wallet scheme, plain garbage — is rejected so it can
 * never reach `loadUrl` and produce ERR_UNKNOWN_URL_SCHEME on a blank
 * page the user cannot leave.
 */
object VoltUrlGate {

    private val WEB = setOf("http", "https")

    fun accepts(raw: String?): Boolean {
        val candidate = raw?.trim().orEmpty()
        if (candidate.isEmpty()) return false
        val parsed = runCatching { Uri.parse(candidate) }.getOrNull() ?: return false
        val scheme = parsed.scheme?.lowercase() ?: return false
        if (scheme !in WEB) return false
        return !parsed.host.isNullOrBlank()
    }

    /**
     * The URL ready for `loadUrl`, or `null` when it is not web content.
     *
     * Cleartext is promoted to TLS rather than passed through.
     * `networkSecurityConfig` refuses plain HTTP app-wide, so an
     * `http://` push link used to die as `ERR_CLEARTEXT_NOT_PERMITTED`
     * on an error page with no way back — a dead end for a user who
     * just tapped a notification. Partners put these links behind
     * CDNs that serve both schemes, so upgrading recovers the campaign
     * without the security regression of whitelisting the domain
     * (pitfalls §15).
     *
     * Every entry point routes through here, so the promotion applies
     * to config answers, push payloads and deep links alike.
     */
    fun sanitize(raw: String?): String? {
        val candidate = raw?.trim()
        if (!accepts(candidate)) return null
        val parsed = Uri.parse(candidate)
        if (!parsed.scheme.equals("http", ignoreCase = true)) return candidate

        val upgraded = parsed.buildUpon().scheme("https")
        // An explicit :80 would survive the scheme swap and point TLS at
        // the cleartext port.
        if (parsed.port == 80) {
            val host = parsed.host.orEmpty()
            val userInfo = parsed.encodedUserInfo
            upgraded.encodedAuthority(if (userInfo.isNullOrEmpty()) host else "$userInfo@$host")
        }
        return upgraded.build().toString()
    }
}
