package com.thundercrest.thundercrestgame.volt.pipe

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.thundercrest.thundercrestgame.volt.mark.VoltId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Connectivity gateway.
 *
 * Two questions that look alike and are not:
 *
 *  - [hasAnyAdapter] — is there an active network claiming internet
 *    capability? Cheap, synchronous, safe to ask on the first frame.
 *  - [isReachable] — does a TCP handshake actually complete somewhere
 *    out there? Costs a round trip, and it is the only one that can be
 *    trusted once a VPN or a captive portal is in the picture.
 *
 * The gap between them is not academic. With a tunnel up, the active
 * network for this uid *is* the tunnel; switching Wi-Fi off underneath
 * leaves it exactly as it was — connected, internet-capable, validated —
 * with nothing behind it. No `onLost` is delivered for a network that
 * never went away, so a page simply stops loading and every capability
 * check in the app keeps saying everything is fine.
 */
class VoltNet(context: Context) {

    private val app = context.applicationContext
    private val cm = app.getSystemService(ConnectivityManager::class.java)

    /**
     * Synchronous check used on the first frame of the router — must
     * return immediately. Catches "aeroplane mode, no data" installs
     * without paying for a round trip.
     */
    fun hasAnyAdapter(): Boolean {
        val active = cm?.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Real reachability. Two targets on two different ports, both raw
     * IPs on purpose: a hostname would need DNS, and DNS down a dead
     * tunnel is precisely the lookup that hangs. Either one answering
     * is enough — a network that blocks outbound 53 to public resolvers
     * is restrictive, not offline.
     */
    suspend fun isReachable(): Boolean {
        if (!hasAnyAdapter()) return false
        return withContext(Dispatchers.IO) {
            PROBES.any { (host, port) -> knock(host, port) }
        }
    }

    private fun knock(host: String, port: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(
                InetSocketAddress(host, port),
                VoltId.REACH_PROBE_TIMEOUT_MS,
            )
            true
        }
    } catch (_: Throwable) {
        false
    }

    /**
     * Live online/offline state of the default network.
     *
     * Offline transitions are debounced: a Wi-Fi to cellular handover
     * and a VPN reconnect both emit a brief loss that resolves on its
     * own, and reacting to those would bounce the user to the offline
     * screen for no reason. Coming back online is never debounced —
     * there is nothing to gain from delaying good news.
     */
    fun statusStream(): Flow<Status> = callbackFlow {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        var pendingDrop: Job? = null

        fun goOnline() {
            pendingDrop?.cancel()
            pendingDrop = null
            trySend(Status.Online)
        }

        fun scheduleDrop() {
            pendingDrop?.cancel()
            pendingDrop = scope.launch {
                delay(VoltId.OFFLINE_DEBOUNCE_MS)
                trySend(Status.Offline)
            }
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = goOnline()

            override fun onLost(network: Network) = scheduleDrop()

            override fun onUnavailable() = scheduleDrop()

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    goOnline()
                } else {
                    scheduleDrop()
                }
            }
        }

        trySend(if (hasAnyAdapter()) Status.Online else Status.Offline)
        val registered = runCatching { cm?.registerDefaultNetworkCallback(callback) }.isSuccess

        awaitClose {
            pendingDrop?.cancel()
            scope.cancel()
            if (registered) runCatching { cm?.unregisterNetworkCallback(callback) }
        }
    }.distinctUntilChanged()

    enum class Status { Online, Offline }

    private companion object {
        val PROBES = listOf("1.1.1.1" to 443, "8.8.8.8" to 53)
    }
}
