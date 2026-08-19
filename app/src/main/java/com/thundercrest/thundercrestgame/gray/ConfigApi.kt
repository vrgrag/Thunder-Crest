package com.thundercrest.thundercrestgame.gray

import android.util.Log
import com.thundercrest.thundercrestgame.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Talks to the config endpoint described in the task spec.
 *
 * Contract:
 *   - Success  → HTTP 200 + `{ ok: true, url: <String>, expires: <Timestamp> }`.
 *   - Failure  → any other code, or `ok:false` — treated as a "negative" reply,
 *                which means the shell must fall back to the native game.
 */
class ConfigApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * POST the fully-assembled body (conversion data + UDL + device fields +
     * push token) exactly as-is — the endpoint expects every conversion field
     * to arrive untouched.
     */
    suspend fun fetch(body: JSONObject): ConfigResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(BuildConfig.CONFIG_ENDPOINT)
            .addHeader("accept", "application/json")
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()

        if (BuildConfig.DEBUG) {
            Log.i(TAG, "POST ${BuildConfig.CONFIG_ENDPOINT}")
            Log.i(TAG, "  body: ${body.toString().take(2000)}")
        }
        try {
            client.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (BuildConfig.DEBUG) {
                    Log.i(TAG, "  ← HTTP ${resp.code}")
                    Log.i(TAG, "  ← body: ${raw.take(2000)}")
                }
                val json = runCatching { JSONObject(raw) }.getOrNull()
                if (!resp.isSuccessful || json == null) {
                    return@use ConfigResponse(
                        ok = false,
                        url = null,
                        expires = null,
                        message = json?.optString("message"),
                    )
                }
                ConfigResponse(
                    ok = json.optBoolean("ok", false),
                    url = json.optString("url").ifBlank { null },
                    expires = json.optLong("expires").takeIf { it > 0 },
                    message = json.optString("message").ifBlank { null },
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "config request failed: ${t.javaClass.simpleName}: ${t.message}")
            ConfigResponse(false, null, null, t.message)
        }
    }

    private companion object {
        const val TAG = "ConfigApi"
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
