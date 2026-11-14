package com.softwiredtech.dashpilot.datasource

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.media.MediaScannerConnection
import android.os.SystemClock
import android.util.Log
import com.softwiredtech.dashpilot.BuildConfig
import com.softwiredtech.dashpilot.datamodel.dash.CarState
import com.softwiredtech.dashpilot.vehicle.CanFrameDecoder
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.sample
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@SuppressLint("MissingPermission")
class DashKitDataSource(
    private val manager: DashKitBleManager,
    private val decoder: CanFrameDecoder,
    private val context: Context,
) : IDataSource, GattListener {

    companion object {
        private const val TAG = "DashKitDataSource"
        private val SERVICE_UUID = UUID.fromString("CADA0000-CA00-B1E0-B0D6-C000AA0100A1")
        private val CHAR_UUID = UUID.fromString("CADA0001-CA00-B1E0-B0D6-C000AA0100A1")
        // Stop writing a CAN log at about 50 MB (a few minutes of traffic).
        private const val CAN_LOG_MAX_BYTES = 50L * 1024 * 1024
    }

    // Debug builds: every forwarded frame, both buses, as CSV (ms since connect, bus,
    // id, hex data), one file per connection in the app's external files directory,
    // Android/data/<app id>/files/can-log-<date>-<time>.csv (reachable over USB).
    private var canLogFile: File? = null
    private var canLog: BufferedWriter? = null
    private var canLogBytes = 0L
    private var canLogStartMs = 0L
    private var canLogFlushMs = 0L
    private var canLogDone = false

    private val _incoming = MutableSharedFlow<CarState>(replay = 1)
    @OptIn(FlowPreview::class)
    override val incomingMessages: Flow<CarState> = _incoming.sample(40)

    private var currentState = CarState()

    override fun connect(address: String) {
        manager.addGattListener(this)
        manager.connect()
    }

    override fun disconnect() {
        closeCanLog()
        manager.removeGattListener(this)
        decoder.destroy()
    }

    override fun onServicesReady(gatt: BluetoothGatt) {
        val service = gatt.getService(SERVICE_UUID)
        if (service == null) {
            Log.e(TAG, "CAN BLE service not found")
            return
        }
        val characteristic = service.getCharacteristic(CHAR_UUID)
        if (characteristic == null) {
            Log.e(TAG, "CAN BLE characteristic not found")
            return
        }
        gatt.setCharacteristicNotification(characteristic, true)
        manager.subscribeWithRetry(gatt, characteristic, TAG, "CAN")
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        if (characteristic.uuid != CHAR_UUID) return
        parseAndEmit(value)
    }

    private fun parseAndEmit(payload: ByteArray) {
        if (payload.isEmpty()) return
        val frames = parseCanPacket(payload)
        if (BuildConfig.DEBUG) frames.forEach { writeCanLog(it.bus, it.address, it.data) }
        if (frames.isNotEmpty()) {
            currentState = decoder.decodeFrames(frames) ?: return
        }
        _incoming.tryEmit(currentState)
    }

    private fun writeCanLog(bus: Int, address: Int, data: ByteArray) {
        if (canLogDone) return
        try {
            val now = SystemClock.elapsedRealtime()
            if (canLog == null) {
                // getExternalFilesDir creates the directory; null if storage is unavailable.
                val dir = context.getExternalFilesDir(null) ?: error("external files dir unavailable")
                val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())
                val file = File(dir, "can-log-$stamp.csv")
                canLog = BufferedWriter(FileWriter(file, false))
                canLogFile = file
                canLogBytes = 0
                canLogStartMs = now
                canLogFlushMs = now
                canLog!!.write("ms,bus,id,data\n")
                canLog!!.flush()
                scanCanLog()
            }
            val hex = data.joinToString("") { "%02x".format(it) }
            val line = "${now - canLogStartMs},$bus,0x%03X,$hex\n".format(address)
            canLog!!.write(line)
            canLogBytes += line.length
            if (now - canLogFlushMs > 1000) {
                canLog!!.flush()
                canLogFlushMs = now
            }
            if (canLogBytes > CAN_LOG_MAX_BYTES) closeCanLog().also { canLogDone = true }
        } catch (e: Exception) {
            Log.w(TAG, "CAN log disabled: ${e.message}")
            canLogDone = true
            closeCanLog()
        }
    }

    private fun closeCanLog() {
        try {
            canLog?.close()
        } catch (_: Exception) {
        }
        if (canLog != null) scanCanLog()
        canLog = null
    }

    // Files in Android/data only show up over USB (MTP) once the media scanner has seen them.
    private fun scanCanLog() {
        canLogFile?.let { MediaScannerConnection.scanFile(context, arrayOf(it.absolutePath), null, null) }
    }
}
