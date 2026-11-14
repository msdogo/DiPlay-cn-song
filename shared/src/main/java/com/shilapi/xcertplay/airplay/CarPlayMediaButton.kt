package com.shilapi.xcertplay.airplay

import android.view.KeyEvent

/**
 * Hardware media keys → CarPlay media HID presses (indices into [AirPlayHid]'s media report).
 *
 * Hardware play and pause keys both map to the toggle: BYD picks PLAY or PAUSE from its own idea of
 * the play state, and a wrong guess would make the button do nothing. Explicit play and pause
 * commands from media controllers use [PLAY] and [PAUSE].
 */
object CarPlayMediaButton {
    const val PLAY = 1
    const val PAUSE = 2
    const val PLAY_PAUSE = 3
    const val NEXT = 4
    const val PREVIOUS = 5

    /** BYD's steering-wheel play/pause key; the firmware normally rewrites it to MEDIA_PLAY/PAUSE. */
    const val KEYCODE_BYD_AUTO_MEDIA_PLAY_PAUSE = 353

    /** BYD's steering-wheel voice key: a short press, and the code the wheel sends for a long press. */
    const val KEYCODE_BYD_AUTO_MEDIA_VOICE = 304
    const val KEYCODE_BYD_AUTO_MEDIA_VOICE_LONG = 312

    /** DiLink 3's play/pause key (KEYCODE_AUTO_MEDIA_PLAY_PAUSE), when the firmware does not rewrite it. */
    const val KEYCODE_BYD_AUTO_MEDIA_PLAY_PAUSE_DILINK3 = 331

    /**
     * DiLink 3's CarPlay voice key, short and long. BYD's window manager keeps it for its own CarPlay
     * app, so only the wheel key service sees it (see [opensSiriWhileCarPlay]).
     */
    const val KEYCODE_BYD_CARPLAY_VOICE = 327
    const val KEYCODE_BYD_CARPLAY_VOICE_LONG = 328

    /**
     * The DiLink 3 steering wheel's own play/pause button. The wheel sends it as 293; some firmware
     * builds label the same press 113, so both codes are claimed and the button works either way.
     * Neither is a standard Android key, so claiming them here cannot take a key away from the car.
     */
    const val KEYCODE_BYD_WHEEL_PLAY_PAUSE = 293
    const val KEYCODE_BYD_WHEEL_PLAY_PAUSE_ALT = 113

    /**
     * Whether [keyCode] is a voice key that opens Siri. The BYD wheel sends each press as an
     * instant down/up pair, so a long press arrives as its own key rather than as a held one.
     */
    fun opensSiri(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_VOICE_ASSIST ||
        keyCode == KEYCODE_BYD_AUTO_MEDIA_VOICE || keyCode == KEYCODE_BYD_AUTO_MEDIA_VOICE_LONG

    /** The voice keys BYD sends only for CarPlay; they open Siri while a CarPlay session runs. */
    fun opensSiriWhileCarPlay(keyCode: Int): Boolean =
        keyCode == KEYCODE_BYD_CARPLAY_VOICE || keyCode == KEYCODE_BYD_CARPLAY_VOICE_LONG

    /** The two voice keys the wheel sends for a short press of its voice button. */
    fun isShortVoiceKey(keyCode: Int): Boolean =
        keyCode == KEYCODE_BYD_CARPLAY_VOICE || keyCode == KEYCODE_BYD_AUTO_MEDIA_VOICE

    /** The CarPlay press for [keyCode], or null when the key is not a media key CarPlay handles. */
    fun forKeyCode(keyCode: Int, experimentalDiLink3Keys: Boolean = false): Int? = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_NEXT -> NEXT
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> PREVIOUS
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_HEADSETHOOK,
        KEYCODE_BYD_AUTO_MEDIA_PLAY_PAUSE -> PLAY_PAUSE
        KEYCODE_BYD_WHEEL_PLAY_PAUSE, KEYCODE_BYD_WHEEL_PLAY_PAUSE_ALT -> PLAY_PAUSE
        KEYCODE_BYD_AUTO_MEDIA_PLAY_PAUSE_DILINK3 -> if (experimentalDiLink3Keys) PLAY_PAUSE else null
        else -> null
    }

    /**
     * The CarPlay press the CarPlay screen itself should claim for [keyCode], or null to let the key
     * go on to the car.
     *
     * BYD hands the steering wheel's next/previous keys to whichever window is in front, and DiPlay's
     * CarPlay screen is that window whenever the driver is looking at it. Claiming them here is what
     * makes the wheel change tracks without the accessibility service, which a head unit with no ADB
     * cannot turn on.
     *
     * Next, previous and every code a BYD wheel uses for play/pause are claimed: the wheel's own
     * [KEYCODE_BYD_WHEEL_PLAY_PAUSE] and [KEYCODE_BYD_WHEEL_PLAY_PAUSE_ALT], the standard media
     * codes, and BYD's 353 and 331. The head unit rewrites the button to one of those depending on
     * the firmware, and only the window route is left when the wheel key service cannot be turned
     * on, so the whole transport row has to work here.
     *
     * Claiming play/pause in the window costs the car nothing outside this screen: the CarPlay
     * screen is only in front while the driver is watching CarPlay, and the key's BYD action (its
     * own player) is unreachable from there anyway. Nothing is claimed outside a CarPlay session,
     * or during a call, where the car's own call handling is what the driver expects from these
     * keys.
     */
    fun forCarPlayWindow(
        keyCode: Int,
        sessionActive: Boolean,
        inCall: Boolean,
    ): Int? {
        if (!sessionActive || inCall) return null
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> NEXT
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> PREVIOUS
            // The same press reaches the window under different codes depending on the firmware:
            // BYD's own 293 / 113, the standard play/pause codes it rewrites them to, and the
            // DiLink 3 codes 353 / 331 when the firmware does not rewrite them at all.
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KEYCODE_BYD_AUTO_MEDIA_PLAY_PAUSE,
            KEYCODE_BYD_AUTO_MEDIA_PLAY_PAUSE_DILINK3,
            KEYCODE_BYD_WHEEL_PLAY_PAUSE,
            KEYCODE_BYD_WHEEL_PLAY_PAUSE_ALT -> PLAY_PAUSE
            else -> null
        }
    }
}
