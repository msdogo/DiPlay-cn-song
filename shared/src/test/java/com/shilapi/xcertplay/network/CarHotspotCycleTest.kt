package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cycle restarts the car hotspot by asking for the Wi-Fi client, which is the only Wi-Fi control
 * the CN DiLink shell uid may use. It never acts on a state it could not read and never guesses a
 * command the firmware does not advertise.
 */
class CarHotspotCycleTest {
    private val wifiHelp = """
        Wi-Fi is enabled
        ---- Commands supported ----
          set-wifi-enabled enabled|disabled
            Enables/disables Wifi on this device.
          status
            Gets the status of Wifi.
    """.trimIndent()

    @Test
    fun theFirmwareMustAdvertiseTheSubsystemSwitch() {
        assertTrue(CarHotspotCycle.supportsWifiSwitch(wifiHelp))
        assertFalse(CarHotspotCycle.supportsWifiSwitch(null))
        assertFalse(CarHotspotCycle.supportsWifiSwitch("Wi-Fi is enabled"))
    }

    @Test
    fun anErrorListingIsNotReadAsSupport() {
        assertFalse(CarHotspotCycle.supportsWifiSwitch("unknown command set-wifi-enabled"))
    }

    @Test
    fun stoppingAsksForTheWifiClient() {
        assertEquals("cmd wifi set-wifi-enabled enabled", CarHotspotCycle.wifiOnCommand())
    }

    @Test
    fun shouldCycleNeedsTheCarHotspotTheOptInAndAdb() {
        assertTrue(CarHotspotCycle.shouldCycle(WirelessHotspotMode.MANUAL, enabled = true, adbReady = true))
        assertFalse(CarHotspotCycle.shouldCycle(WirelessHotspotMode.MANUAL, enabled = false, adbReady = true))
        assertFalse(CarHotspotCycle.shouldCycle(WirelessHotspotMode.MANUAL, enabled = true, adbReady = false))
        for (mode in WirelessHotspotMode.entries.filter { it != WirelessHotspotMode.MANUAL }) {
            assertFalse(mode.name, CarHotspotCycle.shouldCycle(mode, enabled = true, adbReady = true))
        }
    }

    @Test
    fun stopReturnsTrueWhenTheApIsAlreadyOff() {
        val log = mutableListOf<String>()
        val stopped = CarHotspotCycle.stop(
            deadline = System.nanoTime() + 2_000_000_000L,
            cancelled = { false },
            state = { false },
            shell = { _, _ -> error("the shell must not run when the AP is already off") },
            log = { log += it },
        )
        assertTrue(stopped)
        assertTrue(log.any { it.contains("already off") })
    }

    @Test
    fun stopRefusesToActWhenTheApStateCannotBeRead() {
        val log = mutableListOf<String>()
        val stopped = CarHotspotCycle.stop(
            deadline = System.nanoTime() + 2_000_000_000L,
            cancelled = { false },
            state = { null },
            shell = { command, _ -> if (command == "dumpsys wifi") "no manager dump here" else "" },
            log = { log += it },
        )
        assertFalse(stopped)
        assertTrue(log.any { it.contains("cannot read the AP state") })
    }

    @Test
    fun stopRefusesWhenTheFirmwareLacksTheCommand() {
        val log = mutableListOf<String>()
        val stopped = CarHotspotCycle.stop(
            deadline = System.nanoTime() + 2_000_000_000L,
            cancelled = { false },
            state = { true },
            shell = { command, _ -> if (command == "cmd wifi help") "Wi-Fi is enabled" else null },
            log = { log += it },
        )
        assertFalse(stopped)
        assertTrue(log.any { it.contains("does not offer") })
    }

    @Test
    fun stopAsksForTheClientAndWaitsForTheApToGoAway() {
        val log = mutableListOf<String>()
        var apOn = true
        val stopped = CarHotspotCycle.stop(
            deadline = System.nanoTime() + 5_000_000_000L,
            cancelled = { false },
            state = { apOn },
            shell = { command, _ ->
                when {
                    command == "dumpsys wifi" -> null
                    command == "cmd wifi help" -> wifiHelp
                    command.startsWith("cmd wifi set-wifi-enabled enabled") -> { apOn = false; "" }
                    else -> null
                }
            },
            log = { log += it },
        )
        assertTrue(stopped)
        assertTrue(log.any { it.contains("Wi-Fi client requested") })
        assertTrue(log.any { it.contains("AP off state confirmed") })
    }

    @Test
    fun stopGivesUpWhenTheFirmwareRejectsTheCommand() {
        val log = mutableListOf<String>()
        val stopped = CarHotspotCycle.stop(
            deadline = System.nanoTime() + 5_000_000_000L,
            cancelled = { false },
            state = { true },
            shell = { command, _ ->
                when {
                    command == "dumpsys wifi" -> null
                    command == "cmd wifi help" -> wifiHelp
                    else -> "Error: permission denied"
                }
            },
            log = { log += it },
        )
        assertFalse(stopped)
        assertTrue(log.any { it.contains("firmware rejected") })
    }

    @Test
    fun stopIsAbandonedAsSoonAsTheCycleIsCancelled() {
        val stopped = CarHotspotCycle.stop(
            deadline = System.nanoTime() + 2_000_000_000L,
            cancelled = { true },
            state = { true },
            shell = { _, _ -> null },
            log = {},
        )
        assertFalse(stopped)
    }

    @Test
    fun awaitArrivalReturnsAsSoonAsThePhoneIsBack() {
        var polls = 0
        val reached = CarHotspotCycle.awaitArrival(
            deadline = System.nanoTime() + 5_000_000_000L,
            cancelled = { false },
            arrived = { ++polls == 2 },
            log = {},
        )
        assertTrue(reached)
        assertEquals(2, polls)
    }

    @Test
    fun awaitArrivalStopsWhenTheCycleIsCancelled() {
        val reached = CarHotspotCycle.awaitArrival(
            deadline = System.nanoTime() + 5_000_000_000L,
            cancelled = { true },
            arrived = { error("a cancelled cycle must not poll the phone") },
            log = {},
        )
        assertFalse(reached)
    }
}
