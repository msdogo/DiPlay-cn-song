package com.shilapi.xcertplay.backup

import android.Manifest
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/**
 * Saves and reads the settings document as a file the driver can keep.
 *
 * The document itself is SettingsBackupCodec (see the backup package), unchanged: a file backup
 * and the phone web page share one format, one allow-list and one type check. This object only
 * moves those bytes to and from storage, including the visible Downloads/DiPlay folder when the
 * head unit allows it.
 */
internal object SettingsFileStore {
    private const val TAG = "SettingsFile"
    private const val MIME = "application/json"
    private const val DIRECTORY_NAME = "DiPlay"
    private const val SETTINGS_DIRECTORY = "settings-backups"

    /** The document ceiling, the same one the codec enforces. */
    private const val MAX_BYTES = 512 * 1024
    private const val KEEP_FILES = 8

    data class SavedFile(
        val uri: Uri,
        val path: String? = null,
        val inApp: Boolean = false,
    )

    /**
     * Saves to the visible Downloads/DiPlay folder whenever the head unit allows it. Order:
     *  1. Android 10+: MediaStore Downloads (no permission needed).
     *  2. Direct file in public Downloads/DiPlay: Android 11+ allows it without permission;
     *     Android 9 needs WRITE_EXTERNAL_STORAGE. Some DiLink builds lack a working MediaStore
     *     Downloads provider, so this also covers Android 11+ when step 1 fails.
     *  3. App-specific external storage (Android/data/<package>, hidden on many head units).
     *  4. Internal app storage.
     */
    fun save(context: Context, fileName: String, document: ByteArray): SavedFile {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return SavedFile(saveToDownloads(context.contentResolver, fileName, document))
            } catch (error: Exception) {
                Log.w(TAG, "MediaStore Downloads unavailable; trying the public folder directly", error)
            }
        }
        if (canWritePublicDownloads(context)) {
            try {
                return saveToPublicDownloads(context, fileName, document)
            } catch (error: Exception) {
                Log.w(TAG, "Public Downloads folder unavailable; saving in app storage", error)
            }
        }
        try {
            val external = context.getExternalFilesDir(null)
            if (external != null) {
                return saveInDirectory(context, File(external, SETTINGS_DIRECTORY), fileName, document)
            }
        } catch (_: Exception) {
            // A missing, read-only or full external volume must not prevent the export.
        }
        return saveInDirectory(context, File(context.filesDir, SETTINGS_DIRECTORY), fileName, document, inApp = true)
    }

    /** Reads at most one byte past the ceiling, so an oversized file is rejected without loading it. */
    fun read(context: Context, uri: Uri): ByteArray? = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(MAX_BYTES + 1)
            var total = 0
            while (total < buffer.size) {
                val read = stream.read(buffer, total, buffer.size - total)
                if (read <= 0) break
                total += read
            }
            if (total > MAX_BYTES) null else buffer.copyOf(total)
        }
    } catch (error: Exception) {
        Log.w(TAG, "The chosen file could not be read", error)
        null
    }

    private fun saveInDirectory(
        context: Context,
        directory: File,
        fileName: String,
        document: ByteArray,
        inApp: Boolean = false,
    ): SavedFile {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Settings storage is unavailable")
        // Each export has a new URI: an earlier grant cannot read a later document.
        val file = File.createTempFile(fileName.removeSuffix(".json") + "-", ".json", directory)
        try {
            file.writeBytes(document)
            val authority = context.packageName + ".settings-backups"
            val uri = FileProvider.getUriForFile(context, authority, file)
            // Retain only the newest exports; never prune the document being returned.
            directory.listFiles()?.filter { it != file && it.isFile }
                ?.sortedByDescending { it.lastModified() }?.drop(KEEP_FILES - 1)?.forEach { it.delete() }
            return SavedFile(uri, path = if (inApp) null else file.absolutePath, inApp = inApp)
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    @Suppress("DEPRECATION")
    internal fun publicDownloadsDirectory(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DIRECTORY_NAME)

    internal fun saveToPublicDownloads(context: Context, fileName: String, document: ByteArray): SavedFile {
        val directory = publicDownloadsDirectory()
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Downloads/DiPlay is unavailable")
        val base = fileName.removeSuffix(".json")
        var file = File(directory, fileName)
        var copy = 1
        while (file.exists()) file = File(directory, base + "-" + copy++ + ".json")
        try {
            file.writeBytes(document)
            if (file.length() == 0L && document.isNotEmpty()) throw IOException("Downloads/DiPlay did not keep the file")
        } catch (error: Exception) {
            file.delete()
            throw error
        }
        // The file is saved; nothing below may undo that. The public Downloads folder is never pruned.
        runCatching {
            MediaScannerConnection.scanFile(context.applicationContext, arrayOf(file.absolutePath), arrayOf(MIME), null)
        }
        val authority = context.packageName + ".settings-backups"
        val uri = try {
            FileProvider.getUriForFile(context, authority, file)
        } catch (error: IllegalArgumentException) {
            // Some storage layouts resolve Downloads outside the provider root.
            Log.w(TAG, "Downloads/DiPlay is not shareable through the provider; sharing a private copy", error)
            privateCopy(context, fileName, document).uri
        }
        return SavedFile(uri, path = file.absolutePath)
    }

    private fun privateCopy(context: Context, fileName: String, document: ByteArray): SavedFile {
        val external = runCatching { context.getExternalFilesDir(null) }.getOrNull()
        if (external != null) {
            return saveInDirectory(context, File(external, SETTINGS_DIRECTORY), fileName, document)
        }
        return saveInDirectory(context, File(context.filesDir, SETTINGS_DIRECTORY), fileName, document, inApp = true)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    internal fun saveToDownloads(resolver: ContentResolver, fileName: String, document: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + DIRECTORY_NAME)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Downloads could not create the settings file")
        try {
            val stream = resolver.openOutputStream(uri, "wt") ?: throw IOException("Downloads is unavailable")
            stream.use { it.write(document) }
            val published = resolver.update(uri, ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }, null, null)
            if (published != 1) throw IOException("Downloads could not publish the settings file")
            return uri
        } catch (error: Exception) {
            // Only remove the entry created by this call; never leave a partial file behind.
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    /** Android 11+ lets any app create files in Downloads; older versions need the permission. */
    internal fun canWritePublicDownloads(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt >= Build.VERSION_CODES.R ||
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /**
     * True only on Android 9, the one version where storage access is what puts the file in the
     * visible Downloads folder. Android 10+ saves there through MediaStore without asking.
     */
    internal fun needsStoragePermission(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt < Build.VERSION_CODES.Q && !canWritePublicDownloads(context, sdkInt)
}
