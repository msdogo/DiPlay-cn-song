package com.shilapi.xcertplay.backup

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.content.ContextWrapper
import android.database.Cursor
import android.net.Uri
import androidx.core.content.FileProvider
import com.shilapi.xcertplay.FileProviderPathTestShadow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
import java.io.IOException

/** The settings document as a file: Downloads first, the app's own storage as the last resort. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [FileProviderPathTestShadow::class])
class SettingsFileStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val backups get() = File(context.getExternalFilesDir(null)!!, "settings-backups")
    private val privateBackups get() = File(context.filesDir, "settings-backups")
    private val publicBackups get() = SettingsFileStore.publicDownloadsDirectory()
    private val document = "{\"format\":\"diplay-settings\",\"version\":1}\n".toByteArray()

    @Before fun cleanBackups() {
        backups.deleteRecursively()
        privateBackups.deleteRecursively()
        publicBackups.deleteRecursively()
        registerSettingsProvider()
    }

    @Test fun androidNineSavesTheDocumentWithoutAPickerOrStoragePermission() {
        val saved = SettingsFileStore.save(context, "DiPlay-settings.json", document)
        assertFalse(saved.inApp)
        val file = backups.listFiles()!!.single()
        assertArrayEquals(document, file.readBytes())
        assertEquals(file.absolutePath, saved.path)
        assertEquals("content", saved.uri.scheme)
        assertEquals(context.packageName + ".settings-backups", saved.uri.authority)
        val info = context.packageManager.resolveContentProvider(saved.uri.authority!!, 0)!!
        assertFalse(info.exported)
        assertTrue(info.grantUriPermissions)
    }

    @Test fun theSavedDocumentReadsBackUnchanged() {
        val saved = SettingsFileStore.save(context, "DiPlay-settings.json", document)
        assertArrayEquals(document, SettingsFileStore.read(context, saved.uri))
    }

    @Test fun anOversizedFileIsRejectedWithoutBeingLoaded() {
        val file = File(backups.apply { mkdirs() }, "huge.json")
        file.writeBytes(ByteArray(512 * 1024 + 1))
        val uri = FileProvider.getUriForFile(context, context.packageName + ".settings-backups", file)
        assertNull(SettingsFileStore.read(context, uri))
    }

    @Test fun anUnreadableFileIsReportedAsMissing() {
        assertNull(SettingsFileStore.read(context, Uri.parse("content://unavailable/settings.json")))
    }

    @Test fun theLargestAcceptedFileStillReadsBack() {
        val file = File(backups.apply { mkdirs() }, "largest.json")
        val largest = ByteArray(512 * 1024) { (it % 251).toByte() }
        file.writeBytes(largest)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".settings-backups", file)
        assertArrayEquals(largest, SettingsFileStore.read(context, uri))
    }

    @Test fun publicDownloadsWriteSucceedsDirectly() {
        val saved = SettingsFileStore.saveToPublicDownloads(context, "DiPlay-settings.json", document)
        assertArrayEquals(document, File(publicBackups, "DiPlay-settings.json").readBytes())
        assertArrayEquals(document, SettingsFileStore.read(context, saved.uri))
    }

    @Test fun androidNineWithStorageAccessSavesToTheVisibleDownloadsFolder() {
        shadowOf(context).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val saved = SettingsFileStore.save(context, "DiPlay-settings.json", document)
        val file = File(publicBackups, "DiPlay-settings.json")
        assertArrayEquals(document, file.readBytes())
        assertEquals(file.absolutePath, saved.path)
        assertFalse(saved.inApp)
    }

    @Test fun repeatedPublicExportsNeverOverwriteAnEarlierFile() {
        shadowOf(context).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        SettingsFileStore.save(context, "DiPlay-settings.json", "first".toByteArray())
        val second = SettingsFileStore.save(context, "DiPlay-settings.json", "second".toByteArray())
        assertEquals("first", File(publicBackups, "DiPlay-settings.json").readText())
        assertEquals("second", File(publicBackups, "DiPlay-settings-1.json").readText())
        assertEquals("second", String(SettingsFileStore.read(context, second.uri)!!))
    }

    @Test @Config(sdk = [30]) fun missingMediaStoreDownloadsUsesThePublicFolderWithoutPermission() {
        val provider = MissingDownloadsProvider()
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        val saved = SettingsFileStore.save(context, "DiPlay-settings.json", document)
        assertTrue(provider.insertAttempted)
        assertEquals(File(publicBackups, "DiPlay-settings.json").absolutePath, saved.path)
        assertArrayEquals(document, SettingsFileStore.read(context, saved.uri))
    }

    @Test fun storageAccessIsOnlyRequestedOnAndroidNine() {
        assertTrue(SettingsFileStore.needsStoragePermission(context, sdkInt = 28))
        assertFalse(SettingsFileStore.needsStoragePermission(context, sdkInt = 29))
        assertFalse(SettingsFileStore.needsStoragePermission(context, sdkInt = 30))
        shadowOf(context).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        assertFalse(SettingsFileStore.needsStoragePermission(context, sdkInt = 28))
    }

    @Test fun anEarlierUriCannotReadALaterExport() {
        val first = SettingsFileStore.save(context, "DiPlay-settings.json", "first".toByteArray())
        val second = SettingsFileStore.save(context, "DiPlay-settings.json", "second".toByteArray())
        assertNotEquals(first.uri, second.uri)
        assertEquals("first", String(SettingsFileStore.read(context, first.uri)!!))
        assertEquals("second", String(SettingsFileStore.read(context, second.uri)!!))
    }

    @Test fun onlyEightExternalFilesAreRetained() {
        var latest: Uri? = null
        repeat(12) { latest = SettingsFileStore.save(context, "DiPlay-settings.json", "document $it".toByteArray()).uri }
        assertEquals(8, backups.listFiles()!!.size)
        assertEquals("document 11", String(SettingsFileStore.read(context, latest!!)!!))
    }

    @Test fun theProviderCannotExposeOtherPrivateFiles() {
        SettingsFileStore.save(context, "DiPlay-settings.json", document)
        val authority = context.packageName + ".settings-backups"
        for (path in listOf("logs/diplay.log", "other.json")) {
            val file = File(context.filesDir, path).apply { parentFile!!.mkdirs(); writeBytes(document) }
            assertThrows(IllegalArgumentException::class.java) {
                FileProvider.getUriForFile(context, authority, file)
            }
        }
    }

    @Test fun unavailableExternalStorageStillSavesPrivately() {
        val unavailable = object : ContextWrapper(context) {
            override fun getExternalFilesDir(type: String?): File? = null
        }
        val saved = SettingsFileStore.save(unavailable, "DiPlay-settings.json", document)
        assertTrue(saved.inApp)
        assertNull(saved.path)
        assertArrayEquals(document, SettingsFileStore.read(context, saved.uri))
        assertArrayEquals(document, privateBackups.listFiles()!!.single().readBytes())
    }

    @Test fun unwritableExternalStorageStillSavesPrivately() {
        backups.writeText("blocks directory creation")
        val saved = SettingsFileStore.save(context, "DiPlay-settings.json", document)
        assertTrue(saved.inApp)
        assertArrayEquals(document, SettingsFileStore.read(context, saved.uri))
        assertEquals("blocks directory creation", backups.readText())
    }

    @Test fun unavailableExternalAndPrivateStorageDoesNotReportSuccess() {
        backups.writeText("blocks directory creation")
        privateBackups.writeText("blocks directory creation")
        assertThrows(IOException::class.java) {
            SettingsFileStore.save(context, "DiPlay-settings.json", document)
        }
    }

    @Test fun onlyEightPrivateFilesAreRetainedWhenExternalStorageIsUnavailable() {
        backups.writeText("blocks directory creation")
        var latest: Uri? = null
        repeat(12) { latest = SettingsFileStore.save(context, "DiPlay-settings.json", "document $it".toByteArray()).uri }
        assertEquals(8, privateBackups.listFiles()!!.size)
        assertEquals("document 11", String(SettingsFileStore.read(context, latest!!)!!))
    }

    private fun registerSettingsProvider() {
        val authority = context.packageName + ".settings-backups"
        val info = context.packageManager.resolveContentProvider(authority, android.content.pm.PackageManager.GET_META_DATA)!!
        // Robolectric gives each test a new filesDir; refresh FileProvider's static path cache.
        val provider = FileProvider().also { it.attachInfo(context, info) }
        ShadowContentResolver.registerProviderInternal(authority, provider)
    }

    private class MissingDownloadsProvider : ContentProvider() {
        var insertAttempted = false
        override fun onCreate() = true
        override fun insert(uri: Uri, values: ContentValues?): Uri? { insertAttempted = true; return null }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
