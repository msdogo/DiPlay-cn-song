package com.shilapi.xcertplay

import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File

/**
 * The Diagnostics card shares and deletes reports that are already saved: no second report is
 * generated, one report still uses ACTION_SEND, and a batch uses ACTION_SEND_MULTIPLE.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en", shadows = [FileProviderPathTestShadow::class])
class DiagnosticReportPickerTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val reports get() = File(context.getExternalFilesDir(null)!!, "diagnostic-reports")

    @Before fun cleanReports() {
        reports.deleteRecursively()
        File(context.filesDir, "diagnostic-reports").deleteRecursively()
        DiagnosticExportStore.publicDownloadsDirectory().deleteRecursively()
        registerReportProvider()
    }

    /** FileProvider caches paths statically; Robolectric gives every test a new filesDir. */
    private fun registerReportProvider() {
        val authority = context.packageName + ".diagnostic-reports"
        val info = context.packageManager.resolveContentProvider(
            authority, android.content.pm.PackageManager.GET_META_DATA,
        )!!
        val provider = DiagnosticReportProvider().also { it.attachInfo(context, info) }
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(authority, provider)
    }

    private fun seed(count: Int) {
        reports.mkdirs()
        repeat(count) { index ->
            val file = File(reports, "DiPlay-0" + index + ".txt")
            file.writeText("report " + index)
            file.setLastModified(System.currentTimeMillis() - index * 1_000L)
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun openDiagnostics(): DiPlayActivity {
        val activity = Robolectric.buildActivity(
            DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings"),
        ).setup().get()
        descendants(activity.window.decorView)
            .first {
                it.contentDescription == activity.getString(
                    R.string.settings_open_category, activity.getString(R.string.diagnostics),
                )
            }
            .performClick()
        repeat(5) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        return activity
    }

    private fun idleUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
    }

    private fun awaitDialog(): android.app.AlertDialog {
        val deadline = System.nanoTime() + 5_000_000_000L
        while ((ShadowAlertDialog.getLatestAlertDialog() == null || !dialogShown()) && System.nanoTime() < deadline) {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
        shadowOf(Looper.getMainLooper()).idle()
        return requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
    }

    private fun dialogShown(): Boolean = ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true

    /** Robolectric never lays rows out, so drive the listener the dialog installed. */
    private fun toggle(dialog: android.app.AlertDialog, index: Int) {
        val list = dialog.listView
        val listener = requireNotNull(list.onItemClickListener) { "dialog installed no item listener" }
        list.setItemChecked(index, !list.isItemChecked(index))
        listener.onItemClick(list, null, index, list.adapter.getItemId(index))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun buttons(screen: DiPlayActivity, id: Int): TextView =
        descendants(screen.window.decorView)
            .filterIsInstance<TextView>()
            .first { it.text == screen.getString(id) }

    private fun awaitStartedActivity(screen: DiPlayActivity): Intent {
        idleUntil { shadowOf(screen).peekNextStartedActivity() != null }
        return requireNotNull(shadowOf(screen).peekNextStartedActivity()) {
            "no activity was started; files=" + reports.listFiles()?.map { it.name }
        }
    }

    @Test fun theShareButtonListsEverySavedReport() {
        seed(3)
        val screen = openDiagnostics()
        idleUntil { true }
        buttons(screen, R.string.share_diagnostic_reports).performClick()
        val dialog = awaitDialog()
        val list = requireNotNull(dialog.listView)
        assertEquals(3, list.adapter.count)
        val labels = (0 until list.adapter.count).map { list.adapter.getItem(it).toString() }
        // A row is the bare file name: no time prefix, no size prefix, no separator.
        assertTrue("rows must be file names: " + labels, labels.all { it.startsWith("DiPlay-") && it.endsWith(".txt") })
        assertTrue("no separator leftovers: " + labels, labels.none { it.contains(" · ") })
        assertEquals(labels.distinct().size, labels.size)
        dialog.dismiss()
    }

    @Test fun selectingTwoReportsSharesThemWithSendMultiple() {
        seed(3)
        val screen = openDiagnostics()
        idleUntil { true }
        buttons(screen, R.string.share_diagnostic_reports).performClick()
        val dialog = awaitDialog()
        toggle(dialog, 0)
        toggle(dialog, 1)
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        val chooser = awaitStartedActivity(screen)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND_MULTIPLE, send.action)
        assertEquals(2, send.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)!!.size)
        assertEquals(2, send.clipData!!.itemCount)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test fun selectingOneReportKeepsThePlainSendIntent() {
        seed(1)
        val screen = openDiagnostics()
        idleUntil { true }
        buttons(screen, R.string.share_diagnostic_reports).performClick()
        val dialog = awaitDialog()
        toggle(dialog, 0)
        assertTrue(dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled)
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        val chooser = awaitStartedActivity(screen)
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals(context.getString(R.string.report_clip_label), send.clipData!!.description.label.toString())
    }

    @Test fun sharingNeverCreatesANewReport() {
        seed(2)
        val screen = openDiagnostics()
        idleUntil { true }
        val before = reports.listFiles()!!.map { it.name }.toSet()
        buttons(screen, R.string.share_diagnostic_reports).performClick()
        val dialog = awaitDialog()
        toggle(dialog, 0)
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        awaitStartedActivity(screen)
        assertEquals(before, reports.listFiles()!!.map { it.name }.toSet())
    }

    @Test fun selectAllTicksEveryRow() {
        seed(4)
        val screen = openDiagnostics()
        idleUntil { true }
        buttons(screen, R.string.share_diagnostic_reports).performClick()
        val dialog = awaitDialog()
        val neutral = dialog.getButton(DialogInterface.BUTTON_NEUTRAL)
        assertEquals(screen.getString(R.string.select_all), neutral.text.toString())
        assertEquals("the driver must be able to see it", View.VISIBLE, neutral.visibility)
        neutral.performClick()
        val list = requireNotNull(dialog.listView)
        assertTrue((0 until list.count).all { list.isItemChecked(it) })
        neutral.performClick()
        assertTrue((0 until list.count).none { list.isItemChecked(it) })
        dialog.dismiss()
    }

    @Test fun deletingChosenReportsAsksFirstAndThenRemovesThem() {
        seed(2)
        val screen = openDiagnostics()
        idleUntil { true }
        buttons(screen, R.string.manage_diagnostic_reports).performClick()
        val picker = awaitDialog()
        toggle(picker, 0)
        picker.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val confirmation = awaitDialog()
        assertNull(confirmation.listView)
        assertEquals(
            screen.getString(R.string.delete_diagnostic_reports_message),
            shadowOf(confirmation).message?.toString(),
        )
        assertEquals("nothing is removed before the confirmation", 2, reports.listFiles()!!.size)
        confirmation.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        idleUntil { reports.listFiles()?.size == 1 }
        assertEquals(1, reports.listFiles()!!.size)
    }

    @Test fun savingANewReportReEnablesBothButtonsAfterEveryReportWasDeleted() {
        // The card renders while the folder is empty, so both buttons start disabled.
        val screen = openDiagnostics()
        idleUntil { false }
        assertFalse(buttons(screen, R.string.share_diagnostic_reports).isEnabled)
        assertFalse(buttons(screen, R.string.manage_diagnostic_reports).isEnabled)

        // Saving must hand the freshly written report back to the card, not leave it stale.
        val export = DiPlayActivity::class.java.getDeclaredMethod("exportDiagnostics", android.net.Uri::class.java)
        export.isAccessible = true
        export.invoke(screen, null)
        idleUntil { reports.listFiles()?.isNotEmpty() == true }
        idleUntil { buttons(screen, R.string.share_diagnostic_reports).isEnabled }
        assertTrue(reports.listFiles()!!.isNotEmpty())
        assertTrue(buttons(screen, R.string.share_diagnostic_reports).isEnabled)
        assertTrue(buttons(screen, R.string.manage_diagnostic_reports).isEnabled)
    }

    @Test fun withNoReportsBothButtonsStayDisabled() {
        val screen = openDiagnostics()
        idleUntil { false }
        assertFalse(buttons(screen, R.string.share_diagnostic_reports).isEnabled)
        assertFalse(buttons(screen, R.string.manage_diagnostic_reports).isEnabled)
    }
}
