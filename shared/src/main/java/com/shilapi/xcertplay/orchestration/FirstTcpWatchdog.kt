package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.AirPlayListenerIdentity
import com.shilapi.xcertplay.airplay.AirPlayTcpAccepted
import com.shilapi.xcertplay.network.WirelessStartupPolicy

internal class FirstTcpWatchdog(
    val listener: AirPlayListenerIdentity,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val onTimeout: () -> Unit,
    private val nowNanos: () -> Long = System::nanoTime,
    private val timeoutMillis: Long = WirelessStartupPolicy.FIRST_TCP_MILLIS,
    // One extra window, granted only while [graceWanted] says the iPhone is still negotiating. A fresh
    // iPhone handshake after a reconnect can take longer than the first window, and tearing the whole
    // wireless stack down at the deadline costs the driver another full bring-up with no first screen.
    private val graceMillis: Long = 0L,
    private val graceWanted: () -> Boolean = { false },
    private val log: (String) -> Unit = {},
) {
    @Volatile var terminated = false
        private set
    private var firstStartNanos: Long? = null
    private var firstAcceptNanos: Long? = null
    private var success = false
    private var graceUsed = false
    private var cancelTimer: (() -> Unit)? = null

    @Synchronized fun startSessionSent(sentAtNanos: Long) {
        if (terminated || firstStartNanos != null) return
        firstStartNanos = sentAtNanos
        log("StartSession atNs=$sentAtNanos")
        if (success || firstAcceptNanos != null) return
        val remaining = (timeoutMillis - (nowNanos() - sentAtNanos) / 1_000_000).coerceAtLeast(0)
        cancelTimer = schedule(remaining, ::expire)
    }

    @Synchronized fun accepted(event: AirPlayTcpAccepted): Boolean {
        if (terminated || event.listener !== listener || event.internal) return false
        if (firstAcceptNanos == null) {
            firstAcceptNanos = event.acceptedAtNanos
            log("first TCP atNs=${event.acceptedAtNanos}")
        }
        cancel()
        return true
    }

    @Synchronized fun sessionEstablished(): Boolean {
        if (terminated) return false
        if (!success) log("session established atNs=${nowNanos()}")
        success = true
        cancel()
        return true
    }

    @Synchronized fun terminate(): Boolean {
        if (terminated) return false
        terminated = true
        cancel()
        return true
    }

    private fun expire() {
        val timedOut = synchronized(this) {
            if (terminated || success || firstAcceptNanos != null || firstStartNanos == null) return
            if (graceMillis > 0 && !graceUsed && graceWanted()) {
                graceUsed = true
                val atNs = nowNanos()
                log("first TCP still pending atNs=$atNs startNs=$firstStartNanos; " +
                    "holding the wireless stack for another ${graceMillis}ms")
                cancelTimer = schedule(graceMillis, ::expire)
                return
            }
            terminated = true
            cancelTimer = null
            log("first TCP timeout atNs=${nowNanos()} startNs=$firstStartNanos")
            true
        }
        if (timedOut) onTimeout()
    }

    private fun cancel() { cancelTimer?.invoke(); cancelTimer = null }
}
