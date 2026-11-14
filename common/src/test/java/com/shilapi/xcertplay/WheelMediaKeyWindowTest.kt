package com.shilapi.xcertplay

import android.content.Context
import android.media.AudioManager
import android.content.res.Configuration
import android.os.Handler
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.airplay.AirPlayKnobState
import com.shilapi.xcertplay.hud.BydCarPlayCall
import com.shilapi.xcertplay.hud.CarPlayCallState
import com.shilapi.xcertplay.iap2.message.Iap2Messages
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
 * The steering wheel's next/previous keys, claimed by the CarPlay window itself.
 *
 * This is the route that has to work when the wheel key service cannot be turned on — a head unit
 * with no ADB has no route to it — so the tests here drive `dispatchKeyEvent` directly rather than
 * going through an accessibility service.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WheelMediaKeyWindowTest {
    private lateinit var host: CarPlayHostActivity
    private lateinit var controller: CarPlayController

    @Before fun setUp() {
        BydCarPlayCall.end()
        CarPlayBackgroundSession.clear()
        host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        host.getSharedPreferences("diplay_wheel_map_zoom", Context.MODE_PRIVATE).edit().clear().commit()
        host.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
        host.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
        // A session has to exist: outside one the wheel keeps the car's own action.
        controller = mock(CarPlayController::class.java)
        `when`(controller.sendMediaButton(anyInt())).thenReturn(true)
        field("controller", controller)
        field("activeAirPlaySession", mock(AirPlaySession::class.java))
    }

    @After fun tearDown() {
        for (name in listOf("teardownExecutor", "airPlayCommandExecutor")) {
            (host.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(host) as ExecutorService).shutdownNow()
        }
        (host.javaClass.getDeclaredField("mainHandler").apply { isAccessible = true }.get(host) as Handler)
            .removeCallbacksAndMessages(null)
        BydCarPlayCall.end()
        CarPlayBackgroundSession.clear()
        host.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun theWheelTrackKeysReachCarPlay() {
        assertTrue(host.dispatchKeyEvent(key(KeyEvent.KEYCODE_MEDIA_NEXT, true)))
        verify(controller).sendMediaButton(CarPlayMediaButton.NEXT)
        assertTrue(host.dispatchKeyEvent(key(KeyEvent.KEYCODE_MEDIA_PREVIOUS, true)))
        verify(controller).sendMediaButton(CarPlayMediaButton.PREVIOUS)
    }

    @Test fun oneTapSendsOnePress() {
        // BYD sends a tap as an instant down/up pair, and a hold as repeats. Either way CarPlay must
        // see one press, and the release must not reach BYD's own player as an unpaired key.
        assertTrue(host.dispatchKeyEvent(key(KeyEvent.KEYCODE_MEDIA_NEXT, true)))
        assertTrue(host.dispatchKeyEvent(key(KeyEvent.KEYCODE_MEDIA_NEXT, true, repeat = 1)))
        assertTrue(host.dispatchKeyEvent(key(KeyEvent.KEYCODE_MEDIA_NEXT, false)))
        verify(controller, times(1)).sendMediaButton(anyInt())
    }

    @Test fun theKeysKeepTheCarsOwnActionDuringACall() {
        BydCarPlayCall.onFrame(Iap2Messages.buildRaw(CarPlayCallState.CALL_STATE_UPDATE) {
            u8(2, 4); string(4, "active-call")
        })
        assertEquals(AudioManager.MODE_NORMAL, host.getSystemService(AudioManager::class.java).mode)
        assertTrue(inCall(host))
        assertFalse(host.dispatchKeyEvent(key(KeyEvent.KEYCODE_MEDIA_NEXT, true)))
        verify(controller, never()).sendMediaButton(anyInt())
    }

    @Test fun theKeysKeepTheCarsOwnActionWithoutASession() {
        field("activeAirPlaySession", null)
        assertFalse(host.dispatchKeyEvent(key(KeyEvent.KEYCODE_MEDIA_NEXT, true)))
        verify(controller, never()).sendMediaButton(anyInt())
    }

    @Test fun theWheelsOwnPlayPauseKeyReachesCarPlayWithoutTheService() {
        // 293 is the DiLink 3 wheel's play/pause code and 113 is the label some firmware builds use
        // for the same press. They are not standard keys, so the window can claim them for CarPlay.
        assertTrue(host.dispatchKeyEvent(key(CarPlayMediaButton.KEYCODE_BYD_WHEEL_PLAY_PAUSE, true)))
        assertTrue(host.dispatchKeyEvent(key(CarPlayMediaButton.KEYCODE_BYD_WHEEL_PLAY_PAUSE_ALT, true)))
        verify(controller, times(2)).sendMediaButton(CarPlayMediaButton.PLAY_PAUSE)
    }

    @Test fun theWheelsPlayPauseKeySendsOnePressPerTap() {
        assertTrue(host.dispatchKeyEvent(key(CarPlayMediaButton.KEYCODE_BYD_WHEEL_PLAY_PAUSE, true)))
        assertTrue(host.dispatchKeyEvent(key(CarPlayMediaButton.KEYCODE_BYD_WHEEL_PLAY_PAUSE, true, repeat = 1)))
        assertTrue(host.dispatchKeyEvent(key(CarPlayMediaButton.KEYCODE_BYD_WHEEL_PLAY_PAUSE, false)))
        verify(controller, times(1)).sendMediaButton(anyInt())
    }

    @Test fun theWheelsPlayPauseKeyKeepsTheCarsOwnActionDuringACallAndWithoutASession() {
        field("activeAirPlaySession", null)
        assertFalse(host.dispatchKeyEvent(key(CarPlayMediaButton.KEYCODE_BYD_WHEEL_PLAY_PAUSE, true)))
        field("activeAirPlaySession", mock(AirPlaySession::class.java))
        BydCarPlayCall.onFrame(Iap2Messages.buildRaw(CarPlayCallState.CALL_STATE_UPDATE) {
            u8(2, 4); string(4, "call")
        })
        assertFalse(host.dispatchKeyEvent(key(CarPlayMediaButton.KEYCODE_BYD_WHEEL_PLAY_PAUSE, true)))
        verify(controller, never()).sendMediaButton(anyInt())
    }

    @Test fun everyPlayPauseCodeTheWheelCanSendReachesCarPlay() {
        // Firmware rewrites the wheel's play/pause button differently, and the CarPlay screen is the
        // only route left on a head unit where the wheel key service cannot be turned on.
        for (keyCode in listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE, CarPlayMediaButton.KEYCODE_BYD_AUTO_MEDIA_PLAY_PAUSE,
            CarPlayMediaButton.KEYCODE_BYD_AUTO_MEDIA_PLAY_PAUSE_DILINK3)) {
            assertTrue("keyCode=$keyCode", host.dispatchKeyEvent(key(keyCode, true)))
        }
        verify(controller, times(5)).sendMediaButton(CarPlayMediaButton.PLAY_PAUSE)
    }

    @Test fun theKeysTheWindowDoesNotOwnStillReachTheCar() {
        assertFalse(host.dispatchKeyEvent(key(KeyEvent.KEYCODE_VOLUME_UP, true)))
        assertFalse(host.dispatchKeyEvent(key(KeyEvent.KEYCODE_MEDIA_STOP, true)))
        verify(controller, never()).sendMediaButton(anyInt())
    }

    @Test fun assignedNonMediaKeyTogglesCarPlayPlaybackOnceWithoutAccessibilityService() {
        WheelZoomSettings.setMediaPlayPause(host, true)
        WheelZoomSettings.assign(host, WheelZoomSettings.Role.MEDIA_PLAY_PAUSE,
            WheelKey(KeyEvent.KEYCODE_F9, 0, "?"))
        assertTrue(host.dispatchKeyEvent(key(KeyEvent.KEYCODE_F9, true)))
        assertTrue(host.dispatchKeyEvent(key(KeyEvent.KEYCODE_F9, true, repeat = 1)))
        assertTrue(host.dispatchKeyEvent(key(KeyEvent.KEYCODE_F9, false)))
        verify(controller, times(1)).sendMediaButton(CarPlayMediaButton.PLAY_PAUSE)
    }

    @Test fun remappedPlayPauseKeyNeverStealsACallOrAnAbsentSession() {
        WheelZoomSettings.setMediaPlayPause(host, true)
        WheelZoomSettings.assign(host, WheelZoomSettings.Role.MEDIA_PLAY_PAUSE,
            WheelKey(KeyEvent.KEYCODE_F9, 0, "?"))
        field("activeAirPlaySession", null)
        assertFalse(host.dispatchKeyEvent(key(KeyEvent.KEYCODE_F9, true)))
        field("activeAirPlaySession", mock(AirPlaySession::class.java))
        BydCarPlayCall.onFrame(Iap2Messages.buildRaw(CarPlayCallState.CALL_STATE_UPDATE) {
            u8(2, 4); string(4, "call")
        })
        assertFalse(host.dispatchKeyEvent(key(KeyEvent.KEYCODE_F9, true)))
        verify(controller, never()).sendMediaButton(anyInt())
    }

    @Test fun fireTvNavigationRemoteButtonsRemainReservedEvenIfMappedToPlayPause() {
        WheelZoomSettings.setMediaPlayPause(host, true)
        for (code in listOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
        )) {
            WheelZoomSettings.assign(host, WheelZoomSettings.Role.MEDIA_PLAY_PAUSE,
                WheelKey(code, 0, "?"))
            assertFalse("TV navigation key must not be remapped: $code",
                WheelZoomSettings.isMediaPlayPauseKey(host, WheelKey(code, 0, "?")))
        }
        verify(controller, never()).sendMediaButton(anyInt())
    }

    @Test fun googleTvHomeAssistantVolumeAndMediaSessionKeysAreNotHijacked() {
        WheelZoomSettings.setMediaPlayPause(host, true)
        for (code in listOf(
            KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_VOICE_ASSIST,
            KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_SEARCH,
        )) {
            WheelZoomSettings.assign(host, WheelZoomSettings.Role.MEDIA_PLAY_PAUSE,
                WheelKey(code, 0, "?"))
            assertFalse("reserved TV key $code was incorrectly mapped",
                WheelZoomSettings.isMediaPlayPauseKey(host, WheelKey(code, 0, "?")))
        }
        verify(controller, never()).sendMediaButton(anyInt())
    }

    @Test fun originalTrackSkipAndMediaSessionPlayPauseRemainWhenRemapIsEnabled() {
        WheelZoomSettings.setMediaPlayPause(host, true)
        for (code in listOf(
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        )) {
            WheelZoomSettings.assign(host, WheelZoomSettings.Role.MEDIA_PLAY_PAUSE,
                WheelKey(code, 0, "?"))
            assertFalse(WheelZoomSettings.isMediaPlayPauseKey(host, WheelKey(code, 0, "?")))
            // Every one of them is claimed by the CarPlay screen itself, so a remap cannot take
            // them and the car's own action is not what a driver expects here.
            assertTrue("keyCode=$code must still reach CarPlay", host.dispatchKeyEvent(key(code, true)))
        }
        verify(controller).sendMediaButton(CarPlayMediaButton.NEXT)
        verify(controller).sendMediaButton(CarPlayMediaButton.PREVIOUS)
        verify(controller, times(1)).sendMediaButton(CarPlayMediaButton.PLAY_PAUSE)
    }

    @Test fun enablingMediaRemapWithoutAnAssignedKeyLeavesExistingTvControlsUnchanged() {
        host.resources.configuration.touchscreen = Configuration.TOUCHSCREEN_NOTOUCH
        `when`(controller.sendKnob(AirPlayKnobState(select = true), false)).thenReturn(true)
        `when`(controller.sendKnob(AirPlayKnobState(), false)).thenReturn(true)
        WheelZoomSettings.setMediaPlayPause(host, false)
        assertTrue(host.dispatchKeyEvent(key(KeyEvent.KEYCODE_DPAD_CENTER, true)))
        assertTrue(host.dispatchKeyEvent(key(KeyEvent.KEYCODE_DPAD_CENTER, false)))
        WheelZoomSettings.setMediaPlayPause(host, true)
        // Enabling the feature alone, without learning a key, cannot steal remote OK.
        assertTrue(host.dispatchKeyEvent(key(KeyEvent.KEYCODE_DPAD_CENTER, true)))
        assertTrue(host.dispatchKeyEvent(key(KeyEvent.KEYCODE_DPAD_CENTER, false)))
        verify(controller, times(2)).sendKnob(AirPlayKnobState(select = true), false)
        verify(controller, times(2)).sendKnob(AirPlayKnobState(), false)
        verify(controller, never()).sendMediaButton(anyInt())
    }

    @Test fun fireTvOkAndDirectionStillControlCarPlayAfterMediaFeatureEnabled() {
        host.resources.configuration.touchscreen = Configuration.TOUCHSCREEN_NOTOUCH
        assertTrue(AndroidTvInputMode.shouldUseKnobAsPrimaryInput(host))
        WheelZoomSettings.setMediaPlayPause(host, true)
        `when`(controller.sendKnob(AirPlayKnobState(select = true), false)).thenReturn(true)
        `when`(controller.sendKnob(AirPlayKnobState(), false)).thenReturn(true)
        `when`(controller.sendKnob(AirPlayKnobState(wheel = 1), true)).thenReturn(true)
        for ((keyCode, press, momentary) in listOf(
            Triple(KeyEvent.KEYCODE_DPAD_CENTER, AirPlayKnobState(select = true), false),
            Triple(KeyEvent.KEYCODE_DPAD_RIGHT, AirPlayKnobState(wheel = 1), true),
        )) {
            WheelZoomSettings.assign(host, WheelZoomSettings.Role.MEDIA_PLAY_PAUSE,
                WheelKey(keyCode, 0, "?"))
            assertTrue(host.dispatchKeyEvent(key(keyCode, true)))
            verify(controller).sendKnob(press, momentary)
            assertTrue(host.dispatchKeyEvent(key(keyCode, false)))
        }
        verify(controller, never()).sendMediaButton(anyInt())
    }

    private fun field(name: String, value: Any?) = host.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(host, value)

    private fun key(code: Int, down: Boolean, repeat: Int = 0) =
        KeyEvent(1_000, 1_000, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
            code, repeat, 0, -1, 0)
}
