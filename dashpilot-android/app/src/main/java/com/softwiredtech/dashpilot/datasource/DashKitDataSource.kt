package com.softwiredtech.dashpilot.datasource

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.util.Log
import com.softwiredtech.dashpilot.ble.VehicleControl
import com.softwiredtech.dashpilot.datamodel.dash.CarState
import com.softwiredtech.dashpilot.datamodel.dash.getDashKitControl
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
    private var decoded = 0
    private var handleMaxMs = 0L   // longest time this thread spent on one notification, last 5 s
    private var emitted = 0
    private var emitFailed = 0
    private var recordAt = 0L
    @Volatile private var mainPostedAt = 0L
    @Volatile private var mainLagMs = 0L
    @Volatile private var wanted: Set<Long> = emptySet()  // bus shl 32 or id, for every id the mapper reads
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
        // Which phone sends the commands when two are connected. Firmware without it rejects the write.
        manager.writeCommandWithRetry(
            VehicleControl.SERVICE_UUID, VehicleControl.CONTROL_CHAR_UUID,
            VehicleControl.payload(VehicleControl.CMD_BLE_ROLE, if (getDashKitControl(manager.context)) 0 else 1),
            TAG, "BLE role"
        )
        val payload = decoder.canFilterPayload()
        if (payload.isEmpty()) return
        wanted = filterIds(payload)
        Log.d(TAG, "Subscribing to ${wanted.size} CAN ids")
        manager.writeCommandWithRetry(SERVICE_UUID, FILTER_UUID, payload, TAG, "CAN filter")
        manager.writeCommandWithRetry(
            VehicleControl.SERVICE_UUID, VehicleControl.CONTROL_CHAR_UUID,
            VehicleControl.payload(VehicleControl.CMD_COMPACT, 1), TAG, "compact packets"
        )
        handler.removeCallbacksAndMessages(null)
        mainPostedAt = 0L
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
        val t0 = android.os.SystemClock.elapsedRealtime()
        parseAndEmit(value)
        handleMaxMs = maxOf(handleMaxMs, android.os.SystemClock.elapsedRealtime() - t0)
    }

    private fun parseAndEmit(payload: ByteArray) {
        if (payload.isEmpty()) return
        for (frame in parseCanPacket(payload)) {
            if (logging) canLog.write(frame.bus, frame.address, frame.data)
            if (CanLog.isDiagnostic(frame.bus)) {
                onDiagnostic(frame)   // firmware record, not a CAN frame
                continue
            }
            // Decode only what the mapper reads. With "Log every frame" ~5,000 frames/s arrive, and running the
            // mapper on each one stalled this thread: the dashboard stopped updating and notifications were lost.
            if (wanted.isNotEmpty() && (frame.bus.toLong() shl 32 or frame.address.toLong()) !in wanted) continue
            currentState = decoder.decodeFrame(frame.bus, frame.address, frame.data) ?: return
            decoded++
        }
        if (_incoming.tryEmit(currentState)) emitted++ else emitFailed++
        if (logging) appRecord()
    }

    // App-side record in the CAN log every 5 s (bus 31, id 0x1DA5F000), to tell where a frozen dashboard stalls:
    // [0..1] frames decoded  [2..3] states handed to the dashboard  [4..5] states it refused (collector behind)
    // [6..7] main-thread delay, ms (how long a task posted to the UI thread has been waiting or took to start)
    private fun appRecord() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - recordAt < 5000) return
        recordAt = now
        val lag = if (mainPostedAt != 0L) now - mainPostedAt else mainLagMs  // still waiting: its age so far
        fun put(b: ByteArray, o: Int, v: Long) { val c = v.coerceIn(0, 0xFFFF).toInt(); b[o] = c.toByte(); b[o + 1] = (c shr 8).toByte() }
        val d = ByteArray(8)
        put(d, 0, decoded.toLong()); put(d, 2, emitted.toLong()); put(d, 4, emitFailed.toLong()); put(d, 6, lag)
        canLog.write(31, 0x1DA5F000, d)
        // 0x1DA5F001, since the app started: [0..1] pings sent  [2..3] pings Android refused  [4..5] writes that failed
        // [6..7] longest time spent handling one notification in the last 5 s, ms (a stall here holds up all others)
        val w = ByteArray(8)
        put(w, 0, manager.pingsSent.toLong()); put(w, 2, manager.pingsRefused.toLong()); put(w, 4, manager.writeErrors.toLong())
        put(w, 6, handleMaxMs); handleMaxMs = 0
        canLog.write(31, 0x1DA5F001, w)
        decoded = 0; emitted = 0; emitFailed = 0
        if (mainPostedAt == 0L) {
            mainPostedAt = now
            handler.post { mainLagMs = android.os.SystemClock.elapsedRealtime() - mainPostedAt; mainPostedAt = 0L }
        }
    }
}
