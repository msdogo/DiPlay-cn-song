package com.shilapi.xcertplay.airplay

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayMediaButtonTest {
    @Test
    fun steeringWheelKeysMapToCarPlayMediaPresses() {
        assertEquals(CarPlayMediaButton.NEXT, CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(CarPlayMediaButton.PREVIOUS, CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        // BYD rewrites its play/pause key into PLAY or PAUSE from the session state; both toggle.
        assertEquals(CarPlayMediaButton.PLAY_PAUSE, CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertEquals(CarPlayMediaButton.PLAY_PAUSE, CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertEquals(CarPlayMediaButton.PLAY_PAUSE, CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(CarPlayMediaButton.PLAY_PAUSE, CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_HEADSETHOOK))
        assertEquals(CarPlayMediaButton.PLAY_PAUSE, CarPlayMediaButton.forKeyCode(353))
        // The DiLink 3 wheel's own play/pause button, in both of the codes its firmware sends.
        assertEquals(CarPlayMediaButton.PLAY_PAUSE, CarPlayMediaButton.forKeyCode(293))
        assertEquals(CarPlayMediaButton.PLAY_PAUSE, CarPlayMediaButton.forKeyCode(113))
        // DiLink 3 (Han, GCC) sends KEYCODE_AUTO_MEDIA_PLAY_PAUSE unchanged.
        assertNull(CarPlayMediaButton.forKeyCode(331))
        assertEquals(CarPlayMediaButton.PLAY_PAUSE,
            CarPlayMediaButton.forKeyCode(331, experimentalDiLink3Keys = true))
    }

    @Test
    fun dilink3CarPlayVoiceKeysOpenSiriOnlyThroughTheWheelKeyService() {
        assertTrue(CarPlayMediaButton.opensSiriWhileCarPlay(327))
        assertTrue(CarPlayMediaButton.opensSiriWhileCarPlay(328))
        assertFalse(CarPlayMediaButton.opensSiriWhileCarPlay(304))
        assertFalse(CarPlayMediaButton.opensSiri(327))
    }

    @Test
    fun theWheelsShortVoiceKeysAreTheCarPlayAndGenericShortCodes() {
        assertTrue(CarPlayMediaButton.isShortVoiceKey(327))
        assertTrue(CarPlayMediaButton.isShortVoiceKey(304))
        assertFalse(CarPlayMediaButton.isShortVoiceKey(328))
        assertFalse(CarPlayMediaButton.isShortVoiceKey(312))
        assertFalse(CarPlayMediaButton.isShortVoiceKey(KeyEvent.KEYCODE_VOICE_ASSIST))
    }

    @Test
    fun theVoiceKeyOpensSiri() {
        // Recorded on DiLink 5.0: short press 304 (scan 290), long press 312 (scan 312).
        assertTrue(CarPlayMediaButton.opensSiri(304))
        assertTrue(CarPlayMediaButton.opensSiri(312))
        assertTrue(CarPlayMediaButton.opensSiri(KeyEvent.KEYCODE_VOICE_ASSIST))
        assertFalse(CarPlayMediaButton.opensSiri(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertNull(CarPlayMediaButton.forKeyCode(304))
    }

    @Test
    fun otherKeysAreLeftToTheSystem() {
        assertNull(CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_VOLUME_UP))
        assertNull(CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_MEDIA_STOP))
    }

    @Test
    fun indicesMatchTheAdvertisedMediaHidReport() {
        // Media report usages: 0 none, 1 play, 2 pause, 3 play/pause, 4 next, 5 previous.
        assertEquals(3, CarPlayMediaButton.PLAY_PAUSE)
        assertEquals(4, CarPlayMediaButton.NEXT)
        assertEquals(5, CarPlayMediaButton.PREVIOUS)
    }

    @Test
    fun theWindowClaimsTrackKeysOnlyWhileCarPlayIsRunning() {
        fun claim(keyCode: Int, sessionActive: Boolean = true, inCall: Boolean = false) =
            CarPlayMediaButton.forCarPlayWindow(keyCode, sessionActive, inCall)

        assertEquals(CarPlayMediaButton.NEXT, claim(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(CarPlayMediaButton.PREVIOUS, claim(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        // The wheel's own play/pause button is part of the same row and works the same way.
        assertEquals(CarPlayMediaButton.PLAY_PAUSE, claim(CarPlayMediaButton.KEYCODE_BYD_WHEEL_PLAY_PAUSE))
        assertEquals(CarPlayMediaButton.PLAY_PAUSE, claim(CarPlayMediaButton.KEYCODE_BYD_WHEEL_PLAY_PAUSE_ALT))
        // Without a session, or during a call, the wheel keeps the car's own action.
        assertNull(claim(KeyEvent.KEYCODE_MEDIA_NEXT, sessionActive = false))
        assertNull(claim(KeyEvent.KEYCODE_MEDIA_PREVIOUS, sessionActive = false))
        assertNull(claim(KeyEvent.KEYCODE_MEDIA_NEXT, inCall = true))
        assertNull(claim(KeyEvent.KEYCODE_MEDIA_PREVIOUS, inCall = true))
        assertNull(claim(CarPlayMediaButton.KEYCODE_BYD_WHEEL_PLAY_PAUSE, sessionActive = false))
        assertNull(claim(CarPlayMediaButton.KEYCODE_BYD_WHEEL_PLAY_PAUSE, inCall = true))
        assertNull(claim(CarPlayMediaButton.KEYCODE_BYD_WHEEL_PLAY_PAUSE_ALT, inCall = true))
    }

    @Test
    fun theWindowClaimsEveryPlayPauseCodeTheWheelCanSend() {
        // The head unit rewrites the wheel's play/pause button to one of these depending on the
        // firmware, and on a head unit with no ADB the window is the only route that is left, so
        // all of them have to be claimed together.
        for (keyCode in listOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK, 353, 331)) {
            assertEquals("keyCode=$keyCode", CarPlayMediaButton.PLAY_PAUSE,
                CarPlayMediaButton.forCarPlayWindow(keyCode, true, false))
        }
    }

    @Test
    fun theWindowLeavesUnrelatedKeysAlone() {
        for (keyCode in listOf(KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)) {
            assertNull("keyCode=$keyCode", CarPlayMediaButton.forCarPlayWindow(keyCode, true, false))
        }
    }
}
