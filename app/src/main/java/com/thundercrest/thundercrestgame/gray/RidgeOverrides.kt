package com.thundercrest.thundercrestgame.gray

import android.content.Context
import com.thundercrest.thundercrestgame.BuildConfig
import java.util.Properties

/**
 * Debug-only overrides loaded from `ridge.properties` at the project root. Kept
 * out of git so QA can flip individual switches (force WebView with a probe
 * link, override AppsFlyer status, inject conversion parameters) without
 * building a special variant.
 *
 * The file is copied into `assets/ridge.properties` at build time only for debug
 * builds; in release builds all overrides return no-ops.
 */
data class RidgeOverrides(
    val probeLink: String? = null,
    val forceAfStatus: String? = null,
    val forceParams: Map<String, String> = emptyMap(),
    val stickyVerdict: Boolean = true,
    /** Always show the push-promo screen in debug builds, ignoring cooldown and granted state. */
    val forceShowPushPrompt: Boolean = false,
) {
    companion object {
        fun load(ctx: Context): RidgeOverrides {
            if (!BuildConfig.DEBUG) return RidgeOverrides()
            return runCatching {
                val props = Properties()
                ctx.assets.open("ridge.properties").use(props::load)
                RidgeOverrides(
                    probeLink = props.getProperty("probeLink").orEmptyToNull(),
                    forceAfStatus = props.getProperty("forceAfStatus").orEmptyToNull(),
                    forceParams = parseForceParams(props.getProperty("forceParams")),
                    stickyVerdict = props.getProperty("stickyVerdict")?.toBoolean() ?: true,
                    forceShowPushPrompt = props.getProperty("forceShowPushPrompt")?.toBoolean() ?: false,
                )
            }.getOrDefault(RidgeOverrides())
        }

        private fun String?.orEmptyToNull(): String? =
            this?.trim().takeUnless { it.isNullOrEmpty() }

        private fun parseForceParams(raw: String?): Map<String, String> {
            if (raw.isNullOrBlank()) return emptyMap()
            return raw.split("&").mapNotNull { chunk ->
                val eq = chunk.indexOf('=')
                if (eq <= 0) null else chunk.substring(0, eq) to chunk.substring(eq + 1)
            }.toMap()
        }
    }
}
