package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hotspot cycle only ever acts on a state it could read, and only when the firmware advertises a
 * stop command. Everything else leaves the car alone.
 */
class CarHotspotCycleTest {
    private val connectivityHelp = """
        Connectivity service commands:
          start-tethering <wifi|usb|bluetooth>
          stop-tethering <wifi|usb|bluetooth>
    """.trimIndent()

    private val tetheringHelp = """
        Tethering service commands:
          start <wifi|usb|bluetooth>
          stop <wifi|usb|bluetooth>
    """.trimIndent()

    @Test
    fun connectivityServiceUsesStopTethering() {
        assertEquals("cmd connectivity stop-tethering wifi", CarHotspotCycle.stopCommand("connectivity", connectivityHelp))
    }

    @Test
    fun tetheringServiceUsesStop() {
        assertEquals("cmd tethering stop wifi", CarHotspotCycle.stopCommand("tethering", tetheringHelp))
    }

    @Test
    fun aServiceWithoutTheVerbForThatNameIsNotUsed() {
        // "stop wifi" is not a connectivity verb; only start-tethering/stop-tethering are.
        assertTrue(CarHotspotCycle.stopCommand("connectivity", tetheringHelp) == null)
        assertEquals("cmd tethering stop wifi", CarHotspotCycle.stopCommand("tethering", tetheringHelp))
    }

    @Test
    fun helpThatOnlyStartsHasNoStopCommand() {
        val startOnly = "  start-tethering wifi\n  start-tethering usb"
        assertTrue(CarHotspotCycle.stopCommand("connectivity", startOnly) == null)
    }

    @Test
    fun anUnknownServiceOrMissingHelpStopsNothing() {
        assertTrue(CarHotspotCycle.stopCommand("wifi", connectivityHelp) == null)
        assertTrue(CarHotspotCycle.stopCommand("connectivity", null) == null)
    }

    @Test
    fun helpThatIsAnErrorListingIsNotRead() {
        assertTrue(CarHotspotCycle.stopCommand("tethering", "Unknown command: stop") == null)
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
    fun stopGivesUpWhenTheFirmwareListsNoStopCommand() {
        val log = mutableListOf<String>()
        val stopped = CarHotspotCycle.stop(
            deadline = System.nanoTime() + 2_000_000_000L,
            cancelled = { false },
            state = { true },
            shell = { command, _ -> if (command.startsWith("cmd ")) "start-tethering wifi" else null },
            log = { log += it },
        )
        assertFalse(stopped)
        assertTrue(log.any { it.contains("no hotspot stop command") })
    }

    @Test
    fun stopIsAbandonedAsSoonAsTheCycleIsCancelled() {
        var cancelled = false
        val stopped = CarHotspotCycle.stop(
            deadline = System.nanoTime() + 2_000_000_000L,
            cancelled = { cancelled },
            state = { true },
            shell = { _, _ -> cancelled = true; null },
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

    @Test
    fun awaitArrivalStopsAtTheDeadline() {
        val log = mutableListOf<String>()
        val reached = CarHotspotCycle.awaitArrival(
            deadline = System.nanoTime(),
            cancelled = { false },
            arrived = { false },
            log = { log += it },
        )
        assertFalse(reached)
        assertTrue(log.any { it.contains("did not return") })
    }

    @Test
    fun stopFallsBackToTheShellHelperWhenTheFirmwareListsNoCommand() {
        val log = mutableListOf<String>()
        var apOn = true
        val stopped = CarHotspotCycle.stop(
            deadline = System.nanoTime() + 5_000_000_000L,
            cancelled = { false },
            state = { apOn },
            shell = { command, _ ->
                when {
                    command == "dumpsys wifi" -> null
                    command.startsWith("cmd ") -> "start-tethering wifi"
                    command.endsWith(" stop") -> {
                        apOn = false
                        "DIPLAY_HOTSPOT_STOP_V1|stopped"
                    }
                    else -> null
                }
            },
            log = { log += it },
            helper = "CLASSPATH='/data/app/base.apk' app_process /system/bin Helper stop",
        )
        assertTrue(stopped)
        assertTrue(log.any { it.contains("shell stop request stopped") })
        assertTrue(log.any { it.contains("AP off state confirmed") })
    }

    @Test
    fun stopGivesUpWhenTheShellHelperRefuses() {
        val log = mutableListOf<String>()
        val stopped = CarHotspotCycle.stop(
            deadline = System.nanoTime() + 5_000_000_000L,
            cancelled = { false },
            state = { true },
            shell = { command, _ ->
                when {
                    command == "dumpsys wifi" -> null
                    command.startsWith("cmd ") -> "start-tethering wifi"
                    command.endsWith(" stop") -> "DIPLAY_HOTSPOT_STOP_V1|unsupported"
                    else -> null
                }
            },
            log = { log += it },
            helper = "CLASSPATH='/data/app/base.apk' app_process /system/bin Helper stop",
        )
        assertFalse(stopped)
        assertTrue(log.any { it.contains("shell stop request failed") })
    }

    @Test
    fun aMissingHelperReplyIsNotMistakenForSuccess() {
        val stopped = CarHotspotCycle.stop(
            deadline = System.nanoTime() + 5_000_000_000L,
            cancelled = { false },
            state = { true },
            shell = { command, _ ->
                when {
                    command == "dumpsys wifi" -> null
                    command.startsWith("cmd ") -> "start-tethering wifi"
                    else -> "no protocol line here"
                }
            },
            log = {},
            helper = "CLASSPATH='/data/app/base.apk' app_process /system/bin Helper stop",
        )
        assertFalse(stopped)
    }
}
