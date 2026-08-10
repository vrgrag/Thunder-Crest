package com.thundercrest.thundercrestgame

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Hosts the Privacy Policy and Support pages in a WebView. These are the only
 * parts of the app that need connectivity; a friendly offline message is shown
 * when there is no network so the rest of the game stays usable offline.
 */
class WebActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
        const val PRIVACY = "https://thunndercrest.com/privacy-policy.html"
        const val SUPPORT = "https://thunndercrest.com/support.html"

        fun open(context: Context, url: String) {
            val title = if (url == PRIVACY) "Privacy Policy" else "Support"
            context.startActivity(
                Intent(context, WebActivity::class.java)
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_TITLE, title)
            )
        }
    }

    private lateinit var webView: WebView
    private lateinit var offline: TextView
    private lateinit var progress: ProgressBar

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL) ?: PRIVACY
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Thunder Crest"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#071022"))
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }

        // Simple gold top bar with a back button.
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0C1E44"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val back = TextView(this).apply {
            text = "\u2039  Back"
            setTextColor(Color.parseColor("#FFE9A8"))
            textSize = 18f
            setOnClickListener { finish() }
        }
        val titleView = TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(dp(16), 0, 0, 0)
        }
        bar.addView(back)
        bar.addView(titleView)
        root.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))

        val content = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            setBackgroundColor(Color.WHITE)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    this@WebActivity.progress.visibility = View.GONE
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                    error: android.webkit.WebResourceError?,
                ) {
                    showOffline()
                }
            }
        }
        progress = ProgressBar(this).apply {
            val lp = FrameLayout.LayoutParams(dp(48), dp(48))
            lp.gravity = Gravity.CENTER
            layoutParams = lp
        }
        offline = TextView(this).apply {
            text = "You are offline.\n\nThis page requires an internet connection.\nThe game itself works fully offline."
            setTextColor(Color.parseColor("#F3ECD8"))
            textSize = 16f
            gravity = Gravity.CENTER
            visibility = View.GONE
            val lp = FrameLayout.LayoutParams(MATCH, MATCH)
            lp.gravity = Gravity.CENTER
            layoutParams = lp
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }

        content.addView(webView, FrameLayout.LayoutParams(MATCH, MATCH))
        content.addView(progress)
        content.addView(offline)
        root.addView(content)

        setContentView(root)

        if (isOnline()) {
            webView.loadUrl(url)
        } else {
            showOffline()
        }
    }

    private fun showOffline() {
        progress.visibility = View.GONE
        webView.visibility = View.GONE
        offline.visibility = View.VISIBLE
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
