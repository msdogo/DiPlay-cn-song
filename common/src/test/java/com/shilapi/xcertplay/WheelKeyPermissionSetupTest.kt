package com.shilapi.xcertplay

import android.content.ComponentName
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class WheelKeyPermissionSetupTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val ours get() = ComponentName(context, WheelKeyService::class.java).flattenToString()
    private val other = "car.package/.Accessibility"
    private val commands = mutableListOf<String>()
    private var reading = other
    private var reply: String? = "\nDIPLAY_WHEEL_EXIT:0"
    private var allow = true
    private var dumpsys: String? = null

    @Before fun setup() {
        context.getSharedPreferences("diplay_navigation_wheel", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        WheelZoomSettings.setEnabled(context, false)
        WheelZoomSettings.setJoystick(context, false)
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, other)
        Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
    }

    @Test fun nullEmptyOrFailedReadDoesNotWriteTheServiceList() {
        for (output in listOf(null, "", "$other\nDIPLAY_WHEEL_EXIT:1")) {
            commands.clear()
            assertFalse(WheelKeyService.applyServiceSettings(context, { commands += it; output }))
            assertEquals(1, commands.size)
            assertEquals(other, Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
        }
    }

    @Test fun malformedOrShellActiveValuesAreRejectedBeforeAnyWrite() {
        for (value in listOf("Permission denied", "$other:'; touch /tmp/unsafe; '", "$other::bad", "$other\nbad")) {
            reading = value
            commands.clear()
            assertFalse(WheelKeyService.applyServiceSettings(context, ::shell))
            assertEquals(1, commands.size)
        }
    }

    @Test fun stoppedServiceIsReboundWhileOtherServicesKeepTheirOrder() {
        reading = "$other:$ours:another.package/.Service"
        assertTrue(WheelKeyService.applyServiceSettings(context, ::shell))
        val writes = commands.filter { it.contains("settings put secure enabled_accessibility_services") }
        assertEquals(2, writes.size)
        assertTrue(writes[0].contains("'$other:another.package/.Service'"))
        // Android clears its crashed mark only if its observer sees the list without the service (#606).
        assertTrue(writes[0].contains("&& sleep 1"))
        assertFalse(writes[1].contains("sleep"))
        assertEquals("$other:another.package/.Service:$ours",
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
    }

    @Test fun healthyServiceIsNotRemovedForRebinding() {
        val service = Robolectric.buildService(WheelKeyService::class.java).create().get()
        service.javaClass.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(service)
        try {
            reading = "$other:$ours"
            assertTrue(WheelKeyService.applyServiceSettings(context, ::shell))
            val writes = commands.filter { it.contains("settings put secure enabled_accessibility_services") }
            assertEquals(1, writes.size)
            assertTrue(writes.single().contains("'$other:$ours'"))
        } finally { service.onDestroy() }
    }

    @Test fun serviceBindingAfterTheReadIsNotRemovedForRebinding() {
        val service = Robolectric.buildService(WheelKeyService::class.java).create().get()
        reading = "$other:$ours"
        var continuationChecks = 0
        try {
            assertTrue(WheelKeyService.applyServiceSettings(context, ::shell) {
                if (++continuationChecks == 2) {
                    service.javaClass.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(service)
                }
                true
            })
            val writes = commands.filter { it.contains("settings put secure enabled_accessibility_services") }
            assertEquals(1, writes.size)
            assertTrue(writes.single().contains("'$other:$ours'"))
        } finally { service.onDestroy() }
    }

    @Test fun shellFailureStopsFurtherWritesAndDoesNotClaimSuccess() {
        reply = "DIPLAY_WHEEL_EXIT:1"
        assertFalse(WheelKeyService.applyServiceSettings(context, ::shell))
        assertEquals(2, commands.size)
        assertFalse(commands.any { it.contains("settings put secure accessibility_enabled") })
    }

    @Test fun successfulShellWithoutActualPermissionIsNotSuccess() {
        assertFalse(WheelKeyService.applyServiceSettings(context, { command ->
            if (command.contains("settings get")) "$other\nDIPLAY_WHEEL_EXIT:0" else "DIPLAY_WHEEL_EXIT:0"
        }))
    }

    @Test fun disablingTheFeaturesDuringTheRestoreGraceStopsTheRequest() {
        WheelZoomSettings.setEnabled(context, true)
        assertTrue(WheelKeyService.needsRestore(context))
        WheelZoomSettings.setEnabled(context, false)
        assertFalse(WheelKeyService.needsRestore(context))
        assertFalse(WheelKeyService.applyServiceSettings(context, ::shell) { WheelKeyService.needsRestore(context) })
        assertTrue(commands.isEmpty())
    }

    @Test fun aServiceTheCarListsButNeverBoundIsRestoredWithoutAnyKeyFeature() {
        // Turned on through the ADB button, no key feature: the car force-stopped DiPlay, Android keeps
        // the name in the list and marks the service crashed, so nothing asks for a restore (#606).
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "$other:$ours")
        assertFalse(WheelKeyService.wanted(context))
        assertTrue(WheelKeyService.needsRestore(context))

        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, other)
        assertFalse(WheelKeyService.needsRestore(context))
    }

    @Test fun aServiceTheCarKeepsCrashedIsTakenOutAgainForLonger() {
        // The car left the service under "Crashed services" after the usual reset: only a list without it,
        // seen by the car, clears the mark (#606).
        reading = "$other:$ours"
        dumpsys = "Enabled services:\n  $ours\nCrashed services:\n  $ours\n"
        assertTrue(WheelKeyService.applyServiceSettings(context, ::shell))
        val writes = commands.filter { it.contains("settings put secure enabled_accessibility_services") }
        assertEquals(4, writes.size)
        assertTrue(writes[0].contains("&& sleep 1"))
        assertTrue(writes[2].contains("enabled_accessibility_services '$other'") && !writes[2].contains("sleep"))
        assertTrue(commands.any { it.contains("sleep 3") })
        assertTrue(writes[3].contains("enabled_accessibility_services '$other:$ours'") && !writes[3].contains("sleep"))
        assertEquals("$other:$ours",
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
        assertTrue(commands.any { it.contains("dumpsys accessibility") })
    }

    @Test fun aServiceTheCarNoLongerMarksCrashedIsWrittenBackOnlyOnce() {
        reading = "$other:$ours"
        dumpsys = "Enabled services:\n  $ours\nCrashed services:\n  car.package/.Accessibility\n"
        assertTrue(WheelKeyService.applyServiceSettings(context, ::shell))
        assertEquals(2, commands.count { it.contains("settings put secure enabled_accessibility_services") })
    }

    @Test fun anUnreadableCrashedListLeavesTheUsualRepair() {
        reading = "$other:$ours"
        for (dump in listOf(null, "Enabled services:\n  $ours\n")) {
            dumpsys = dump
            commands.clear()
            assertTrue(WheelKeyService.applyServiceSettings(context, ::shell))
            assertEquals(2, commands.count { it.contains("settings put secure enabled_accessibility_services") })
        }
        assertEquals(false, WheelKeyService.crashedMark("Crashed services:\n  car.package/.Accessibility\n", ours))
        assertEquals(true, WheelKeyService.crashedMark("Crashed services:\n  $ours\n", ours))
        assertNull(WheelKeyService.crashedMark(null, ours))
    }

    private fun repairTrail(): String? = context
        .getSharedPreferences("wheel_key_repair", android.content.Context.MODE_PRIVATE)
        .getString("trail", null)

    @Test fun aRepairKeepsTheStepsItTookForTheReport() {
        reading = "$other:$ours"
        assertTrue(WheelKeyService.applyServiceSettings(context, ::shell))
        val trail = repairTrail().orEmpty()
        assertTrue(trail, trail.contains("listed=true"))
        assertTrue(trail, trail.contains("removedFor=1s"))
        assertTrue(trail, trail.contains("written bound=false"))
        val report = WheelKeyService.report(context)
        assertTrue(report, report.contains("lastRepair=ageMs="))
        assertTrue(report, report.contains("written bound=false"))
    }

    @Test fun aRepairThatStopsSaysWhichStepTheCarRefused() {
        // A report that only says "still starting…" cannot tell a refused read, a refused write and a
        // cancelled repair apart.
        assertFalse(WheelKeyService.applyServiceSettings(context, { commands += it; null }))
        assertEquals("stopped=the service list could not be read", repairTrail())

        reply = "$other\nDIPLAY_WHEEL_EXIT:1"
        assertFalse(WheelKeyService.applyServiceSettings(context, ::shell))
        assertTrue(repairTrail().orEmpty(), repairTrail().orEmpty().endsWith("stopped=the service list could not be written"))

        reply = "\nDIPLAY_WHEEL_EXIT:0"
        assertFalse(WheelKeyService.applyServiceSettings(context, ::shell) { false })
        assertEquals("stopped=cancelled", repairTrail())
    }

    @Test fun theCarOwnCrashListIsKeptForTheReport() {
        // The maintainer asks for "dumpsys accessibility" every time: DiPlay now asks for it itself.
        reading = "$other:$ours"
        dumpsys = "Enabled services:\n  $ours\n  $other\nCrashed services:\n  $ours\n"
        assertTrue(WheelKeyService.applyServiceSettings(context, ::shell))
        val report = WheelKeyService.report(context)
        assertTrue(report, report.contains("carCrashList=ageMs="))
        assertTrue(report, report.contains("enabled=[$ours $other]"))
        assertTrue(report, report.contains("crashed=[$ours]"))
    }

    @Test fun aCarWithoutACrashSectionSaysSoInsteadOfGuessing() {
        reading = "$other:$ours"
        dumpsys = "Enabled services:\n  $ours\n"
        assertTrue(WheelKeyService.applyServiceSettings(context, ::shell))
        val report = WheelKeyService.report(context)
        assertTrue(report, report.contains("crashed=[section missing]"))
    }

    @Test fun aRepairWithNothingToRemoveSkipsTheReset() {
        reading = other
        assertTrue(WheelKeyService.applyServiceSettings(context, ::shell))
        val trail = repairTrail().orEmpty()
        assertTrue(trail, trail.contains("listed=false"))
        assertFalse(trail, trail.contains("removedFor="))
    }

    @Test fun disablingAfterTheReadStopsAllWrites() {
        assertFalse(WheelKeyService.applyServiceSettings(context, { command -> shell(command).also { allow = false } }) { allow })
        assertEquals(1, commands.size)
    }

    @Test fun navigationOnlyRestorationRequiresRecordedAuthorizationAndStopsWhenDisabled() {
        val prefs = context.getSharedPreferences("diplay_navigation_wheel", android.content.Context.MODE_PRIVATE)
        assertFalse(NavigationWheelSettings.enabled(context))
        prefs.edit().putBoolean("enabled", true).commit()
        assertFalse(WheelKeyService.needsRestore(context))
        NavigationWheelSettings.recordAuthorization(context)
        assertTrue(WheelKeyService.needsRestore(context))
        prefs.edit().putBoolean("enabled", false).commit()
        assertFalse(WheelKeyService.needsRestore(context))
        assertFalse(WheelKeyService.applyServiceSettings(context, ::shell) { WheelKeyService.needsRestore(context) })
        assertTrue(commands.isEmpty())
    }

    private fun shell(command: String): String? {
        commands += command
        if (command.contains("dumpsys accessibility")) return dumpsys
        if (command.contains("settings get")) return "$reading\nDIPLAY_WHEEL_EXIT:0"
        if (reply?.trim() != "DIPLAY_WHEEL_EXIT:0") return reply
        if (command.contains("settings put secure enabled_accessibility_services")) {
            val value = command.substringAfter("enabled_accessibility_services '").substringBefore("'")
            Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value)
        } else if (command.contains("settings put secure accessibility_enabled 1")) {
            Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }
        return reply
    }
}
