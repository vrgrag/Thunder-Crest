package com.thundercrest.thundercrestgame.volt.pipe

import java.util.concurrent.atomic.AtomicReference

/**
 * In-process hand-off for a push URL that arrives while the WebView
 * shell is already alive.
 *
 * Without this, a notification tap goes through the launcher, which
 * restarts the router, which re-runs the whole decision and finally
 * re-creates a shell that was never gone — the user watches their page
 * disappear and a loading screen come back for a link the live WebView
 * could have taken instantly.
 *
 * Nothing here is ever persisted. A warm URL is a fact about *this*
 * moment; writing it to the vault would make it replay on the next cold
 * start, long after the user stopped caring about it.
 */
object VoltHandoff {

    /** Installed by the shell between `onStart` and `onStop`. */
    private val sink = AtomicReference<((String) -> Unit)?>(null)

    /**
     * Set while the shell Activity exists. A URL can be parked for a
     * shell that is alive but currently between `onStop` and `onStart`.
     */
    @Volatile
    var shellAlive: Boolean = false

    private val parked = AtomicReference<String?>(null)

    fun attach(sinkFn: (String) -> Unit) {
        sink.set(sinkFn)
    }

    fun detach() {
        sink.set(null)
    }

    /**
     * @return true when the shell took the URL (now or on its next
     *   `onStart`), meaning the caller must not route anywhere itself.
     */
    fun offer(url: String): Boolean {
        sink.get()?.let { deliver ->
            deliver(url)
            return true
        }
        if (!shellAlive) return false
        parked.set(url)
        return true
    }

    /** Drains a URL parked while the shell was stopped. One shot. */
    fun drain(): String? = parked.getAndSet(null)
}
