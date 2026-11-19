package com.shilapi.xcertplay

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Whether the factory 360 camera (`com.byd.avc`) is in front.
 *
 * The steering wheel's next/previous keys reach CarPlay through three routes that are all global:
 * the CarPlay window's own key handling, the wheel key service's accessibility filter, and the
 * media session. None of them is scoped to a window, so a press while the camera covers the screen
 * changed the iPhone's track behind the driver's back. This gate stops that: while the camera is up
 * the press is swallowed instead of being sent.
 *
 * Fail open. Without the usage-stats appop nothing can be read, and a gate that guessed "camera up"
 * would take the wheel's media keys away for good; unreadable therefore means "not up".
 */
internal object CarCameraGate {
    private const val TAG = "CarCameraGate"

    /** The factory camera's package, the same one the side view yields to. */
    private const val CAMERA_PACKAGE = "com.byd.avc"

    private const val POLL_MS = 500L

    /** Catches a camera that resumed before this gate started. */
    private const val FIRST_LOOKBACK_MS = 5L * 60_000L

    @Volatile
    var cameraInFront: Boolean = false
        private set

    /** False while the appop is missing: "cannot know", which never suppresses a key. */
    @Volatile
    var readable: Boolean = false
        private set

    private val started = AtomicBoolean(false)
    private var worker: ScheduledExecutorService? = null

    @Volatile
    private var consumedUpTo = 0L

    /** True when a wheel media press must be swallowed rather than sent to CarPlay. */
    val suppressesMediaKeys: Boolean get() = readable && cameraInFront

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        consumedUpTo = 0L
        worker = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "camera-gate").apply { isDaemon = true }
        }.also { it.scheduleWithFixedDelay({ poll(app) }, 0, POLL_MS, TimeUnit.MILLISECONDS) }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        worker?.shutdownNow()
        worker = null
        cameraInFront = false
        readable = false
        consumedUpTo = 0L
    }

    /** Test seam: the poller's answer, without a UsageStatsManager. */
    internal fun update(up: Boolean, canRead: Boolean = true) {
        cameraInFront = up
        readable = canRead
    }

    private fun poll(context: Context) {
        try {
            val now = System.currentTimeMillis()
            val from = if (consumedUpTo == 0L) now - FIRST_LOOKBACK_MS else consumedUpTo + 1
            val latest = latestResumed(context, from, now + 1)
            if (latest != null) {
                if (latest.second > consumedUpTo) consumedUpTo = latest.second
                val up = latest.first == CAMERA_PACKAGE
                if (up != cameraInFront) {
                    cameraInFront = up
                    Log.i(TAG, "factory camera in front=$up")
                }
            }
            readable = true
        } catch (_: Exception) {
            // No appop, or events unavailable: report nothing rather than a stale "camera up".
            readable = false
            cameraInFront = false
        }
    }

    /** Newest ACTIVITY_RESUMED (package, timestamp) in the window, by timestamp rather than order. */
    private fun latestResumed(context: Context, from: Long, to: Long): Pair<String, Long>? {
        val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usage.queryEvents(from, to)
        val event = UsageEvents.Event()
        var best: Pair<String, Long>? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED &&
                (best == null || event.timeStamp > best.second)
            ) {
                best = event.packageName to event.timeStamp
            }
        }
        return best
    }
}
