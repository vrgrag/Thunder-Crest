package com.thundercrest.thundercrestgame.volt.pipe

import android.content.Context
import android.util.Log
import com.thundercrest.thundercrestgame.BuildConfig
import com.thundercrest.thundercrestgame.volt.mark.VoltId
import com.thundercrest.thundercrestgame.volt.kind.VoltReply
import com.thundercrest.thundercrestgame.volt.guard.VoltUrlGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLDecoder
import java.util.Locale

/**
 * The single POST call that asks the backend "should this install see
 * the WebView or the native game?".
 *
 * See the gray-flow guide's "Config Request Contract"
 * — this class must mirror that spec exactly.
 *
 * Every exit path is explicit about whether the endpoint *answered*,
 * because the router persists a native verdict permanently and must
 * never do so on the strength of a timeout. A URL that comes back but
 * is not web content counts as an answer we then rejected ourselves,
 * so the question stays open for the next launch.
 */
class VoltAsk {

    /**
     * @param body flat JSON built by [VoltTrack.collectBody]
     *   plus the device-side fields.
     * @return [VoltReply] — never throws, always resolves.
     */
    suspend fun query(body: JSONObject): VoltReply = withContext(Dispatchers.IO) {
        val endpoint = VoltId.configEndpoint
        if (endpoint.isEmpty()) {
            return@withContext VoltReply.silence("no endpoint packed")
        }
        val serialized = body.toString()
        // Logged as one line with the field count in front, so a QA run
        // can tell "the backend said no" apart from "we asked it the
        // wrong question" without attaching a proxy.
        debug { "request (${body.length()} fields): $serialized" }

        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .post(serialized.toRequestBody(JSON))
            .build()

        val outcome = withTimeoutOrNull(VoltId.GATE_TIMEOUT_MS) {
            runCatching {
                VoltAgent.http.newCall(request).execute().use { resp ->
                    val payload = resp.body?.string().orEmpty()
                    debug { "response ${resp.code}: $payload" }
                    interpret(resp.code, payload).also {
                        debug { "verdict: allowed=${it.allowed} note=${it.note}" }
                    }
                }
            }.getOrElse { VoltReply.silence("io: ${it.message}") }
        }
        outcome ?: VoltReply.silence("timeout")
    }

    /**
     * Device-side fields from the config contract. Both `push_token`
     * and `firebase_project_id` are written together or omitted
     * together — never as empty strings.
     *
     * [forcedToken] wins over the vault and over a live fetch; that is
     * the path [VoltPushService.onNewToken] uses so a token that just
     * arrived is in the body even if the vault write has not flushed.
     */
    suspend fun decorate(
        body: JSONObject,
        ctx: Context,
        tracker: VoltTrack,
        forcedToken: String? = null,
    ) {
        body.put("af_id", tracker.deviceId().orEmpty())
        body.put("bundle_id", VoltId.PACKAGE_ID)
        body.put("os", "Android")
        body.put("store_id", VoltId.PACKAGE_ID)
        body.put("locale", localeTag())

        val vault = VoltStore(ctx)
        val token = forcedToken?.takeIf { it.isNotEmpty() }
            ?: vault.readPushToken()
            ?: withTimeoutOrNull(VoltId.TOKEN_WAIT_MS) { VoltPush.fetchToken() }
                ?.also(vault::writePushToken)
        val project = VoltId.messagingProject
        if (!token.isNullOrEmpty() && project.isNotEmpty()) {
            body.put("push_token", token)
            body.put("firebase_project_id", project)
        }

        applyQa(body)
    }

    /**
     * 404 is the backend's ordinary way of saying "not one of ours" —
     * a real verdict, not an error. Any other non-2xx is the server
     * speaking too, so it also settles the question.
     */
    private fun interpret(code: Int, payload: String): VoltReply {
        if (code !in 200..299) return VoltReply.verdict("http $code")
        if (payload.isBlank()) return VoltReply.verdict("empty body")

        val parsed = VoltReply.parse(payload)
        if (!parsed.allowed || !parsed.hasLink) return VoltReply.verdict(parsed.note ?: "ok=false")

        if (!VoltUrlGate.accepts(parsed.link)) {
            debug { "endpoint returned a non-web destination — ignoring it" }
            return VoltReply.verdict("destination rejected by url guard")
        }
        return parsed
    }

    private fun applyQa(body: JSONObject) {
        if (!BuildConfig.DEBUG) return
        val status = BuildConfig.FORCE_AF_STATUS
        if (status.isNotEmpty()) body.put("af_status", status)
        var injected = 0
        for (pair in BuildConfig.FORCE_PARAMS.split('&')) {
            val cut = pair.indexOf('=')
            if (cut <= 0) continue
            val key = pair.substring(0, cut).trim()
            if (key.isEmpty()) continue
            val value = runCatching {
                URLDecoder.decode(pair.substring(cut + 1), "UTF-8")
            }.getOrNull() ?: continue
            body.put(key, value)
            injected++
        }
        if (status.isNotEmpty() || injected > 0) {
            debug { "QA overrides: af_status=$status, $injected campaign fields" }
        }
    }

    private fun localeTag(): String {
        val tag = Locale.getDefault().toLanguageTag()
        if (tag.isEmpty() || tag == "und") return "en"
        return tag.replace('-', '_')
    }

    private inline fun debug(message: () -> String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message())
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val TAG = "VoltAsk"
    }
}
