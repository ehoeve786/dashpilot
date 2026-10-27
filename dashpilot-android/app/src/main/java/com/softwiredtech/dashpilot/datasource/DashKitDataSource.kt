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
import java.util.UUID

@SuppressLint("MissingPermission")
class DashKitDataSource(
    private val manager: DashKitBleManager,
    private val decoder: CanFrameDecoder,
    // Debug builds write the CAN log here (the app's internal files directory).
    private val canLogFile: File,
) : IDataSource, GattListener {

    companion object {
        private const val TAG = "DashKitDataSource"
        private val SERVICE_UUID = UUID.fromString("CADA0000-CA00-B1E0-B0D6-C000AA0100A1")
        private val CHAR_UUID = UUID.fromString("CADA0001-CA00-B1E0-B0D6-C000AA0100A1")

        // Debug builds log what DashKit forwards: per (bus, id) frame counts
        // and the decoded TPMS / DAS_lanes state, every RX_LOG_MS.
        // `adb logcat -s DashKitRx` shows whether e.g. 0x219 arrives at all.
        private const val RX_LOG_TAG = "DashKitRx"
        private const val RX_LOG_MS = 10_000L
        // VCSEC_TPMSData: every frame is logged raw, one sensor per frame.
        private const val TPMS_ADDRESS = 0x219
        // TPMS_data: all four wheels in one frame on the chassis bus.
        private const val TPMS_DATA_ADDRESS = 0x31F
        // Stop writing the CAN log at about 50 MB (a few minutes of traffic).
        private const val CAN_LOG_MAX_BYTES = 50L * 1024 * 1024
    }

    private val rxCounts = HashMap<Long, Int>()
    private var rxLogStartMs = 0L

    private val _incoming = MutableSharedFlow<CarState>(replay = 1)
    @OptIn(FlowPreview::class)
    override val incomingMessages: Flow<CarState> = _incoming.sample(40)

    private var currentState = CarState()

    // Last reading per wheel (0 FL, 1 FR, 2 RL, 3 RR), for the debug readout.
    private val tpmsByWheel = arrayOfNulls<String>(4)
    private val tpmsWheelMs = LongArray(4)
    // Last mux 4 frame (not a wheel reading), for the debug readout.
    private var tpmsMux4Hex = ""
    private var tpmsMux4Ms = 0L
    private var tpmsFrames = 0
    // Last TPMS_data frame (0x31F, chassis bus) for the debug readout.
    private var tpms31fHex = ""
    private var tpms31fMs = 0L
    // Frames received per vehicle-bus CAN ID since connecting, for the debug readout.
    private val busOneCounts = sortedMapOf<Int, Int>()

    // Debug builds: every forwarded frame, both buses, as CSV
    // (ms since connect, bus, id, hex data). Overwritten on each connection and
    // capped in size. Readable with run-as on debug builds.
    private var canLog: BufferedWriter? = null
    private var canLogBytes = 0L
    private var canLogStartMs = 0L
    private var canLogFlushMs = 0L
    private var canLogDone = false

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
        for (frame in parseCanPacket(payload)) {
            if (BuildConfig.DEBUG) {
                countRx(frame.bus, frame.address)
                if (frame.address == TPMS_ADDRESS) {
                    val hex = frame.data.joinToString(" ") { "%02x".format(it) }
                    Log.i(RX_LOG_TAG, "tpms raw bus=${frame.bus} len=${frame.data.size} $hex")
                }
            }
            if (frame.address == TPMS_ADDRESS && frame.data.size >= 7) noteTpms(frame.data)
            if (frame.bus == 1) busOneCounts[frame.address] = (busOneCounts[frame.address] ?: 0) + 1
            if (frame.bus == 0 && frame.address == TPMS_DATA_ADDRESS) {
                tpms31fHex = frame.data.joinToString(" ") { "%02x".format(it) }
                tpms31fMs = SystemClock.elapsedRealtime()
            }
            if (BuildConfig.DEBUG) writeCanLog(frame.bus, frame.address, frame.data)
            currentState = (decoder.decodeFrame(frame.bus, frame.address, frame.data) ?: return)
                .copy(tpmsDebug = tpmsDebugText())
        }
        _incoming.tryEmit(currentState)
    }

    private fun writeCanLog(bus: Int, address: Int, data: ByteArray) {
        if (canLogDone) return
        try {
            val now = SystemClock.elapsedRealtime()
            if (canLog == null) {
                canLogFile.parentFile?.mkdirs()
                canLog = BufferedWriter(FileWriter(canLogFile, false))
                canLogBytes = 0
                canLogStartMs = now
                canLogFlushMs = now
                canLog!!.write("ms,bus,id,data\n")
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
        canLog = null
    }

    // Decodes the 7-byte VCSEC_TPMSData layout from bus_1_tesla_vehicle.dbc.
    // Mux 5 carries FL, FR, RL, RR pressures in bytes 2-5 (x0.025 bar).
    // Muxes 0-3 each carry one sensor: mux 0 RR, 1 RL, 2 FR, 3 FL, with pressure
    // in byte 2 (x0.05 - 1 bar gauge), temperature in byte 3 (-40 C) and sensor
    // battery in byte 4 (x0.01 + 1.5 V). Raw 0xFF in a mux 0-3 frame is no reading.
    private fun noteTpms(d: ByteArray) {
        val mux = d[0].toInt() and 0x7
        val hex = d.joinToString(" ") { "%02x".format(it) }
        val now = SystemClock.elapsedRealtime()
        tpmsFrames++
        when (mux) {
            5 -> for (w in 0 until 4) {
                val raw = d[2 + w].toInt() and 0xFF
                tpmsByWheel[w] = "%.3f bar [%s]".format(raw * 0.025, hex)
                tpmsWheelMs[w] = now
            }
            0, 1, 2, 3 -> {
                val w = intArrayOf(3, 2, 1, 0)[mux]
                val raw = d[2].toInt() and 0xFF
                tpmsByWheel[w] = if (raw == 0xFF) "mux$mux no reading [$hex]" else
                    "mux$mux %.2f bar %d C %.2f V [$hex]".format(
                        raw * 0.05 - 1.0,
                        (d[3].toInt() and 0xFF) - 40,
                        (d[4].toInt() and 0xFF) * 0.01 + 1.5,
                    )
                tpmsWheelMs[w] = now
            }
            else -> {
                tpmsMux4Hex = hex
                tpmsMux4Ms = now
            }
        }
    }

    private fun tpmsDebugText(): String {
        val now = SystemClock.elapsedRealtime()
        val wheels = listOf("FL", "FR", "RL", "RR").mapIndexed { w, name ->
            val s = tpmsByWheel[w]
            if (s == null) "$name --"
            else "$name $s age ${(now - tpmsWheelMs[w]) / 1000}s"
        }.joinToString("\n")
        val mux4 = if (tpmsMux4Hex.isEmpty()) "mux4 --"
            else "mux4 [$tpmsMux4Hex] age ${(now - tpmsMux4Ms) / 1000}s"
        val ids = busOneCounts.entries.joinToString(" ") { "0x%03X:%d".format(it.key, it.value) }
        val all = if (tpms31fHex.isEmpty()) "tpms31f --"
            else "tpms31f [$tpms31fHex] age ${(now - tpms31fMs) / 1000}s"
        return "tpms frames=$tpmsFrames\n$wheels\n$mux4\n$all\nbus1 ids: $ids"
    }

    private fun countRx(bus: Int, address: Int) {
        val key = (bus.toLong() shl 32) or (address.toLong() and 0xFFFFFFFFL)
        rxCounts[key] = (rxCounts[key] ?: 0) + 1
        val now = SystemClock.elapsedRealtime()
        if (rxLogStartMs == 0L) rxLogStartMs = now
        if (now - rxLogStartMs < RX_LOG_MS) return
        val ids = rxCounts.entries.sortedBy { it.key }.joinToString(" ") { (k, n) ->
            "b${k ushr 32}:0x%03X=%d".format(k and 0xFFFFFFFFL, n)
        }
        Log.i(RX_LOG_TAG, "frames in ${(now - rxLogStartMs) / 1000}s: $ids")
        val s = currentState
        Log.i(
            RX_LOG_TAG,
            "tpms bar=${s.tpmsPressureFL}/${s.tpmsPressureFR}/${s.tpmsPressureRL}/${s.tpmsPressureRR} " +
                "lane=${s.laneViewRange}/${s.laneC2} fcw=${s.forwardCollisionWarning} " +
                "ldw=${s.laneDepartureWarning} bsm=${s.leftBlindSpot}/${s.rightBlindSpot} vin=${s.vin}"
        )
        rxCounts.clear()
        rxLogStartMs = now
    }
}
