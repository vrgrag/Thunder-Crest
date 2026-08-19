package com.thundercrest.thundercrestgame.gray

import android.content.Context
import android.os.Build
import com.thundercrest.thundercrestgame.BuildConfig
import org.json.JSONObject
import java.util.Locale

/**
 * Builds the JSON body for the config endpoint by merging, in order of
 * priority:
 *
 *   1. Conversion data from AppsFlyer.
 *   2. AppsFlyer UDL (deep linking) data — but keys already present in the
 *      conversion payload win (per the "first received wins" rule in the spec).
 *   3. Device / app fields the client is responsible for adding
 *      (`af_id`, `bundle_id`, `os`, `store_id`, `locale`, `push_token`,
 *      `firebase_project_id`).
 *   4. Optional debug overrides from `ridge.properties`.
 *
 * If [pushToken] or [firebaseProjectId] are null we omit them, as the spec
 * requires when Firebase Messaging failed to initialise.
 */
object ConfigBody {
    fun build(
        ctx: Context,
        conversionData: Map<String, Any?>,
        udlData: Map<String, Any?> = emptyMap(),
        appsFlyerUid: String?,
        pushToken: String?,
        firebaseProjectId: String?,
        overrides: RidgeOverrides = RidgeOverrides(),
    ): JSONObject {
        val json = JSONObject()

        conversionData.forEach { (k, v) -> json.putSafe(k, v) }

        udlData.forEach { (k, v) ->
            if (!json.has(k)) json.putSafe(k, v)
        }

        if (!appsFlyerUid.isNullOrEmpty()) json.put("af_id", appsFlyerUid)
        json.put("bundle_id", ctx.packageName)
        json.put("os", "Android")
        json.put("store_id", ctx.packageName)
        json.put("locale", currentLocaleTag())

        if (!pushToken.isNullOrEmpty()) json.put("push_token", pushToken)
        if (!firebaseProjectId.isNullOrEmpty()) json.put(Ember.fbProjectIdKey, firebaseProjectId)

        // Debug overrides. Only ever populated on debug builds — see [RidgeOverrides].
        overrides.forceAfStatus?.let { json.put(Ember.afStatusKey, it) }
        overrides.forceParams.forEach { (k, v) -> json.put(k, v) }

        return json
    }

    private fun JSONObject.putSafe(key: String, value: Any?) {
        if (value == null) put(key, JSONObject.NULL) else put(key, value)
    }

    private fun currentLocaleTag(): String {
        val locale: Locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Locale.getDefault(Locale.Category.DISPLAY)
        } else {
            Locale.getDefault()
        }
        val tag = locale.toLanguageTag()
        // Fall back to the plain language when the tag is empty or "und".
        return if (tag.isNullOrBlank() || tag == "und") locale.language.ifBlank { "en" } else tag
    }

    /** Kept for logging / debugging. */
    fun endpoint(): String = BuildConfig.CONFIG_ENDPOINT
}
