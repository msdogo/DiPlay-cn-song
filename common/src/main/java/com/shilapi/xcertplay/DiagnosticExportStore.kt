package com.shilapi.xcertplay

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
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

/** Saves an app-owned report without depending on an OEM's document-picker activity. */
internal object DiagnosticExportStore {
    private const val TAG = "DiagnosticExport"
    /** MediaStore keeps the trailing slash; the file API path has none. Query both. */
    private val DOWNLOADS_PATHS = listOf(
        Environment.DIRECTORY_DOWNLOADS + "/DiPlay/",
        Environment.DIRECTORY_DOWNLOADS + "/DiPlay",
    )

    data class SavedReport(
        val uri: Uri,
        val savedInApp: Boolean = false,
        val savedPath: String? = null,
    )

    /**
     * Saves to the visible Downloads/DiPlay folder whenever the head unit allows it, so the
     * report can be reached from a file manager or USB. Order:
     *  1. Android 10+: MediaStore Downloads (no permission needed).
     *  2. Direct file in public Downloads/DiPlay: Android 11+ allows it without permission;
     *     Android 9 needs WRITE_EXTERNAL_STORAGE. Some DiLink builds lack a working
     *     MediaStore Downloads provider, so this also covers Android 11+ when step 1 fails.
     *  3. App-specific external storage (Android/data/<package>, hidden on many head units).
     *  4. Internal app storage, shared through the report viewer.
     */
    fun saveWithoutPicker(context: Context, fileName: String, report: String): SavedReport {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return SavedReport(saveToDownloads(context.contentResolver, fileName, report))
            } catch (error: Exception) {
                // Preserve the report even when the OEM's public storage provider is absent.
                Log.w(TAG, "MediaStore Downloads unavailable; trying the public folder directly", error)
            }
        }
        if (canWritePublicDownloads(context)) {
            try {
                return saveToPublicDownloads(context, fileName, report)
            } catch (error: Exception) {
                Log.w(TAG, "Public Downloads folder unavailable; saving in app storage", error)
            }
        }
        try {
            // Use Android's package-specific directory, including debug application IDs.
            // No storage permission or document-picker activity is needed.
            val externalFiles = context.getExternalFilesDir(null)
            if (externalFiles != null) {
                return saveInDirectory(context, File(externalFiles, "diagnostic-reports"), fileName, report)
            }
        } catch (_: Exception) {
            // A missing, read-only or full external volume must not prevent export.
        }
        return saveInDirectory(context, File(context.filesDir, "diagnostic-reports"), fileName, report, savedInApp = true)
    }

    private fun saveInDirectory(
        context: Context,
        directory: File,
        fileName: String,
        report: String,
        savedInApp: Boolean = false,
    ): SavedReport {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Report storage is unavailable")
        // Each export has a new URI: an earlier share grant cannot read a later report.
        val file = File.createTempFile(fileName.removeSuffix(".txt") + "-", ".txt", directory)
        try {
            file.writeText(report, Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostic-reports", file)
            // Retain only the newest eight reports; never prune the export being returned.
            directory.listFiles()?.filter { it != file && it.isFile }
                ?.sortedByDescending { it.lastModified() }?.drop(7)?.forEach { it.delete() }
            return SavedReport(uri, savedInApp = savedInApp, savedPath = if (savedInApp) null else file.absolutePath)
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    /** Android 11+ lets any app create files in Downloads; older versions need the permission. */
    fun canWritePublicDownloads(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt >= Build.VERSION_CODES.R ||
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /**
     * True only on Android 9, the one version where storage access is what puts the report in
     * the visible Downloads folder. Android 10+ saves there through MediaStore without asking.
     */
    fun needsStoragePermission(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt < Build.VERSION_CODES.Q && !canWritePublicDownloads(context, sdkInt)

    @Suppress("DEPRECATION")
    fun publicDownloadsDirectory(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DiPlay")

    internal fun saveToPublicDownloads(context: Context, fileName: String, report: String): SavedReport {
        val directory = publicDownloadsDirectory()
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Downloads/DiPlay is unavailable")
        val base = fileName.removeSuffix(".txt")
        var file = File(directory, fileName)
        var copy = 1
        while (file.exists()) file = File(directory, "$base-${copy++}.txt")
        try {
            file.writeText(report, Charsets.UTF_8)
            if (file.length() == 0L && report.isNotEmpty()) throw IOException("Downloads/DiPlay did not keep the report")
        } catch (error: Exception) {
            file.delete()
            throw error
        }
        // The report is saved; nothing below may undo that. The user's Downloads folder is never pruned.
        runCatching {
            MediaScannerConnection.scanFile(context.applicationContext, arrayOf(file.absolutePath), arrayOf("text/plain"), null)
        }
        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.diagnostic-reports", file)
        } catch (error: IllegalArgumentException) {
            // Some storage layouts resolve Downloads outside the provider's external root.
            // Share a private copy instead; the visible file in Downloads/DiPlay stays.
            Log.w(TAG, "Downloads/DiPlay is not shareable through the provider; sharing a private copy", error)
            privateShareCopy(context, fileName, report)
        }
        return SavedReport(uri, savedPath = file.absolutePath)
    }

    private fun privateShareCopy(context: Context, fileName: String, report: String): Uri {
        val external = runCatching { context.getExternalFilesDir(null) }.getOrNull()
        if (external != null) {
            runCatching { return saveInDirectory(context, File(external, "diagnostic-reports"), fileName, report).uri }
        }
        return saveInDirectory(context, File(context.filesDir, "diagnostic-reports"), fileName, report, savedInApp = true).uri
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun saveToDownloads(resolver: ContentResolver, fileName: String, report: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/DiPlay")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Downloads could not create the report")
        try {
            write(resolver, uri, report)
            val published = resolver.update(uri, ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }, null, null)
            if (published != 1) throw IOException("Downloads could not publish the report")
            return uri
        } catch (error: Exception) {
            // Only remove the entry created by this call; never leave a partial report behind.
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    fun write(resolver: ContentResolver, uri: Uri, report: String) {
        val stream = resolver.openOutputStream(uri, "wt")
            ?: throw IOException("Report destination is unavailable")
        stream.bufferedWriter(Charsets.UTF_8).use { it.write(report) }
    }

    // --- Saved report management (share / delete) -----------------------------------------

    enum class ReportSource { PUBLIC_DOWNLOADS, EXTERNAL, INTERNAL }

    /** One saved report. [file] is null only for a Downloads entry that exists in MediaStore but not on disk. */
    data class ReportEntry(
        val fileName: String,
        val sizeBytes: Long,
        val modifiedAt: Long,
        val source: ReportSource,
        val file: File?,
        val mediaUri: Uri? = null,
    )

    private fun reportDirectories(context: Context): List<Pair<ReportSource, File>> = buildList {
        runCatching { publicDownloadsDirectory() }.getOrNull()?.let { add(ReportSource.PUBLIC_DOWNLOADS to it) }
        runCatching { context.getExternalFilesDir(null) }.getOrNull()
            ?.let { add(ReportSource.EXTERNAL to File(it, "diagnostic-reports")) }
        add(ReportSource.INTERNAL to File(context.filesDir, "diagnostic-reports"))
    }

    /** Every saved report, newest first. Read-only: nothing is created, pruned or moved. */
    fun listReports(context: Context): List<ReportEntry> {
        val entries = mutableListOf<ReportEntry>()
        val seen = mutableSetOf<String>()
        for ((source, directory) in reportDirectories(context)) {
            val files = runCatching { directory.listFiles { file -> file.isFile && file.name.endsWith(".txt") } }
                .getOrNull() ?: continue
            for (file in files) {
                if (!seen.add(source.name + "/" + file.name)) continue
                entries += ReportEntry(
                    fileName = file.name,
                    sizeBytes = runCatching { file.length() }.getOrDefault(0L),
                    modifiedAt = runCatching { file.lastModified() }.getOrDefault(0L),
                    source = source,
                    file = file,
                )
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { mediaStoreReports(context) }.getOrNull()?.forEach { stored ->
                if (seen.add(ReportSource.PUBLIC_DOWNLOADS.name + "/" + stored.fileName)) entries += stored
            }
        }
        return entries.sortedByDescending { it.modifiedAt }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun mediaStoreReports(context: Context): List<ReportEntry> {
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.SIZE,
            MediaStore.Downloads.DATE_MODIFIED,
        )
        val rows = mutableListOf<ReportEntry>()
        for (relativePath in DOWNLOADS_PATHS) {
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                MediaStore.Downloads.RELATIVE_PATH + "=?",
                arrayOf(relativePath),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val name = cursor.getString(1) ?: continue
                    if (!name.endsWith(".txt")) continue
                    rows += ReportEntry(
                        fileName = name,
                        sizeBytes = cursor.getLong(2),
                        modifiedAt = cursor.getLong(3) * 1000L,
                        source = ReportSource.PUBLIC_DOWNLOADS,
                        file = null,
                        mediaUri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id),
                    )
                }
            }
        }
        return rows
    }

    /** Readable URIs for the chosen reports. A report that cannot be read is skipped, never fatal. */
    fun shareUris(context: Context, entries: List<ReportEntry>): List<Uri> = entries.mapNotNull { entry ->
        runCatching { shareUriFor(context, entry) }.getOrNull()
    }

    private fun shareUriFor(context: Context, entry: ReportEntry): Uri? {
        val file = entry.file
        if (file != null && file.isFile) {
            runCatching { FileProvider.getUriForFile(context, authority(context), file) }.getOrNull()?.let { return it }
            // A MediaStore-created Download can sit outside the provider's external root; share a copy.
            val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
            return privateShareCopyUri(context, entry.fileName, text)
        }
        val uri = entry.mediaUri ?: return null
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        if (bytes.isEmpty()) return null
        return privateShareCopyUri(context, entry.fileName, String(bytes, Charsets.UTF_8))
    }

    private fun privateShareCopyUri(context: Context, fileName: String, report: String): Uri =
        saveInDirectory(context, File(context.filesDir, "diagnostic-reports"), fileName, report, savedInApp = true).uri

    /** Deletes the chosen reports. Returns (deleted, failed); nothing outside the report folders is touched. */
    fun deleteReports(context: Context, entries: List<ReportEntry>): Pair<Int, Int> {
        var deleted = 0
        var failed = 0
        for (entry in entries) {
            if (runCatching { deleteReport(context, entry) }.getOrDefault(false)) deleted++ else failed++
        }
        return deleted to failed
    }

    private fun deleteReport(context: Context, entry: ReportEntry): Boolean {
        if (entry.source == ReportSource.PUBLIC_DOWNLOADS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (runCatching { deleteMediaStoreCopy(context, entry) }.getOrDefault(false)) return true
        }
        val file = entry.file
        if (file != null && file.isFile) return file.delete()
        return false
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun deleteMediaStoreCopy(context: Context, entry: ReportEntry): Boolean {
        val resolver = context.contentResolver
        for (relativePath in DOWNLOADS_PATHS) {
            var id: Long? = null
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                MediaStore.Downloads.RELATIVE_PATH + "=? AND " + MediaStore.Downloads.DISPLAY_NAME + "=?",
                arrayOf(relativePath, entry.fileName),
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) id = cursor.getLong(0) }
            val found = id ?: continue
            val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, found)
            if (resolver.delete(uri, null, null) > 0) return true
        }
        return false
    }

    private fun authority(context: Context) = context.packageName + ".diagnostic-reports"
}

