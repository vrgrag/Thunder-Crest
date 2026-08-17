package com.thundercrest.thundercrestgame.volt

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.thundercrest.thundercrestgame.BuildConfig
import com.thundercrest.thundercrestgame.volt.pipe.VoltTrack

/**
 * Process entry point.
 *
 * The only job here is to introduce the two SDKs to the process before
 * any Activity exists. AppsFlyer in particular registers
 * `ActivityLifecycleCallbacks` during `init` and uses them to notice the
 * app came to the foreground; wiring it up while an Activity is already
 * on screen means the SDK misses this launch entirely and the install
 * sits queued until the next one. Attribution silently reads as organic
 * for that whole first session, which is the single most expensive
 * failure mode in this flow.
 *
 * Nothing here touches the network. [VoltTrack.prime] only
 * registers callbacks; the call that actually speaks to AppsFlyer is
 * [VoltTrack.ignite], and the router fires it only once it has
 * confirmed a live connection.
 */
class VoltBoot : Application() {

    /** Shared across the router, the shell and the messaging service. */
    lateinit var tracker: VoltTrack
        private set

    override fun onCreate() {
        super.onCreate()

        // Best-effort: a missing google-services.json must not stop the
        // config POST from happening. The flow degrades to "no push
        // token in the body", never to a crash on launch.
        runCatching { FirebaseApp.initializeApp(this) }
            .onFailure { warn("Firebase unavailable: ${it.message}") }

        tracker = VoltTrack(this)
        tracker.prime()
    }

    private fun warn(message: String) {
        if (BuildConfig.DEBUG) Log.w(TAG, message)
    }

    private companion object {
        const val TAG = "VoltBoot"
    }
}
