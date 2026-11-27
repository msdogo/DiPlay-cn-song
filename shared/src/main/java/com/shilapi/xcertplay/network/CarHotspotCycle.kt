package com.shilapi.xcertplay.network

import android.content.Context
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/**
 * Restarts the car's own hotspot after an unexpected wireless CarPlay loss.
 *
 * The driver's iPhone can stop rejoining the car hotspot while the head unit keeps the access point
 * up. On the CN DiLink 3.0 head unit the shell uid may not stop the AP directly: there is no hotspot
 * stop command over ADB, IConnectivityManager has no stopTethering, and both
 * ITetheringConnector.stopTethering and IWifiManager.stopSoftAp are rejected with
 * "Neither user 2000 nor current process has android.permission.NETWORK_STACK".
 *
 * What the shell may do is switch the Wi-Fi client (STA) on with "cmd wifi set-wifi-enabled enabled".
 * While the car hotspot runs, the STA is off; asking for it makes the firmware give the radio back,
 * which takes the AP down with it. That is the whole trick: one permitted command both stops the AP
 * and leaves Wi-Fi ready for the hotspot to be rebuilt on top of it.
 *
 * Only the built-in car hotspot is cycled this way: the Wi-Fi Direct and local-only backends own
 * their group, and "Existing Wi-Fi" belongs to the car.
 */
object CarHotspotCycle {
    /** How long the AP may take to report off after the Wi-Fi client was asked for. */
    private const val STOP_TIMEOUT_MILLIS = 20_000L

    /** How long the AP may take to come back once the start request was made. */
    private const val RESTART_TIMEOUT_MILLIS = 30_000L

    /** The Wi-Fi command set verb this cycle needs; older firmware has no cmd wifi at all. */
    internal const val WIFI_ENABLE_VERB = "set-wifi-enabled"

    /**
     * Whether a loss should cycle the hotspot: only the built-in car hotspot, only when the driver
     * opted in, and only with the ADB link the Wi-Fi switch needs.
     */
    fun shouldCycle(
        mode: WirelessHotspotMode,
        enabled: Boolean,
        adbReady: Boolean,
    ): Boolean = enabled && adbReady && mode == WirelessHotspotMode.MANUAL

    /**
     * Whether the firmware help output advertises the subsystem switch. A blind guess would fail
     * after the AP had already been given up.
     */
    internal fun supportsWifiSwitch(help: String?): Boolean =
        help != null && !rejected(help) && help.contains(WIFI_ENABLE_VERB)

    /** Asking for the client makes the firmware give the radio back, which takes the AP down. */
    internal fun wifiOnCommand(): String = "cmd wifi set-wifi-enabled enabled"

    internal fun rejected(output: String): Boolean = Regex(
        """unknown command|permission denied|securityexception|(?:^|\n)\s*error:|failed|can't find service|not found|not supported|unsupported""",
        RegexOption.IGNORE_CASE,
    ).containsMatchIn(output)

    /**
     * Asks for the Wi-Fi client and waits until the AP reports off. Returns true when it is off,
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
            ?: return false.also { log("car hotspot cycle: ADB cannot read the AP state; not cycling") }
        if (!initial) {
            log("car hotspot cycle: the AP is already off")
            return true
        }
        // Without the subsystem switch there is nothing the shell uid may do to this AP.
        if (!supportsWifiSwitch(read("cmd wifi help"))) {
            log("car hotspot cycle: this firmware does not offer $WIFI_ENABLE_VERB; not cycling")
            return false
        }
        if (stopped()) return false
        val output = read(wifiOnCommand() + " 2>&1")
        if (stopped()) return false
        if (output != null && rejected(output)) {
            log(
                "car hotspot cycle: firmware rejected $WIFI_ENABLE_VERB: " +
                    output.lineSequence().map(String::trim).firstOrNull { it.isNotEmpty() }.orEmpty().take(120),
            )
            return false
        }
        log("car hotspot cycle: Wi-Fi client requested; waiting for the AP to be given up")
        while (!stopped()) {
            if (observed() == false) {
                log("car hotspot cycle: AP off state confirmed after requesting the Wi-Fi client")
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
     * Waits until [arrived] reports the iPhone's Bluetooth link back, or the deadline passes. The
     * hotspot is restarted even when the phone never returns: the loss that started the cycle is
     * over either way, and a head unit without its hotspot would strand every later attempt.
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

    /**
     * Starts the saved car hotspot again and waits for it to report on. Returns true when it did.
     *
     * The Wi-Fi client was asked for by [stop]; starting tethering hands the radio back to the AP, so
     * no separate switch-off is needed here.
     */
    fun restart(
        context: Context,
        deadline: Long,
        cancelled: () -> Boolean,
        state: () -> Boolean?,
        log: (String) -> Unit,
    ): Boolean {
        val restartDeadline = minOf(deadline, System.nanoTime() + RESTART_TIMEOUT_MILLIS * 1_000_000L)
        fun remaining() = ((restartDeadline - System.nanoTime()) / 1_000_000L).coerceAtMost(30_000).toInt()
        fun stopped() = cancelled() || Thread.currentThread().isInterrupted || remaining() <= 0
        if (stopped()) return false
        val outcome = CarHotspotTethering.enable(
            context,
            isCancelled = { stopped() },
            timeoutMillis = remaining().coerceAtLeast(1).toLong(),
            log = { log("car hotspot cycle restart: $it") },
        )
        log("car hotspot cycle: restart $outcome")
        if (outcome == CarHotspotTethering.Result.CANCELLED) return false
        while (!stopped()) {
            if (state() == true) {
                log("car hotspot cycle: the AP reports on again")
                return true
            }
            try {
                Thread.sleep(minOf(250, remaining().coerceAtLeast(1)).toLong())
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        log("car hotspot cycle: the AP did not report on before the deadline")
        return false
    }
}
