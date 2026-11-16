package com.shilapi.xcertplay

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Looper
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * The CN build ships location reporting on. Refusing the precise-location request used to save
 * `false` over that default, so the switch came back off on every later start. A refusal now only
 * suspends the report for the running session; the saved choice stays the driver's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LocationReportingRefusalTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private var controller: ActivityController<CarPlayHostActivity>? = null

    @Before fun setUp() {
        CarPlayBackgroundSession.clear()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        AirPlayPersistence.saveMfiTarget(context, MfiTarget.USB_CH341)
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.WIFI_P2P)
        // Wired, so the wireless permission request does not run before the location one.
        AirPlayPersistence.saveWirelessEnabled(context, false)
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(context).denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }

    @After fun tearDown() {
        controller?.let { host ->
            runCatching { host.pause().stop().destroy() }
            for (field in listOf("teardownExecutor", "airPlayCommandExecutor")) {
                ReflectionHelpers.getField<java.util.concurrent.ExecutorService>(host.get(), field).shutdownNow()
            }
        }
        CarPlayBackgroundSession.clear()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    private fun open(): CarPlayHostActivity =
        Robolectric.buildActivity(CarPlayHostActivity::class.java).setup().also { controller = it }.get()

    /** Answers the open location request as the driver (or Android) did, granting only [granted]. */
    private fun answer(activity: CarPlayHostActivity, granted: Set<String> = emptySet()) {
        val request = shadowOf(activity).lastRequestedPermission
        val asked = request.requestedPermissions.toList()
        assertTrue(asked.toString(), Manifest.permission.ACCESS_FINE_LOCATION in asked)
        shadowOf(context).grantPermissions(*granted.toTypedArray())
        activity.onRequestPermissionsResult(
            request.requestCode,
            request.requestedPermissions,
            IntArray(asked.size) {
                if (asked[it] in granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
            },
        )
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun field(name: String) = ReflectionHelpers.getField<Any>(controller!!.get(), name)

    @Test fun aRefusalKeepsTheSavedChoiceOn() {
        val activity = open()
        assertTrue("the fresh install ships it on", AirPlayPersistence.loadLocationReportingEnabled(context))

        answer(activity)

        assertTrue(
            "a refused permission must not rewrite the saved default",
            AirPlayPersistence.loadLocationReportingEnabled(context),
        )
        assertEquals(true, field("locationReportingEnabled"))
        assertEquals(true, field("locationPermissionDenied"))
    }

    @Test fun aRefusalOnlySuspendsWhatTheIphoneIsTold() {
        val activity = open()
        answer(activity)

        assertEquals(
            "the iPhone is not offered a location it cannot send",
            false,
            ReflectionHelpers.callInstanceMethod<Any>(activity, "getEffectiveLocationReporting"),
        )
    }

    @Test fun grantingAfterARefusalClearsTheSuspension() {
        val activity = open()
        answer(activity)
        assertEquals(true, field("locationPermissionDenied"))

        shadowOf(context).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        requireNotNull(controller).pause().resume()

        assertEquals(false, field("locationPermissionDenied"))
        assertEquals(true, AirPlayPersistence.loadLocationReportingEnabled(context))
    }

    @Test fun aDriverChoiceOffStillStopsTheRequest() {
        AirPlayPersistence.saveLocationReportingEnabled(context, false)
        open()

        assertEquals(
            "a driver who turned it off is never asked again",
            null,
            shadowOf(controller!!.get()).lastRequestedPermission,
        )
        assertFalse(AirPlayPersistence.loadLocationReportingEnabled(context))
    }

    @Test fun theMenuNamesTheMissingPermissionUnderTheSwitch() {
        val activity = open()
        answer(activity)

        val hint = activity.getString(R.string.settings_location_reporting_needs_permission)
        assertTrue(hint.isNotBlank())
        assertEquals(true, field("locationReportingEnabled"))
    }
}
