package com.softwiredtech.dashpilot.datasource

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records everything a DashKit sends into
 * `Android/data/<package>/files/can-log-yyyy-MM-dd-HHmmss.csv`, one file per
 * connection: `ms,bus,id,data` with milliseconds since the first row, the id in
 * hex and the payload as hex bytes.
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
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val name = "can-log-" + SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date()) + ".csv"
        val file = File(dir, name)
        startMs = now
        lastFlushMs = now
        bytes = 0
        Log.i(TAG, "Recording CAN log to ${file.absolutePath}")
        return BufferedWriter(FileWriter(file)).also {
            it.write("ms,bus,id,data\n")
            writer = it
        }
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
