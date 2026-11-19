package com.shilapi.xcertplay

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.hud.BydCarPlayCall
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * The steering wheel's next/previous keys while the factory 360 camera covers the screen: they are
 * swallowed, and the iPhone's track does not change behind the driver's back. Without the usage
 * stats the gate cannot know, and must not take the keys away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarCameraGateTest {
    private lateinit var host: CarPlayHostActivity
    private lateinit var controller: CarPlayController

    @Before fun setUp() {
        BydCarPlayCall.end()
        CarPlayBackgroundSession.clear()
        CarCameraGate.stop()
        host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        host.getSharedPreferences("diplay_wheel_map_zoom", Context.MODE_PRIVATE).edit().clear().commit()
        host.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
        host.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
        controller = mock(CarPlayController::class.java)
        `when`(controller.sendMediaButton(anyInt())).thenReturn(true)
        set("controller", controller)
        set("activeAirPlaySession", mock(AirPlaySession::class.java))
    }

    @After fun tearDown() {
        CarCameraGate.stop()
        for (name in listOf("teardownExecutor", "airPlayCommandExecutor")) {
            (host.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(host) as ExecutorService).shutdownNow()
        }
        (host.javaClass.getDeclaredField("mainHandler").apply { isAccessible = true }.get(host) as Handler)
            .removeCallbacksAndMessages(null)
        BydCarPlayCall.end()
    }

    private fun set(name: String, value: Any?) =
        host.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(host, value)

    private fun pressNext() {
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            host.dispatchKeyEvent(KeyEvent(action, KeyEvent.KEYCODE_MEDIA_NEXT))
        }
    }

    @Test fun theCameraInFrontSwallowsTheWheelKey() {
        CarCameraGate.update(up = true)
        pressNext()
        verify(controller, never()).sendMediaButton(anyInt())
    }

    @Test fun withoutTheCameraTheWheelKeyReachesCarPlay() {
        CarCameraGate.update(up = false)
        pressNext()
        verify(controller, atLeastOnce()).sendMediaButton(CarPlayMediaButton.NEXT)
    }

    // No appop means "cannot know": the keys must keep working.
    @Test fun anUnreadableCameraStateKeepsTheKeysWorking() {
        CarCameraGate.update(up = true, canRead = false)
        pressNext()
        verify(controller, atLeastOnce()).sendMediaButton(CarPlayMediaButton.NEXT)
    }

    @Test fun theGateReopensWhenTheCameraCloses() {
        CarCameraGate.update(up = true)
        pressNext()
        verify(controller, never()).sendMediaButton(anyInt())

        CarCameraGate.update(up = false)
        pressNext()
        verify(controller, atLeastOnce()).sendMediaButton(CarPlayMediaButton.NEXT)
    }
}
