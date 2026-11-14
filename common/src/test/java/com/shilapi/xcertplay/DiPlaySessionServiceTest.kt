package com.shilapi.xcertplay

import android.content.Intent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/**
 * BYD drops DiPlay's CarPlay task whenever another car app - or DiPlay's own home page after the
 * back key - comes to the front, while the connection process keeps running. Removing that task
 * must not tear the session down; only a real disconnect may.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class DiPlaySessionServiceTest {
    private lateinit var service: DiPlaySessionService
    private var stops = 0

    @Before fun setUp() {
        stops = 0
        CarPlayBackgroundSession.clear()
        service = Robolectric.buildService(DiPlaySessionService::class.java).create().get()
    }

    @After fun tearDown() {
        CarPlayBackgroundSession.clear()
    }

    private fun runningSession() {
        val stop: ((() -> Unit) -> Unit) = { done -> stops++; done() }
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", stop)
    }

    @Test fun removingTheCarPlayTaskKeepsTheRunningSessionAndItsNotification() {
        runningSession()

        service.onTaskRemoved(null)

        assertTrue(CarPlayBackgroundSession.hasSession())
        assertEquals(0, stops)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test fun removingTheTaskWithoutASessionStillCleansUpAndStopsTheService() {
        service.onTaskRemoved(null)

        assertEquals(0, stops)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun theDisconnectActionStillTearsTheSessionDown() {
        runningSession()

        service.onStartCommand(Intent(service, DiPlaySessionService::class.java).setAction(DiPlaySessionService.ACTION_STOP), 0, 1)

        // The stored action is the real shutdown()+finish(); the harness counts its invocation.
        assertEquals(1, stops)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }
}
