package com.thundercrest.thundercrestgame.push

/**
 * Process-wide hand-off for push URLs targeting an open WebLinkActivity.
 *
 * When the WebView is in the foreground the user should not see a system
 * notification at all — the URL loads directly into the running stage.
 * When the stage is alive but paused the URL is queued and drained the
 * next time the stage comes to the front.
 *
 * A short dedupe window prevents loading the same URL twice if the
 * notification tap races with the queue drain.
 */
internal object ThunderRelay {

    @Volatile var stageAlive: Boolean = false

    @Volatile var onLiveUrl: ((String) -> Unit)? = null
        set(value) {
            field = value
            if (value != null) drain(value)
        }

    private val queue = ArrayDeque<String>()

    private const val DEDUPE_WINDOW_MS = 2_300L
    private var lastUrl: String? = null
    private var lastAt: Long = 0L

    /**
     * Delivers the URL to a running stage if one is available, or queues
     * it for the next [onStart]. Returns true iff the live callback fired.
     */
    @Synchronized
    fun deliver(url: String): Boolean {
        val cb = onLiveUrl
        if (cb != null) {
            emit(cb, url)
            return true
        }
        if (stageAlive) queue.addLast(url)
        return false
    }

    /** Call after loading the URL via the cold-tap / onNewIntent path so
     *  the queue drain on the next onStart does not reload the same page. */
    @Synchronized
    fun markSeen(url: String) {
        lastUrl = url
        lastAt = System.currentTimeMillis()
    }

    @Synchronized
    private fun drain(cb: (String) -> Unit) {
        while (queue.isNotEmpty()) emit(cb, queue.removeFirst())
    }

    private fun emit(cb: (String) -> Unit, url: String) {
        val now = System.currentTimeMillis()
        if (url == lastUrl && now - lastAt < DEDUPE_WINDOW_MS) return
        lastUrl = url
        lastAt = now
        cb(url)
    }
}
