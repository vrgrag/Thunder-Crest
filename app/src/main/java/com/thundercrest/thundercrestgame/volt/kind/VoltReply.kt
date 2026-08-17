package com.thundercrest.thundercrestgame.volt.kind

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Raw decoded body of the config endpoint.
 *
 * Wire format from the backend is `{ ok, url, expires, message }`.
 * We tolerate `expires` arriving as either an integer or a numeric
 * string — some backends stringify long values.
 */
@Serializable
data class VoltRawReply(
    val ok: Boolean = false,
    val url: String? = null,
    val message: String? = null,
    val expires: JsonElement? = null,
)

/**
 * Parsed reply from [com.thundercrest.thundercrestgame.volt.pipe.VoltAsk].
 *
 * [answered] is the field the router actually turns on, and it is a
 * different question from [allowed]. "The server said this install gets
 * the game" and "we never reached the server" both come back with
 * `allowed = false`, but only the first is a verdict worth writing down
 * — a first launch on a flaky train connection must not lock the user
 * into the native game for the lifetime of the install because a socket
 * timed out once. Anything that never produced an HTTP status leaves
 * [answered] false and the decision open for the next launch.
 */
data class VoltReply(
    val allowed: Boolean,
    val link: String? = null,
    val note: String? = null,
    val ttl: Long? = null,
    val answered: Boolean = true,
) {
    val hasLink: Boolean get() = !link.isNullOrEmpty()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(body: String): VoltReply = try {
            val raw = json.decodeFromString(VoltRawReply.serializer(), body)
            val ttl = raw.expires?.let { e ->
                when (val p = runCatching { e.jsonPrimitive }.getOrNull()) {
                    null -> null
                    else -> p.longOrNull ?: p.content.toLongOrNull()
                }
            }
            VoltReply(
                allowed = raw.ok,
                link = raw.url,
                note = raw.message,
                ttl = ttl,
                answered = true,
            )
        } catch (t: Throwable) {
            // A body we cannot read still came from a server that
            // responded, so the verdict counts.
            verdict("parse: ${t.message}")
        }

        /** The endpoint responded and ruled this install native. */
        fun verdict(note: String): VoltReply =
            VoltReply(allowed = false, note = note, answered = true)

        /** Nothing was reached — no verdict, nothing to persist. */
        fun silence(note: String): VoltReply =
            VoltReply(allowed = false, note = note, answered = false)
    }
}
