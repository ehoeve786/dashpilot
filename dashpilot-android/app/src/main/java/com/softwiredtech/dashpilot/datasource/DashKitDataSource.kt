package com.softwiredtech.dashpilot.datasource

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.util.Log
import com.softwiredtech.dashpilot.ble.VehicleControl
import com.softwiredtech.dashpilot.datamodel.dash.CarState
import com.softwiredtech.dashpilot.datamodel.dash.getDashKitControl
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
        // CAN filter (write): the (bus, id) list the DashKit forwards.
        private val FILTER_UUID = UUID.fromString("CADA0002-CA00-B1E0-B0D6-C000AA0100A1")
    }

    private val _incoming = MutableSharedFlow<CarState>(replay = 1)
    @OptIn(FlowPreview::class)
    override val incomingMessages: Flow<CarState> = _incoming.sample(40)

    private var currentState = CarState()

    @Volatile private var wanted: Set<Long> = emptySet()  // bus shl 32 or id, for every id the mapper reads

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

    // Once notifications are on, tell the DashKit which CAN ids this vehicle's
    // decoder reads. Stream-all firmware forwards exactly those (any id, no
    // manifest change needed); older firmware forwards the ones its hardware
    // filters accept, as before. The DashKit clears the list on disconnect.
    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        if (descriptor.characteristic?.uuid != CHAR_UUID || status != BluetoothGatt.GATT_SUCCESS) return
        // Which phone sends the commands when two are connected. Firmware without it rejects the write.
        manager.writeCommandWithRetry(
            VehicleControl.SERVICE_UUID, VehicleControl.CONTROL_CHAR_UUID,
            VehicleControl.payload(VehicleControl.CMD_BLE_ROLE, if (getDashKitControl(manager.context)) 0 else 1),
            TAG, "BLE role"
        )
        val payload = decoder.canFilterPayload()
        if (payload.isEmpty()) {
            // No mapper for this vehicle, or more ids than one write carries.
            Log.w(TAG, "No CAN filter for this vehicle; the DashKit sends its default set")
            return
        }
        wanted = filterIds(payload)
        Log.d(TAG, "Subscribing to ${wanted.size} CAN ids")
        manager.writeCommandWithRetry(SERVICE_UUID, FILTER_UUID, payload, TAG, "CAN filter")
        manager.writeCommandWithRetry(
            VehicleControl.SERVICE_UUID, VehicleControl.CONTROL_CHAR_UUID,
            VehicleControl.payload(VehicleControl.CMD_COMPACT, 1), TAG, "compact packets"
        )
    }

    // The ids in a CAN filter payload: per bus [bus][count][id LE32 x count].
    private fun filterIds(p: ByteArray): Set<Long> {
        val ids = HashSet<Long>()
        var o = 0
        while (o + 2 <= p.size) {
            val bus = p[o].toLong() and 0xFF
            val n = p[o + 1].toInt() and 0xFF
            o += 2
            repeat(n) {
                if (o + 4 > p.size) return ids
                val id = java.nio.ByteBuffer.wrap(p, o, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                ids.add(bus shl 32 or id)
                o += 4
            }
        }
        return ids
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
        val kept = ArrayList<RawCanFrame>(frames.size)
        for (frame in frames) {
            // Decode only what the mapper reads: another device on the DashKit (CANLens over USB or Wi-Fi) may
            // have it send more, and running the mapper on every frame of a whole bus stalls this thread.
            if (wanted.isNotEmpty() && (frame.bus.toLong() shl 32 or frame.address.toLong()) !in wanted) continue
            kept.add(frame)
        }
        // One JNI call for the packet's frames (decodeFrames maps after each one).
        if (kept.isNotEmpty()) {
            currentState = decoder.decodeFrames(kept) ?: return
        }
        _incoming.tryEmit(currentState)
    }
}
