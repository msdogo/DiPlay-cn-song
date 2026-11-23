package com.shilapi.xcertplay.network

import android.content.Context
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/**
 * Turns the car's own hotspot off and back on after an unexpected wireless CarPlay loss.
 *
 * The driver's phone can stop rejoining the car hotspot while the head unit keeps the access point
 * up. Stopping the AP, waiting for the iPhone's Bluetooth link and starting the AP again clears that
 * state. Only the built-in car hotspot can be cycled this way: the Wi-Fi Direct and local-only
 * backends own their group, and "Existing Wi-Fi" belongs to the car.
 *
 * Everything here reads the AP state before it changes anything. A firmware whose "cmd connectivity"
 * or "cmd tethering" help lists no stop command is left alone; a blind toggle could take the car's
 * station connection with it.
 */
object CarHotspotCycle {
    /** How long the AP may take to report off after the firmware accepted the request. */
    private const val STOP_TIMEOUT_MILLIS = 15_000L

    /**
     * Whether a loss should cycle the hotspot: only the built-in car hotspot, only when the driver
     * opted in, and only with the ADB link the stop command needs.
     */
    fun shouldCycle(
        mode: WirelessHotspotMode,
        enabled: Boolean,
        adbReady: Boolean,
    ): Boolean = enabled && adbReady && mode == WirelessHotspotMode.MANUAL

    /**
     * The firmware's own stop command, when its help output advertises one. Vendor images differ:
     * some list "stop-tethering wifi" under connectivity, others "stop wifi" under tethering.
     */
    internal fun stopCommand(service: String, help: String?): String? {
        if (service !in listOf("connectivity", "tethering") || help == null || rejected(help)) return null
        for (line in help.lineSequence()) {
            val form = Regex("""^\s*(stop-tethering|stop)\s+(wifi|[<\[][^>\]]*\bwifi\b[^>\]]*[>\]])(?:\s*\[[^\n]*])?\s*$""")
                .matchEntire(line) ?: continue
            val verb = form.groupValues[1]
            if (service == "connectivity" && verb != "stop-tethering") continue
            return "cmd $service $verb wifi"
        }
        return null
    }

    internal fun rejected(output: String): Boolean = Regex(
        """unknown command|permission denied|securityexception|(?:^|\n)\s*error:|failed|can't find service|not found|not supported|unsupported""",
        RegexOption.IGNORE_CASE,
    ).containsMatchIn(output)

    /**
     * Stops the AP that is running now and waits until it reports off. Returns true when it is off,
     * including when it already was. Blocking; call it off the main thread.
     *
     * The deadline covers the capability reads, the command and the polling, and [cancelled] aborts
     * between steps so a reconnect that arrives first is never delayed.
     */
    fun stop(
        deadline: Long,
        cancelled: () -> Boolean,
        state: () -> Boolean?,
        shell: (String, Int) -> String?,
        log: (String) -> Unit,
    ): Boolean {
        val stopDeadline = minOf(deadline, System.nanoTime() + STOP_TIMEOUT_MILLIS * 1_000_000L)
        fun remaining() = ((stopDeadline - System.nanoTime()) / 1_000_000L).coerceAtMost(30_000).toInt()
        fun stopped() = cancelled() || Thread.currentThread().isInterrupted || remaining() <= 0
        fun read(command: String): String? = if (stopped()) null else shell(command, remaining().coerceAtLeast(1))
        fun observed(): Boolean? = state() ?: CarHotspotAdbFallback.apState(read("dumpsys wifi"))
        if (stopped()) return false
        val initial = observed()
            ?: return false.also { log("car hotspot cycle: ADB cannot read the AP state; not stopping it") }
        if (!initial) {
            log("car hotspot cycle: the AP is already off")
            return true
        }
        var command: String? = null
        for (service in listOf("connectivity", "tethering")) {
            command = stopCommand(service, read("cmd $service help"))
            if (command != null) break
        }
        if (stopped()) return false
        if (command == null) {
            log("car hotspot cycle: firmware lists no hotspot stop command in cmd connectivity/tethering help")
            return false
        }
        // No exit-code protocol here: the polling below is the proof. A command that silently did
        // nothing simply never reports the AP off and the cycle is abandoned.
        val output = read("$command 2>&1")
        if (stopped()) return false
        if (output != null && rejected(output)) {
            log(
                "car hotspot cycle: firmware rejected '$command': " +
                    output.orEmpty().lineSequence().map(String::trim).firstOrNull { it.isNotEmpty() }.orEmpty().take(120),
            )
            return false
        }
        log("car hotspot cycle: firmware ADB stop request accepted; waiting for AP off state")
        while (!stopped()) {
            if (observed() == false) {
                log("car hotspot cycle: AP off state confirmed after local ADB request")
                return true
            }
            try {
                Thread.sleep(minOf(250, remaining().coerceAtLeast(1)).toLong())
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        log("car hotspot cycle: the AP did not report off before the deadline")
        return false
    }

    /**
     * Waits until [arrived] reports the iPhone's Bluetooth link back, or the deadline passes. A
     * cycle whose phone never returns still restarts the AP: the loss that started it is over
     * either way, and leaving the hotspot down would strand the next connection attempt.
     */
    fun awaitArrival(
        deadline: Long,
        cancelled: () -> Boolean,
        arrived: () -> Boolean,
        log: (String) -> Unit,
    ): Boolean {
        while (true) {
            if (cancelled() || Thread.currentThread().isInterrupted) return false
            if (arrived()) return true
            if (System.nanoTime() >= deadline) {
                log("car hotspot cycle: the iPhone Bluetooth link did not return before the deadline")
                return false
            }
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    /** Starts the saved car hotspot again through the same path a normal connection uses. */
    fun restart(
        context: Context,
        deadline: Long,
        cancelled: () -> Boolean,
        log: (String) -> Unit,
    ): CarHotspotTethering.Result {
        val remaining = (deadline - System.nanoTime()) / 1_000_000L
        if (cancelled() || remaining <= 0) return CarHotspotTethering.Result.CANCELLED
        val outcome = CarHotspotTethering.enable(
            context,
            isCancelled = { cancelled() },
            timeoutMillis = remaining,
            log = { log("car hotspot cycle restart: $it") },
        )
        log("car hotspot cycle: restart $outcome")
        return outcome
    }
}
