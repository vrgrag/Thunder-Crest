package com.thundercrest.thundercrestgame.volt.pipe

import android.os.Build
import com.thundercrest.thundercrestgame.volt.cipher.VoltPacked
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Forged device User-Agent used by BOTH the OkHttp client that hits
 * the config endpoint AND the WebView (`WebSettings.userAgentString`).
 * A default OkHttp UA would immediately flag the shell as a native
 * client instead of a browser.
 *
 * The string is a plain Chrome-on-Android UA and nothing else. No
 * `appid/` or `appname/` suffix: those tokens appear in no real browser,
 * so they mark every request as coming from a wrapped app — which is the
 * opposite of what forging the UA is for.
 *
 * See the gray-flow User-Agent guide for the canonical shape.
 */
object VoltAgent {

    /** Lazily built once — device info doesn't change at runtime. */
    val value: String by lazy(::build)

    /** Shared OkHttp client that stamps the forged UA on every send. */
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request: Request = chain.request()
                chain.proceed(
                    if (request.header("User-Agent") == null) {
                        request.newBuilder().header("User-Agent", value).build()
                    } else request,
                )
            }
            .build()
    }

    private fun build(): String {
        val chrome = VoltPacked.chromeVersion().ifEmpty { "149.0.0.0" }
        val webkit = VoltPacked.webkitVersion().ifEmpty { "537.36" }
        val release = Build.VERSION.RELEASE ?: "13"
        val manufacturer = (Build.MANUFACTURER ?: "Google")
            .replaceFirstChar { it.uppercaseChar() }
        val model = Build.MODEL ?: "Pixel 7"
        val buildTag = (Build.ID ?: "TQ3A").take(20)

        return "Mozilla/5.0 (Linux; Android $release; $manufacturer $model Build/$buildTag) " +
            "AppleWebKit/$webkit (KHTML, like Gecko) " +
            "Chrome/$chrome Mobile Safari/$webkit"
    }
}
