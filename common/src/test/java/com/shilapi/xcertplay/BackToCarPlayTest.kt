package com.shilapi.xcertplay

import android.content.Intent
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/**
 * Back alternates between the two screens while CarPlay runs behind DiPlay's home page:
 * CarPlay's Back opens DiPlay, and DiPlay's Back opens CarPlay again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class BackToCarPlayTest {
    private var controller: ActivityController<DiPlayActivity>? = null

    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        CarPlayBackgroundSession.clear()
    }

    @After fun cleanup() {
        controller?.destroy()
        CarPlayBackgroundSession.clear()
    }

    private fun runningSession() {
        val stop: ((() -> Unit) -> Unit) = { done -> done() }
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", stop)
    }

    private fun openHome(): DiPlayActivity {
        // Back callbacks are lifecycle aware, so the activity must reach the resumed state.
        controller = Robolectric.buildActivity(DiPlayActivity::class.java, Intent()).setup()
        return controller!!.get()
    }

    @Test fun backOnTheHomePageReturnsToTheRunningCarPlayScreen() {
        runningSession()
        val activity = openHome()
        assertEquals("home", ReflectionHelpers.getField<String>(activity, "page"))
        shadowOf(activity).clearNextStartedActivities()
        activity.onBackPressedDispatcher.onBackPressed()
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(CarPlayHostActivity::class.java.name, started?.component?.className)
        assertTrue(started!!.flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0)
        assertFalse(activity.isFinishing)
        assertTrue(CarPlayBackgroundSession.hasSession())
    }

    @Test fun backOnTheHomePageWithoutCarPlayOnlyHidesTheApp() {
        CarPlayBackgroundSession.clear()
        val activity = openHome()
        shadowOf(activity).clearNextStartedActivities()
        activity.onBackPressedDispatcher.onBackPressed()
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(activity.isFinishing)
    }

    @Test fun backOnASettingsPageStillNavigatesBackBeforeReachingCarPlay() {
        runningSession()
        val activity = openHome()
        ReflectionHelpers.setField(activity, "page", "settings")
        shadowOf(activity).clearNextStartedActivities()
        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals("home", ReflectionHelpers.getField<String>(activity, "page"))
        assertNull(shadowOf(activity).nextStartedActivity)
        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals(CarPlayHostActivity::class.java.name,
            shadowOf(activity).nextStartedActivity?.component?.className)
    }
}
