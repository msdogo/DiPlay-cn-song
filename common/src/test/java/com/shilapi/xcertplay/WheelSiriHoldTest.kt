package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** "Siri only after a long press": the hold is timed here, not taken from the head unit's own key. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WheelSiriHoldTest {
    private val handler = Handler(Looper.getMainLooper())
    private val key = "voice-5-327-0"
    private var fired = 0

    @Before fun setUp() {
        WheelSiriHold.cancelAll(handler)
        fired = 0
    }

    @After fun tearDown() = WheelSiriHold.cancelAll(handler)

    private fun arm(holdMillis: Long = 2_500) =
        WheelSiriHold.arm(key, handler, holdMillis) { fired++ }

    @Test fun aHoldThatCompletesOpensSiriOnce() {
        assertTrue(arm())
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(2_400))
        assertEquals(0, fired)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
        assertEquals(1, fired)
        assertFalse(WheelSiriHold.armed(key))
    }

    @Test fun aReleaseBeforeTheHoldDoesNothing() {
        assertTrue(arm())
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1_000))
        assertTrue(WheelSiriHold.release(key, handler))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(3_000))
        assertEquals(0, fired)
    }

    @Test fun aHeldKeyRepeatingDoesNotArmTwice() {
        assertTrue(arm())
        assertFalse("a repeat of the same held key must not arm another timer", arm())
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(2_600))
        assertEquals(1, fired)
    }

    @Test fun aReleaseAfterTheHoldFiredIsNotAnArmedPress() {
        arm()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(2_600))
        assertEquals(1, fired)
        assertFalse(WheelSiriHold.release(key, handler))
    }

    @Test fun theConfiguredDurationIsWhatCounts() {
        arm(holdMillis = 800)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(700))
        assertEquals(0, fired)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(150))
        assertEquals(1, fired)
    }

    @Test fun cancelAllClearsEveryPendingHold() {
        arm()
        WheelSiriHold.cancelAll(handler)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(3_000))
        assertEquals(0, fired)
        assertFalse(WheelSiriHold.armed(key))
    }
}
