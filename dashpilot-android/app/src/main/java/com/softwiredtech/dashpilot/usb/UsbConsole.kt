package com.softwiredtech.dashpilot.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Serial console on the DashKit's USB-C port (the ESP32-S3's built-in
 * USB-Serial-JTAG, a CDC-ACM device: Espressif VID 0x303A). Read-only: shows
 * the firmware log live and saves it to Downloads/dashkit_usb_*.log. DTR/RTS
 * are deliberately never asserted (on the S3 they can trigger reset/bootloader).
 */
object UsbConsole {
    private const val TAG = "UsbConsole"
    private const val ACTION_PERMISSION = "com.softwiredtech.dashpilot.USB_PERMISSION"
    private const val ESPRESSIF_VID = 0x303A
    private const val KEEP_LINES = 200

    sealed class State {
        object Idle : State()
        object NoDevice : State()
        object WaitingPermission : State()
        data class Connected(val name: String, val file: String) : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines
    @Volatile private var connection: UsbDeviceConnection? = null

    fun connect(context: Context) {
        val app = context.applicationContext
        val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = usb.deviceList.values.firstOrNull { it.vendorId == ESPRESSIF_VID }
            ?: usb.deviceList.values.firstOrNull { dataInterface(it) != null }
            ?: run { _state.value = State.NoDevice; return }
        if (usb.hasPermission(device)) {
            open(app, usb, device)
            return
        }
        _state.value = State.WaitingPermission
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                app.unregisterReceiver(this)
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    open(app, usb, device)
                } else {
                    _state.value = State.Error("USB permission denied")
                }
            }
        }
        app.registerReceiver(receiver, IntentFilter(ACTION_PERMISSION), Context.RECEIVER_NOT_EXPORTED)
        val pi = PendingIntent.getBroadcast(
            app, 0, Intent(ACTION_PERMISSION).setPackage(app.packageName), PendingIntent.FLAG_MUTABLE
        )
        usb.requestPermission(device, pi)
    }

    fun disconnect() {
        connection?.close()  // unblocks the reader thread, which finishes the cleanup
        connection = null
    }

    private fun dataInterface(d: UsbDevice) =
        (0 until d.interfaceCount).map(d::getInterface).firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA }

    private fun open(app: Context, usb: UsbManager, device: UsbDevice) {
        val iface = dataInterface(device) ?: run { _state.value = State.Error("No CDC data interface"); return }
        val ep = (0 until iface.endpointCount).map(iface::getEndpoint)
            .firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN }
            ?: run { _state.value = State.Error("No bulk IN endpoint"); return }
        val conn = usb.openDevice(device) ?: run { _state.value = State.Error("Could not open device"); return }
        if (!conn.claimInterface(iface, true)) {
            conn.close(); _state.value = State.Error("Could not claim interface"); return
        }
        val name = "dashkit_usb_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".log"
        val resolver = app.contentResolver
        val uri = resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            }
        ) ?: run { conn.close(); _state.value = State.Error("Could not create log file"); return }
        connection = conn
        _lines.value = emptyList()
        _state.value = State.Connected(device.productName ?: "USB serial", name)

        thread(name = "usb-console", isDaemon = true) {
            val buf = ByteArray(ep.maxPacketSize.coerceAtLeast(512))
            val pending = StringBuilder()
            try {
                resolver.openOutputStream(uri)!!.buffered().use { out ->
                    while (connection === conn) {
                        val n = conn.bulkTransfer(ep, buf, buf.size, 500)
                        if (n < 0) continue  // timeout; also where a closed connection exits via the loop check
                        out.write(buf, 0, n)
                        pending.append(String(buf, 0, n, Charsets.ISO_8859_1))
                        var nl = pending.indexOf('\n')
                        if (nl < 0) continue
                        val fresh = ArrayList<String>()
                        while (nl >= 0) {
                            fresh.add(pending.substring(0, nl).trimEnd('\r'))
                            pending.delete(0, nl + 1)
                            nl = pending.indexOf('\n')
                        }
                        _lines.value = (_lines.value + fresh).takeLast(KEEP_LINES)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "console ended", e)
            } finally {
                runCatching { conn.releaseInterface(iface) }
                runCatching { conn.close() }
                if (connection === conn) connection = null
                _state.value = State.Idle
            }
        }
    }
}
