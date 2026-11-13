package com.shilapi.xcertplay

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What DiPlay did about the wheel key service, kept on disk so the diagnostic report can show it after
 * the app process is gone. The failure this explains spans restarts: BYD stops DiPlay, Android marks the
 * service crashed, and the next process writes the report knowing nothing of the attempt that failed
 * (#606). A head unit offers no usable logcat, so the report is the only place this can be read.
 *
 * Only binding and repair events are written, never a key press: a write on a key would delay the head
 * unit's own handling, and [WheelKeyLog] already covers presses for the running process.
 */
internal object WheelKeyJournal {
    internal const val ENTRIES = 40
    private const val PREFS = "wheel_key_journal"
    private val started = AtomicBoolean(false)
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** Whether this process has written its first line yet, so the first screen records the state it found. */
    internal fun startedInThisProcess(): Boolean = started.get()

    @Synchronized
    fun append(context: Context, event: String, nowMillis: Long = System.currentTimeMillis()) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        if (started.compareAndSet(false, true)) {
            editor.putLong("sessionStart", nowMillis)
            editor.putInt("sessions", prefs.getInt("sessions", 0) + 1)
        }
        val lines = prefs.getString("entries", null).orEmpty().lineSequence()
            .filter { it.isNotBlank() }.toMutableList()
        lines += "${time.format(Date(nowMillis))} $event"
        while (lines.size > ENTRIES) lines.removeAt(0)
        editor.putString("entries", lines.joinToString("\n")).apply()
    }

    /**
     * The stored lines, oldest first, with the number of app runs they came from. The times are times of
     * day: a report is a snapshot, so what matters is the order and the gaps between the lines.
     */
    fun report(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val entries = prefs.getString("entries", null).orEmpty().lineSequence()
            .filter { it.isNotBlank() }.toList()
        val run = "sessions=${prefs.getInt("sessions", 0)}"
        if (entries.isEmpty()) return listOf("Wheel key service journal: nothing recorded yet ($run)")
        val start = prefs.getLong("sessionStart", 0L)
        val header = "Wheel key service journal: $run" +
            (if (start == 0L) "" else " thisSessionMs=${System.currentTimeMillis() - start}") +
            " entriesKept=${entries.size} newestLast"
        return (listOf(header) + entries.map { "Wheel key journal: $it" })
            .mapNotNull(DiagnosticRedactor::redact)
    }

    /** Starts the "this run has written" flag over, as a new app run does, keeping the stored lines. */
    internal fun resetProcessFlagForTest() {
        started.set(false)
    }

    @Synchronized
    internal fun reset(context: Context) {
        started.set(false)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }
}
