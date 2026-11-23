package com.shilapi.xcertplay

import android.content.ContentProvider
import android.content.ContentValues
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

/**
 * Saved-report management: the picker lists every report from all three write locations and
 * delete removes exactly the chosen files, never anything else (#reports).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [FileProviderPathTestShadow::class])
class DiagnosticReportStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val externalReports get() = File(context.getExternalFilesDir(null)!!, "diagnostic-reports")
    private val internalReports get() = File(context.filesDir, "diagnostic-reports")
    private val publicReports get() = DiagnosticExportStore.publicDownloadsDirectory()

    @Before fun cleanReports() {
        externalReports.deleteRecursively()
        internalReports.deleteRecursively()
        publicReports.deleteRecursively()
        registerReportProvider()
    }

    /** FileProvider caches paths statically; Robolectric gives every test a new filesDir. */
    private fun registerReportProvider() {
        val authority = context.packageName + ".diagnostic-reports"
        val info = context.packageManager.resolveContentProvider(
            authority, android.content.pm.PackageManager.GET_META_DATA,
        )!!
        val provider = DiagnosticReportProvider().also { it.attachInfo(context, info) }
        ShadowContentResolver.registerProviderInternal(authority, provider)
    }

    private fun report(directory: File, name: String, text: String, modifiedAt: Long): File {
        directory.mkdirs()
        val file = File(directory, name)
        file.writeText(text)
        file.setLastModified(modifiedAt)
        return file
    }

    @Test fun listReportsReturnsEveryLocationNewestFirst() {
        val now = System.currentTimeMillis()
        report(externalReports, "DiPlay-old.txt", "old", now - 20_000)
        report(internalReports, "DiPlay-new.txt", "new", now)
        report(publicReports, "DiPlay-middle.txt", "middle", now - 10_000)
        File(externalReports, "notes.md").writeText("not a report")

        val listed = DiagnosticExportStore.listReports(context)

        assertEquals(
            listOf("DiPlay-new.txt", "DiPlay-middle.txt", "DiPlay-old.txt"),
            listed.map { it.fileName },
        )
        assertEquals(
            DiagnosticExportStore.ReportSource.INTERNAL,
            listed.first { it.fileName == "DiPlay-new.txt" }.source,
        )
    }

    @Test fun deleteReportsRemovesOnlyTheChosenFiles() {
        val now = System.currentTimeMillis()
        val keep = report(externalReports, "DiPlay-keep.txt", "keep", now)
        report(internalReports, "DiPlay-one.txt", "one", now)
        report(publicReports, "DiPlay-two.txt", "two", now)

        val chosen = DiagnosticExportStore.listReports(context).filter { it.fileName != "DiPlay-keep.txt" }
        val (deleted, failed) = DiagnosticExportStore.deleteReports(context, chosen)

        assertEquals(2, deleted)
        assertEquals(0, failed)
        assertEquals(listOf(keep.name), externalReports.listFiles()!!.map { it.name })
        assertTrue(internalReports.listFiles().isNullOrEmpty())
        assertTrue(publicReports.listFiles().isNullOrEmpty())
        assertTrue("the report folders themselves stay", externalReports.isDirectory)
    }

    @Test fun deleteReportsCountsAnAlreadyMissingFileAsAFailure() {
        val file = report(internalReports, "DiPlay-gone.txt", "gone", System.currentTimeMillis())
        val entry = DiagnosticExportStore.listReports(context).single()
        assertTrue(file.delete())

        val (deleted, failed) = DiagnosticExportStore.deleteReports(context, listOf(entry))

        assertEquals(0, deleted)
        assertEquals(1, failed)
    }

    @Test fun shareUrisSkipsUnreadableEntriesInsteadOfFailingTheBatch() {
        val readable = report(externalReports, "DiPlay-readable.txt", "hello", System.currentTimeMillis())
        val listed = DiagnosticExportStore.listReports(context)

        val uris = DiagnosticExportStore.shareUris(context, listed)

        assertEquals(1, uris.size)
        assertEquals("content", uris.single().scheme)
        assertTrue(readable.isFile)
        assertEquals("hello", context.contentResolver.openInputStream(uris.single())!!
            .use { it.readBytes() }.toString(Charsets.UTF_8))
    }

    @Test @Config(sdk = [29]) fun aDownloadsEntryIsDeletedThroughMediaStore() {
        val resolver = context.contentResolver
        val directory = Environment.DIRECTORY_DOWNLOADS + "/DiPlay/"
        val provider = FakeDownloadsProvider(directory, "DiPlay-store.txt")
        provider.attachInfo(context, ProviderInfo().apply { authority = ANDROID_MEDIA })
        ShadowContentResolver.registerProviderInternal(ANDROID_MEDIA, provider)

        val entries = DiagnosticExportStore.listReports(context)
        val entry = entries.singleOrNull { it.fileName == "DiPlay-store.txt" }
            ?: error("not listed; calls=" + provider.calls + " listed=" + entries.map { it.fileName })
        assertEquals(DiagnosticExportStore.ReportSource.PUBLIC_DOWNLOADS, entry.source)

        val (deleted, failed) = DiagnosticExportStore.deleteReports(context, listOf(entry))

        assertEquals(1, deleted)
        assertEquals(0, failed)
        assertEquals("query:Download/DiPlay/:DiPlay-store.txt", provider.calls[2])
        assertEquals("delete:1", provider.calls.last())
    }

    private class FakeDownloadsProvider(
        private val relativePath: String,
        private val fileName: String,
    ) : ContentProvider() {
        val calls = mutableListOf<String>()
        private var existing = true

        override fun onCreate() = true

        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor? {
            calls += "query:" + (selectionArgs?.firstOrNull() ?: "") + ":" +
                (selectionArgs?.lastOrNull() ?: "")
            val columns = projection ?: arrayOf("_id", "display_name", "size", "date_modified")
            val matrix = android.database.MatrixCursor(columns)
            val matches = selectionArgs?.contains(relativePath) == true
            if (existing && matches) {
                // Every row must carry exactly the requested columns.
                val values = mapOf(
                    android.provider.MediaStore.Downloads._ID to 1L,
                    android.provider.MediaStore.Downloads.DISPLAY_NAME to fileName,
                    android.provider.MediaStore.Downloads.SIZE to 12L,
                    android.provider.MediaStore.Downloads.DATE_MODIFIED to 1_700_000_000L,
                )
                matrix.addRow(columns.map { values[it] ?: "" }.toTypedArray())
            }
            return matrix
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            calls += "delete:" + uri.lastPathSegment
            existing = false
            return 1
        }

        override fun getType(uri: Uri): String? = "text/plain"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    }

    private companion object {
        const val ANDROID_MEDIA = "media"
    }
}
