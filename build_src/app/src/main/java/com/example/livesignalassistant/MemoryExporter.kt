package com.example.livesignalassistant

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object MemoryExporter {
    data class ExportResult(val path: String, val sessionCount: Int, val bytes: Long)

    fun exportAll(context: Context): ExportResult {
        val sessions = File(context.filesDir, "sessions")
        val sessionDirs = sessions.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }.orEmpty()
        require(sessionDirs.isNotEmpty()) { "No test memory exists yet" }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val tmp = File(context.cacheDir, "LiveSignalMemory_$stamp.zip")
        if (tmp.exists()) tmp.delete()

        val timelines = synchronized(AppStorageLock) {
            sessionDirs.mapNotNull { dir ->
                val log = File(dir, "timeline.jsonl")
                if (log.exists()) "sessions/${dir.name}/timeline.jsonl" to log.readBytes() else null
            }.toMap()
        }
        val store = ExperienceStore(context)
        val experienceBytes = store.snapshotBytes()
        val auditBytes = store.auditSnapshot()
        val pendingBytes = PendingTradeStore(File(context.filesDir, "permanent_experience")).snapshotBytes()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp), 256 * 1024)).use { zip ->
            fun add(file: File, path: String) {
                if (file.isDirectory) {
                    file.listFiles()?.sortedBy { it.name }?.forEach { add(it, "$path/${it.name}") }
                } else if (file.name == "timeline.jsonl") {
                    val snap = timelines[path]
                    zip.putNextEntry(ZipEntry(path))
                    if (snap != null) zip.write(snap) else file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                } else {
                    zip.putNextEntry(ZipEntry(path))
                    file.inputStream().buffered(256 * 1024).use { it.copyTo(zip, 256 * 1024) }
                    zip.closeEntry()
                }
            }
            sessionDirs.forEach { add(it, "sessions/${it.name}") }
            // Snapshot permanent learning for audit/backup. Export does NOT move or delete the live Experience Core.
            if (experienceBytes != null) {
                zip.putNextEntry(ZipEntry("experience_snapshot/experience_v3.json"))
                zip.write(experienceBytes)
                zip.closeEntry()
            }
            if (auditBytes != null) {
                zip.putNextEntry(ZipEntry("experience_snapshot/experience_audit.jsonl"))
                zip.write(auditBytes)
                zip.closeEntry()
            }
            if (pendingBytes != null) {
                zip.putNextEntry(ZipEntry("experience_snapshot/pending_trades.tsv"))
                zip.write(pendingBytes)
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("README.txt"))
            zip.write("Live Signal Assistant 72.0.2 memory export. STRENGTH is an evidence-quality measure and is NOT a calibrated win probability. Session memory is deleted only after explicit user confirmation. Permanent Experience Core is NEVER cleared by session cleanup.\n".toByteArray())
            zip.closeEntry()
        }

        // Verify the archive before it is offered as safe to delete from app storage.
        ZipFile(tmp).use { z ->
            require(z.entries().asSequence().any { it.name.startsWith("sessions/") }) { "ZIP verification failed: no session data" }
            z.entries().asSequence().filter { !it.isDirectory }.forEach { entry ->
                z.getInputStream(entry).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (input.read(buffer) >= 0) { /* force CRC/read verification */ }
                }
            }
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, tmp.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Downloads destination unavailable")
        context.contentResolver.openOutputStream(uri)?.use { out ->
            tmp.inputStream().buffered(256 * 1024).use { it.copyTo(out, 256 * 1024) }
        } ?: throw IllegalStateException("Could not open Downloads output")

        return ExportResult("Downloads/${tmp.name}", sessionDirs.size, tmp.length())
    }

    /** Standalone backup of the compact permanent Experience (a few KB). Does not touch the live store. */
    fun exportExperience(context: Context): String {
        val store = ExperienceStore(context)
        val text = store.exportText()
        val name = "LiveSignalExperience_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".json"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Downloads destination unavailable")
        context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            ?: throw IllegalStateException("Could not open Downloads output")
        return "Downloads/$name"
    }

    fun memoryBytes(context: Context): Long {
        fun size(f: File): Long = if (f.isFile) f.length() else f.listFiles()?.sumOf { size(it) } ?: 0L
        return size(File(context.filesDir, "sessions"))
    }

    fun clearAll(context: Context): Long {
        val root = File(context.filesDir, "sessions")
        val bytes = memoryBytes(context)
        if (root.exists() && !root.deleteRecursively()) throw IllegalStateException("Could not clear test memory")
        root.mkdirs()
        return bytes
    }
}
