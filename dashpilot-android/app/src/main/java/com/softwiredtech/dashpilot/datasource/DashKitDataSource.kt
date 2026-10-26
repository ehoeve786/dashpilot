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
        // and the decoded DAS_object / DAS_lanes state, every RX_LOG_MS.
        // `adb logcat -s DashKitRx` shows whether e.g. 0x309 arrives at all.
        private const val RX_LOG_TAG = "DashKitRx"
        private const val RX_LOG_MS = 10_000L
    }

    private val rxCounts = HashMap<Long, Int>()
    private var rxLogStartMs = 0L

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
            if (BuildConfig.DEBUG) countRx(frame.bus, frame.address)
            currentState = decoder.decodeFrame(frame.bus, frame.address, frame.data) ?: return
        }
        _incoming.tryEmit(currentState)
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
            "das lead=${s.leadVehDx} left=${s.leftVehDx} right=${s.rightVehDx} cutin=${s.cutinVehDx} " +
                "sign=${s.roadSignId} lane=${s.laneViewRange}/${s.laneC2} fcw=${s.forwardCollisionWarning} " +
                "ldw=${s.laneDepartureWarning} bsm=${s.leftBlindSpot}/${s.rightBlindSpot} vin=${s.vin}"
        )
        rxCounts.clear()
        rxLogStartMs = now
    }
}
