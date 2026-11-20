package com.shilapi.xcertplay

import android.os.Handler
import android.os.SystemClock
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.CarPlayMediaButton

/**
 * BYD's spare steering-wheel key (294, scan 288, device 5) and the ten-second quiet window that
 * follows every Siri long press.
 *
 * Nothing on the head unit answers the spare key - the car's own apps ignore it, and the
 * accessibility input filter never receives it - but the CarPlay window does get every press, which
 * is what makes it usable: a tap and a hold can be given meanings of their own without taking a key
 * away from the car.
 *
 * The head unit sends this key as a single code, so it never reports a long press of its own. The
 * hold is timed here, the same way the voice keys are timed after [WheelSiriHold].
 */
internal object ExtraWheelKey {
    const val KEYCODE = 294
    const val SCAN_CODE = 288

    /**
     * How long next/previous stay quiet after a Siri long press. The driver is talking to Siri with
     * the wheel in hand, and a track change would be the last thing they meant to ask for. Every
     * route that opens Siri - the spare key and the wheel's own voice keys - starts this window.
     */
    const val SIRI_QUIET_MILLIS = 10_000L

    private var quietUntil = 0L

    fun handles(keyCode: Int): Boolean = keyCode == KEYCODE

    /**
     * Arms a hold on the press: [onShort] runs when the key comes back up first, [onLong] when
     * [holdMillis] pass with the key still down. A repeat of a held key changes nothing.
     *
     * Returns true when the event was DiPlay's to keep.
     */
    fun press(
        event: KeyEvent,
        handler: Handler,
        holdMillis: Long,
        onShort: () -> Unit,
        onLong: () -> Unit,
    ): Boolean {
        val holdKey = holdKey(event)
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    WheelSiriHold.arm(holdKey, handler, holdMillis) {
                        // "2" and Siri are the same press, so the quiet window starts with it.
                        openSiriQuiet()
                        onLong()
                    }
                }
                true
            }
            KeyEvent.ACTION_UP -> {
                // A hold that already fired took its own timer away, so only a real tap reaches this.
                if (WheelSiriHold.release(holdKey, handler)) onShort()
                true
            }
            else -> false
        }
    }

    /**
     * Watches the wheel's own voice keys for the press that opens Siri, without claiming them: they
     * still reach the head unit. The long-press code can arrive as its own key ([CarPlayMediaButton]
     * 312 / 328), and on some firmware only after DiPlay's own threshold, so both are watched.
     */
    fun observeVoiceKey(event: KeyEvent, handler: Handler, holdMillis: Long) {
        if (!isVoiceKey(event.keyCode)) return
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
            isVoiceLongPress(event.keyCode)) {
            openSiriQuiet()
            return
        }
        val holdKey = holdKey(event)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                WheelSiriHold.arm(holdKey, handler, holdMillis) { openSiriQuiet() }
            }
            KeyEvent.ACTION_UP -> WheelSiriHold.release(holdKey, handler)
            else -> Unit
        }
    }

    private fun isVoiceKey(keyCode: Int): Boolean =
        CarPlayMediaButton.opensSiri(keyCode) || CarPlayMediaButton.opensSiriWhileCarPlay(keyCode)

    private fun isVoiceLongPress(keyCode: Int): Boolean =
        keyCode == CarPlayMediaButton.KEYCODE_BYD_AUTO_MEDIA_VOICE_LONG ||
            keyCode == CarPlayMediaButton.KEYCODE_BYD_CARPLAY_VOICE_LONG

    /** Siri is about to talk to the driver: next/previous keep out of the way for a while. */
    fun openSiriQuiet(nowMillis: Long = SystemClock.uptimeMillis()) {
        quietUntil = nowMillis + SIRI_QUIET_MILLIS
    }

    /** True while next/previous should do nothing because a Siri long press just happened. */
    fun quietAfterSiri(nowMillis: Long = SystemClock.uptimeMillis()): Boolean =
        nowMillis < quietUntil

    /** True for the two CarPlay presses the quiet window holds back. */
    fun isTrackChange(press: Int): Boolean =
        press == CarPlayMediaButton.NEXT || press == CarPlayMediaButton.PREVIOUS

    /** The quiet window lives in memory, so a test or a reconnect can start clean. */
    fun resetQuiet() {
        quietUntil = 0L
    }

    private fun holdKey(event: KeyEvent) = "key-" + event.deviceId + "-" + event.keyCode + "-" + event.scanCode
}
