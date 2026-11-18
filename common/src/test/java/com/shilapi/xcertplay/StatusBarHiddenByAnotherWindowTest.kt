package com.shilapi.xcertplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusBarHiddenByAnotherWindowTest {
    private fun session(hideTopBar: Boolean = false, statusBarShown: Boolean? = true) = CarPlaySessionDisplay(
        width = 1280, height = 660, rotation = 0, hideTopBar = hideTopBar, hideBottomBar = false,
        windowWidth = 1280, windowHeight = 660, statusBarShown = statusBarShown,
    )

    @Test fun theReversingCameraHidingTheStatusBarKeepsTheSession() {
        // #643: 1280x660 grew to 1280x720 during reverse, CarPlay restarted at 720 and stayed scaled down at 660.
        assertTrue(statusBarHiddenByAnotherWindow(session(), 1280, 720, statusBarShown = false))
    }

    @Test fun growthWithTheStatusBarStillShownIsANewWindow() {
        assertFalse(statusBarHiddenByAnotherWindow(session(), 1280, 720, statusBarShown = true))
        assertFalse(statusBarHiddenByAnotherWindow(session(), 1280, 720, statusBarShown = null))
    }

    @Test fun aDriverWhoHidesTheStatusBarGetsTheLargerCanvas() {
        // #536: DiPlay hid the bar itself, so the larger window is the one the driver chose.
        assertFalse(statusBarHiddenByAnotherWindow(session(hideTopBar = true), 1280, 720, statusBarShown = false))
    }

    @Test fun aSessionStartedWithoutAStatusBarIsNotAffected() {
        assertFalse(statusBarHiddenByAnotherWindow(session(statusBarShown = false), 1280, 720, statusBarShown = false))
        assertFalse(statusBarHiddenByAnotherWindow(session(statusBarShown = null), 1280, 720, statusBarShown = false))
    }

    @Test fun onlyAHeightGrowthAtTheSameWidthCounts() {
        assertFalse(statusBarHiddenByAnotherWindow(session(), 1400, 720, statusBarShown = false))
        assertFalse(statusBarHiddenByAnotherWindow(session(), 1280, 660, statusBarShown = false))
    }

    /** The canvas of a window with different bars, at a different shape than the window that follows it. */
    private fun canvasSession(canvasWidth: Int, canvasHeight: Int, windowWidth: Int = 1280, windowHeight: Int = 800) =
        CarPlaySessionDisplay(
            width = canvasWidth, height = canvasHeight, rotation = 0, hideTopBar = false, hideBottomBar = false,
            windowWidth = windowWidth, windowHeight = windowHeight,
        )

    @Test fun aCameraShrinkOfTheSameShapeStillKeepsTheCanvas() {
        // The measured camera cycle: 1920x990, the camera takes 48 px off the height (5% shape change).
        val session = canvasSession(1920, 990)
        assertTrue(shorterWindowKeepsTheCanvas(session, 1920, 942))
    }

    @Test fun aWindowThatLostASystemBarRenegotiatesWhenThePictureNoLongerFits() {
        // The old failure: a 1280x800 canvas below the returning bars of a 1280x660 window stayed
        // letterboxed on both sides until the driver disconnected and connected again.
        val session = canvasSession(1280, 800)
        assertFalse(shorterWindowKeepsTheCanvas(session, 1280, 660))
    }

    @Test fun growthAndWidthChangesAreNotTheCameraCase() {
        val session = canvasSession(1280, 800)
        assertTrue(shorterWindowKeepsTheCanvas(session, 1280, 800))
        assertTrue(shorterWindowKeepsTheCanvas(session, 1280, 900))
        assertTrue(shorterWindowKeepsTheCanvas(session, 1340, 700))
    }
}
