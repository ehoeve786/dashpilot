package com.softwiredtech.dashpilot.datasource

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.util.Log
import com.softwiredtech.dashpilot.ble.VehicleControl
import com.softwiredtech.dashpilot.datamodel.dash.CarState
import com.softwiredtech.dashpilot.datamodel.dash.isDevCanLogAllEnabled
import com.softwiredtech.dashpilot.datamodel.dash.isDevCanLogEnabled
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
        private const val ALL_FALLBACK_MS = 3000L
    }

    private val _incoming = MutableSharedFlow<CarState>(replay = 1)
    @OptIn(FlowPreview::class)
    override val incomingMessages: Flow<CarState> = _incoming.sample(40)

    private var currentState = CarState()

    // Developer option "Record CAN log": every frame and the firmware's
    // diagnostic records go to a CSV. Read once per connection.
    private val canLog = CanLog(manager.context)
    @Volatile private var logging = false
    @Volatile private var allPending = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun connect(address: String) {
        manager.addGattListener(this)
        manager.connect()
    }

    override fun disconnect() {
        manager.removeGattListener(this)
        decoder.destroy()
        logging = false
        canLog.close()
    }

    override fun onDisconnected() {
        canLog.close()   // one file per connection
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
        logging = isDevCanLogEnabled(manager.context)
        val payload = decoder.canFilterPayload()
        if (payload.isEmpty()) return
        Log.d(TAG, "Subscribing to ${(payload.size - 4) / 4} CAN ids")
        manager.writeCommandWithRetry(SERVICE_UUID, FILTER_UUID, payload, TAG, "CAN filter")
        manager.writeCommandWithRetry(
            VehicleControl.SERVICE_UUID, VehicleControl.CONTROL_CHAR_UUID,
            VehicleControl.payload(VehicleControl.CMD_COMPACT, 1), TAG, "compact packets"
        )
        handler.removeCallbacksAndMessages(null)
        allPending = logging && isDevCanLogAllEnabled(manager.context)
        if (logging) {
            // Ask the firmware for its diagnostic records; it answers with a
            // HELLO record. Firmware without the opcode rejects the write and
            // the log then holds the CAN frames only.
            manager.writeCommandWithRetry(
                VehicleControl.SERVICE_UUID, VehicleControl.CONTROL_CHAR_UUID,
                VehicleControl.payload(VehicleControl.CMD_DIAG_LOG, 1), TAG, "diagnostics"
            )
            // Firmware without diagnostics never sends HELLO: subscribe to everything anyway.
            handler.postDelayed({ subscribeAll() }, ALL_FALLBACK_MS)
        }
    }

    // "Log every frame": the decoder's ids plus a whole-bus entry for each of the CAN buses (0, 1) and the comma
    // 3X buses the DashKit injects (2, 3). It must be the last write of the connection setup: once every frame
    // is streaming, the DashKit left a later write unanswered and Android dropped the link after 30 s.
    private fun subscribeAll() {
        if (!allPending) return
        allPending = false
        val ff = 0xFF.toByte()
        val all = decoder.canFilterPayload() + (0..3).flatMap { listOf(it.toByte(), 1, ff, ff, ff, ff) }.toByteArray()
        Log.i(TAG, "Subscribing to every frame on buses 0-3")
        manager.writeCommandWithRetry(SERVICE_UUID, FILTER_UUID, all, TAG, "CAN filter (all)")
    }

    private fun onDiagnostic(frame: RawCanFrame) {
        if (frame.address != CanLog.DIAG_ID_HELLO || frame.data.size < 2) return
        Log.i(TAG, "DashKit diagnostics on (stream-all firmware: ${frame.data[1].toInt() == 1})")
        subscribeAll()
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
            if (logging) canLog.write(frame.bus, frame.address, frame.data)
            if (CanLog.isDiagnostic(frame.bus)) {
                onDiagnostic(frame)   // firmware record, not a CAN frame
                continue
            }
            currentState = decoder.decodeFrame(frame.bus, frame.address, frame.data) ?: return
        }
        _incoming.tryEmit(currentState)
    }
}
