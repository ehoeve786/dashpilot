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

        // Debug builds log what DashKit forwards: per (bus, id) frame counts
        // and the decoded TPMS / DAS_lanes state, every RX_LOG_MS.
        // `adb logcat -s DashKitRx` shows whether e.g. 0x219 arrives at all.
        private const val RX_LOG_TAG = "DashKitRx"
        private const val RX_LOG_MS = 10_000L
        // VCSEC_TPMSData: every frame is logged raw, one sensor per frame.
        private const val TPMS_ADDRESS = 0x219
    }

    private val rxCounts = HashMap<Long, Int>()
    private var rxLogStartMs = 0L

    private val _incoming = MutableSharedFlow<CarState>(replay = 1)
    @OptIn(FlowPreview::class)
    override val incomingMessages: Flow<CarState> = _incoming.sample(40)

    private var currentState = CarState()

    // Last raw TPMS frame per sensor slot (index 0..3), for the debug readout.
    private val tpmsSlots = arrayOfNulls<String>(4)
    private var tpmsFrames = 0

    override fun connect(address: String) {
        manager.addGattListener(this)
        manager.connect()
    }

    override fun disconnect() {
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
            if (frame.address == TPMS_ADDRESS && frame.data.size >= 5) noteTpms(frame.data)
            currentState = (decoder.decodeFrame(frame.bus, frame.address, frame.data) ?: return)
                .copy(tpmsDebug = tpmsDebugText())
        }
        _incoming.tryEmit(currentState)
    }

    // Decodes the VCSEC_TPMSData layout from bus_1_tesla_vehicle.dbc:
    // index 0|2, pressure 8|8 (x0.025 bar), temperature 16|8 (-40 C),
    // location 32|3 (0 FL, 1 FR, 2 RL, 3 RR).
    private fun noteTpms(d: ByteArray) {
        val index = d[0].toInt() and 0x3
        val location = d[4].toInt() and 0x7
        val pressure = ((d[1].toInt() and 0xFF) * 0.025)
        val temp = (d[2].toInt() and 0xFF) - 40
        tpmsSlots[index] = "slot$index loc=$location %.3f bar %d C".format(pressure, temp)
        tpmsFrames++
    }

    private fun tpmsDebugText(): String =
        "tpms frames=$tpmsFrames\n" + tpmsSlots.mapIndexed { i, s -> s ?: "slot$i --" }.joinToString("\n")

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
