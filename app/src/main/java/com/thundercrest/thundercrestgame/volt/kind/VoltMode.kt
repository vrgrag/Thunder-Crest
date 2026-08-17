package com.thundercrest.thundercrestgame.volt.kind

/**
 * Which experience the shell locked onto for this install.
 *
 *  - [Web]     — returning user previously routed to the WebView shell.
 *  - [Native]  — returning user previously routed to the native game.
 *  - [Pending] — first launch, not yet decided.
 */
enum class VoltMode(val wire: String) {
    Web("web"),
    Native("native"),
    Pending("pending");

    companion object {
        fun fromWire(raw: String?): VoltMode = when (raw) {
            "web" -> Web
            "native" -> Native
            else -> Pending
        }
    }
}
