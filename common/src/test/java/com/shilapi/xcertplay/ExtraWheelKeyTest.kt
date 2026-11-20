package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.shilapi.xcertplay.hud.BydCarPlayCall
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowToast

/**
 * BYD's spare wheel key (294): a tap shows "1", a hold shows "2" and wakes Siri, and the wheel's own
 * voice keys no longer open Siri at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class ExtraWheelKeyTest {
    private lateinit var host: CarPlayHostActivity
    private lateinit var controller: CarPlayController

    @Before fun setUp() {
        BydCarPlayCall.end()
        CarPlayBackgroundSession.clear()
        ExtraWheelKey.resetQuiet()
        host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        host.getSharedPreferences("diplay_wheel_map_zoom", Context.MODE_PRIVATE).edit().clear().commit()
        host.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
        controller = mock(CarPlayController::class.java)
        `when`(controller.requestSiri()).thenReturn(true)
        field("controller", controller)
    }

    @After fun tearDown() {
        WheelSiriHold.cancelAll(field("mainHandler") as Handler)
        for (name in listOf("teardownExecutor", "airPlayCommandExecutor")) {
            (host.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(host) as ExecutorService).shutdownNow()
        }
        BydCarPlayCall.end()
        CarPlayBackgroundSession.clear()
        ExtraWheelKey.resetQuiet()
        ShadowToast.reset()
    }

    private fun field(name: String, value: Any? = null): Any? {
        val declared = host.javaClass.getDeclaredField(name).apply { isAccessible = true }
        if (value != null) declared.set(host, value)
        return declared.get(host)
    }

    private fun key(down: Boolean, time: Long = 1_000, repeat: Int = 0) = KeyEvent(
        time, time, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
        ExtraWheelKey.KEYCODE, repeat, 0, 5, ExtraWheelKey.SCAN_CODE,
    )

    @Test fun aTapShowsOneAndDoesNotWakeSiri() {
        assertTrue(host.dispatchKeyEvent(key(true)))
        assertTrue(host.dispatchKeyEvent(key(false, time = 1_120)))
        assertEquals("1", ShadowToast.getTextOfLatestToast())
        verify(controller, never()).requestSiri()
    }

    @Test fun aHoldShowsTwoAndWakesSiriOnce() {
        WheelZoomSettings.setSiriHoldMillis(host, 800)
        assertTrue(host.dispatchKeyEvent(key(true)))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(700))
        verify(controller, never()).requestSiri()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
        assertEquals("2", ShadowToast.getTextOfLatestToast())
        verify(controller, times(1)).requestSiri()
        // The release after a completed hold must not also count as a tap.
        assertTrue(host.dispatchKeyEvent(key(false, time = 2_000)))
        verify(controller, times(1)).requestSiri()
        assertEquals("2", ShadowToast.getTextOfLatestToast())
    }

    @Test fun heldRepeatsDoNotArmASecondHold() {
        WheelZoomSettings.setSiriHoldMillis(host, 800)
        host.dispatchKeyEvent(key(true))
        host.dispatchKeyEvent(key(true, time = 1_100, repeat = 1))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(900))
        verify(controller, times(1)).requestSiri()
    }

    @Test fun theWheelsOwnVoiceKeysNoLongerOpenSiri() {
        for (code in listOf(
            KeyEvent.KEYCODE_VOICE_ASSIST,
            com.shilapi.xcertplay.airplay.CarPlayMediaButton.KEYCODE_BYD_AUTO_MEDIA_VOICE,
            com.shilapi.xcertplay.airplay.CarPlayMediaButton.KEYCODE_BYD_AUTO_MEDIA_VOICE_LONG,
        )) {
            host.dispatchKeyEvent(KeyEvent(1_000, 1_000, KeyEvent.ACTION_DOWN, code, 0, 0, 5, 0))
            host.dispatchKeyEvent(KeyEvent(1_100, 1_100, KeyEvent.ACTION_UP, code, 0, 0, 5, 0))
        }
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(3_000))
        verify(controller, never()).requestSiri()
    }

    @Test fun nextAndPreviousStayQuietForTenSecondsAfterSiriOpens() {
        WheelZoomSettings.setSiriHoldMillis(host, 800)
        // Without a session the window claims nothing, so give it one for the media route.
        field("activeAirPlaySession", mock(com.shilapi.xcertplay.airplay.AirPlaySession::class.java))
        host.dispatchKeyEvent(key(true))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(900))
        verify(controller, times(1)).requestSiri()

        val next = KeyEvent(2_000, 2_000, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT, 0, 0, 5, 272)
        assertTrue(host.dispatchKeyEvent(next))
        verify(controller, never()).sendMediaButton(4)

        // The next press after the window is CarPlay's again.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(10_500))
        assertFalse(ExtraWheelKey.quietAfterSiri())
        assertTrue(host.dispatchKeyEvent(next))
        verify(controller, times(1)).sendMediaButton(4)
    }

    @Test fun theWheelsOwnVoiceKeyLongPressAlsoStartsTheQuietWindow() {
        // The long press arrives as its own code (312) and still reaches the car; DiPlay only watches.
        WheelZoomSettings.setSiriHoldMillis(host, 800)
        field("activeAirPlaySession", mock(com.shilapi.xcertplay.airplay.AirPlaySession::class.java))
        val long1 = com.shilapi.xcertplay.airplay.CarPlayMediaButton.KEYCODE_BYD_AUTO_MEDIA_VOICE_LONG
        assertFalse(host.dispatchKeyEvent(KeyEvent(1_000, 1_000, KeyEvent.ACTION_DOWN, long1, 0, 0, 5, 312)))
        assertTrue(ExtraWheelKey.quietAfterSiri())
        assertFalse(host.dispatchKeyEvent(KeyEvent(1_100, 1_100, KeyEvent.ACTION_UP, long1, 0, 0, 5, 312)))

        val next = KeyEvent(2_000, 2_000, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT, 0, 0, 5, 272)
        assertTrue(host.dispatchKeyEvent(next))
        verify(controller, never()).sendMediaButton(4)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(10_500))
        assertTrue(host.dispatchKeyEvent(next))
        verify(controller, times(1)).sendMediaButton(4)
    }

    @Test fun aTapDoesNotStartTheQuietWindow() {
        host.dispatchKeyEvent(key(true))
        host.dispatchKeyEvent(key(false, time = 1_120))
        assertFalse(ExtraWheelKey.quietAfterSiri())
    }

    @Test fun theKeyIsRecognisedByItsCodeAlone() {
        assertTrue(ExtraWheelKey.handles(ExtraWheelKey.KEYCODE))
        assertTrue(!ExtraWheelKey.handles(304))
    }
}
