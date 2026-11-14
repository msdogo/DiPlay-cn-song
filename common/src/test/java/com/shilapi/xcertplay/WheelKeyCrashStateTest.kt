package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The car's "Crashed services" list is the one state where the wheel key service cannot bind however often
 * the settings name it, and the driver has to see it as something other than an endless "starting…" (#606).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class WheelKeyCrashStateTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val ours = ComponentName(context, WheelKeyService::class.java).flattenToString()

    private fun dump(enabled: String, crashed: String, ageMillis: Long = 0L) {
        context.getSharedPreferences("wheel_key_repair", Context.MODE_PRIVATE).edit()
            .putLong("dumpAt", System.currentTimeMillis() - ageMillis)
            .putString("enabled", enabled)
            .putString("crashed", crashed)
            .commit()
    }

    @Test fun aServiceTheCarKeepsCrashedIsNamedAsSuch() {
        dump(enabled = ours, crashed = "$ours UserId=0")
        assertTrue(WheelKeyService.crashListed(context))
    }

    @Test fun aServiceOutsideTheCrashListIsOnlyStarting() {
        dump(enabled = ours, crashed = "car.package/.Other")
        assertFalse(WheelKeyService.crashListed(context))
    }

    @Test fun anOldDumpSaysNothing() {
        dump(enabled = ours, crashed = ours, ageMillis = 11 * 60 * 1000L)
        assertFalse(WheelKeyService.crashListed(context))
    }

    @Test fun aDumpWithoutTheSectionSaysNothing() {
        context.getSharedPreferences("wheel_key_repair", Context.MODE_PRIVATE).edit()
            .putLong("dumpAt", System.currentTimeMillis())
            .remove("crashed")
            .commit()
        assertFalse(WheelKeyService.crashListed(context))
    }

    @Test fun aBoundServiceIsNeverCalledCrashed() {
        dump(enabled = ours, crashed = ours)
        val service = org.robolectric.Robolectric.buildService(WheelKeyService::class.java).create().get()
        try {
            service.javaClass.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(service)
            assertFalse(WheelKeyService.crashListed(context))
        } finally {
            service.onDestroy()
        }
    }
}
