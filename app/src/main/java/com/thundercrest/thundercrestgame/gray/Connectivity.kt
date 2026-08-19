package com.thundercrest.thundercrestgame.gray

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * Connectivity helpers. `isOnline` is the point-in-time snapshot; `Watcher`
 * lets a WebView (or any long-lived screen) react as soon as the OS reports
 * the network went away, so we can fall back to the No-Wi-Fi screen from
 * whatever page the user was on.
 */
object Connectivity {

    fun isOnline(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Registers a lightweight callback that fires when the default network
     *  is fully lost. VPN flickers and captive-portal transitions can send
     *  spurious losses; the caller should debounce before showing offline. */
    fun watch(ctx: Context, onLost: () -> Unit, onAvailable: () -> Unit): Watcher {
        val cm = ctx.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) = onLost()
            override fun onAvailable(network: Network) = onAvailable()
        }
        cm.registerNetworkCallback(request, callback)
        return Watcher(cm, callback)
    }

    class Watcher internal constructor(
        private val cm: ConnectivityManager,
        private val cb: ConnectivityManager.NetworkCallback,
    ) {
        fun stop() = runCatching { cm.unregisterNetworkCallback(cb) }
    }
}
