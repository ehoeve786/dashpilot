package com.softwiredtech.dashpilot.datasource

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
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
) : IDataSource, GattListener {

    companion object {
        private const val TAG = "DashKitDataSource"
        private val SERVICE_UUID = UUID.fromString("CADA0000-CA00-B1E0-B0D6-C000AA0100A1")
        private val CHAR_UUID = UUID.fromString("CADA0001-CA00-B1E0-B0D6-C000AA0100A1")
        // Debug builds log every received frame to
        // Android/data/<pkg>/files/can-log-yyyy-MM-dd-HHmm.csv (one file per
        // connection, ms,bus,id,data), capped at 50 MiB.
        private const val CAN_LOG_MAX_BYTES = 50L * 1024 * 1024
    }

    private var canLog: BufferedWriter? = null
    private var canLogBytes = 0L
    private var canLogStartMs = 0L
    private var canLogFlushMs = 0L

    private val _incoming = MutableSharedFlow<CarState>(replay = 1)
    @OptIn(FlowPreview::class)
    override val incomingMessages: Flow<CarState> = _incoming.sample(40)

    private var currentState = CarState()

    override fun connect(address: String) {
        manager.addGattListener(this)
        manager.connect()
    }

    override fun disconnect() {
        manager.removeGattListener(this)
        decoder.destroy()
        closeCanLog()
        canLogBytes = 0   // next connection gets a fresh file, even after a cap/error
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
        for (frame in parseCanPacket(payload)) {
            if (BuildConfig.DEBUG) writeCanLog(frame.bus, frame.address, frame.data)
            currentState = decoder.decodeFrame(frame.bus, frame.address, frame.data) ?: return
        }
        _incoming.tryEmit(currentState)
    }

    private fun writeCanLog(bus: Int, address: Int, data: ByteArray) {
        if (canLogBytes > CAN_LOG_MAX_BYTES) return
        try {
            val now = SystemClock.elapsedRealtime()
            val w = canLog ?: run {
                val dir = manager.context.getExternalFilesDir(null) ?: manager.context.filesDir
                val name = "can-log-" + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date()) + ".csv"
                canLogStartMs = now
                canLogFlushMs = now
                canLogBytes = 0
                BufferedWriter(FileWriter(File(dir, name))).also { it.write("ms,bus,id,data\n"); canLog = it }
            }
            val line = "${now - canLogStartMs},$bus,0x%03X,%s\n".format(address, data.joinToString("") { "%02x".format(it) })
            w.write(line)
            canLogBytes += line.length
            if (now - canLogFlushMs > 1000) { w.flush(); canLogFlushMs = now }
            if (canLogBytes > CAN_LOG_MAX_BYTES) closeCanLog()
        } catch (e: Exception) {
            Log.w(TAG, "CAN log disabled: ${e.message}")
            canLogBytes = CAN_LOG_MAX_BYTES + 1
            closeCanLog()
        }
    }

    private fun closeCanLog() {
        try { canLog?.close() } catch (_: Exception) {}
        canLog = null
    }
}
