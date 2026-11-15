package com.softwiredtech.dashpilot.datasource

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records everything a DashKit sends into
 * `Download/DashPilot/can-log-yyyy-MM-dd-HHmmss.csv` on shared storage (visible
 * in any file manager, no permission needed for files the app creates there),
 * one file per connection: `ms,bus,id,data` with milliseconds since the first
 * row, the id in hex and the payload as hex bytes. If shared storage refuses
 * the file, it goes to the app's own `Android/data/<package>/files` instead.
 *
 * CAN frames have bus 0 (chassis) or 1 (vehicle). Rows with bus 16 or 17 and an
 * id of 0x1DA5xxxx are the firmware's diagnostic records (receive statistics
 * every 5 s, one per door unlatch); their layouts are documented in the
 * firmware's `main/can/can_diag.h`.
 *
 * Switched on with the developer option "Record CAN log".
 */
class CanLog(private val context: Context) {

    companion object {
        private const val TAG = "CanLog"
        // A full two-bus stream is roughly 5 MB a minute.
        private const val MAX_BYTES = 200L * 1024 * 1024
        private const val FLUSH_INTERVAL_MS = 1000L

        /** Bus byte flag and ids of the firmware's diagnostic records. */
        const val DIAG_BUS_FLAG = 0x10
        const val DIAG_ID_HELLO = 0x1DA50000

        /** Folder under the public Downloads directory. */
        const val FOLDER = "DashPilot"

        fun isDiagnostic(bus: Int): Boolean = (bus and DIAG_BUS_FLAG) != 0
    }

    private var writer: BufferedWriter? = null
    private var bytes = 0L
    private var startMs = 0L
    private var lastFlushMs = 0L
    private var failed = false

    @Synchronized
    fun write(bus: Int, address: Int, data: ByteArray) {
        if (failed || bytes > MAX_BYTES) return
        try {
            val now = SystemClock.elapsedRealtime()
            val w = writer ?: open(now)
            val line = buildString(24 + data.size * 2) {
                append(now - startMs).append(',').append(bus).append(",0x")
                append(String.format(Locale.US, if (address in 0..0x7FF) "%03X" else "%08X", address))
                append(',')
                for (b in data) append(String.format(Locale.US, "%02x", b))
                append('\n')
            }
            w.write(line)
            bytes += line.length
            if (now - lastFlushMs > FLUSH_INTERVAL_MS) {
                w.flush()
                lastFlushMs = now
            }
            if (bytes > MAX_BYTES) {
                Log.w(TAG, "CAN log reached its size limit; recording stopped")
                close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "CAN log disabled: ${e.message}")
            failed = true
            close()
        }
    }

    private fun open(now: Long): BufferedWriter {
        val name = "can-log-" + SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date()) + ".csv"
        startMs = now
        lastFlushMs = now
        bytes = 0
        return (openInDownloads(name) ?: openInAppFiles(name)).also {
            it.write("ms,bus,id,data\n")
            writer = it
        }
    }

    // Public Downloads through MediaStore. Not marked pending: the file should
    // stay visible even if the app is killed mid-recording.
    private fun openInDownloads(name: String): BufferedWriter? = try {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        val stream = uri?.let { resolver.openOutputStream(it) }
        if (stream == null) {
            null
        } else {
            Log.i(TAG, "Recording CAN log to Download/$FOLDER/$name")
            BufferedWriter(OutputStreamWriter(stream, Charsets.US_ASCII))
        }
    } catch (e: Exception) {
        Log.w(TAG, "Downloads not writable (${e.message}); using the app's files folder")
        null
    }

    private fun openInAppFiles(name: String): BufferedWriter {
        val file = File(context.getExternalFilesDir(null) ?: context.filesDir, name)
        Log.i(TAG, "Recording CAN log to ${file.absolutePath}")
        return BufferedWriter(FileWriter(file))
    }

    /** Ends the current file; the next [write] starts a new one. */
    @Synchronized
    fun close() {
        try {
            writer?.close()
        } catch (_: Exception) {
        }
        writer = null
        if (!failed) bytes = 0
    }
}
