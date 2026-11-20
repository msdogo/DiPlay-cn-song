package com.shilapi.xcertplay

import android.os.Handler

/**
 * "Siri only after a long press", measured rather than guessed.
 *
 * A BYD wheel reports a long press as its own key code (328, and 312 on the CarPlay screen), but how
 * long the button has to be held before the head unit decides that is the firmware's business, and
 * it differs between builds. This waits on the press itself: the key is armed on its first down
 * event and Siri is asked for once [holdMillis] has passed with the key still held. A release before
 * then cancels the timer, so a short press does nothing at all.
 *
 * Shared by the wheel key service and the CarPlay window, which both see the same physical key.
 */
internal object WheelSiriHold {
    private val pending = mutableMapOf<String, Runnable>()

    /**
     * Arms [key] to fire [fire] once [holdMillis] have passed. A repeat of the same held key does
     * not arm a second timer. Returns true when this call armed it.
     */
    fun arm(key: String, handler: Handler, holdMillis: Long, fire: () -> Unit): Boolean {
        if (pending.containsKey(key)) return false
        val runnable = Runnable {
            pending.remove(key)
            fire()
        }
        pending[key] = runnable
        handler.postDelayed(runnable, holdMillis)
        return true
    }

    /**
     * Disarms [key]; returns how long it was held, or null when this release belongs to no armed
     * press (the key was never armed, or it already fired).
     */
    fun release(key: String, handler: Handler): Boolean {
        val runnable = pending.remove(key) ?: return false
        handler.removeCallbacks(runnable)
        return true
    }

    /** True while [key] is waiting for its hold to complete. */
    fun armed(key: String): Boolean = pending.containsKey(key)

    fun cancelAll(handler: Handler) {
        pending.values.forEach(handler::removeCallbacks)
        pending.clear()
    }
}
