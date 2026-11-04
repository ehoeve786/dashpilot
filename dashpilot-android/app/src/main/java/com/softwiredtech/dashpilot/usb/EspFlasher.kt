package com.softwiredtech.dashpilot.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/**
 * Flashes the DashKit (ESP32-S3) over its USB-C port from the phone: puts the
 * chip into its ROM download mode with the DTR/RTS sequence esptool uses on
 * USB-Serial-JTAG, then speaks the ROM bootloader protocol (SLIP + SYNC,
 * SPI_ATTACH, SPI_SET_PARAMS, FLASH_BEGIN/DATA/END, FLASH_MD5). No stub, so
 * writes are uncompressed (~1 min/MB) but nothing needs to be uploaded first.
 *
 * Input: the CI "recovery set" zip; its FLASH.txt lists "<offset> <file>" pairs.
 */
object EspFlasher {
    private const val TAG = "EspFlasher"
    private const val ACTION_PERMISSION = "com.softwiredtech.dashpilot.USB_FLASH_PERMISSION"
    private const val ESPRESSIF_VID = 0x303A
    private const val FLASH_WRITE_SIZE = 0x400           // ROM loader block size
    private const val FLASH_SIZE = 16 * 1024 * 1024      // DashKit: 16 MB
    private const val S3_MAGIC_REG = 0x40001000L
    private const val S3_MAGIC = 0x9L

    sealed class State {
        object Idle : State()
        data class Busy(val message: String, val progress: Float = -1f) : State()
        data class Done(val message: String) : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    @Volatile private var running = false

    fun flashRecoverySet(context: Context, zip: Uri) {
        if (running) return
        running = true
        val app = context.applicationContext
        thread(name = "esp-flash", isDaemon = true) {
            try {
                UsbConsole.disconnect()
                val set = readSet(app, zip)
                busy("Entering download mode…")
                Thread.sleep(300)  // let the console's reader release the interface
                val before = enterBootloader(app)
                Port(app, waitForDevice(app, before)).use { p ->
                    p.sync()
                    val magic = p.readReg(S3_MAGIC_REG)
                    if (magic != S3_MAGIC) throw IllegalStateException("Not an ESP32-S3 (magic 0x${magic.toString(16)})")
                    p.command(OP_SPI_ATTACH, u32(0, 0))
                    p.command(OP_SPI_SET_PARAMS, u32(0, FLASH_SIZE.toLong(), 0x10000, 0x1000, 0x100, 0xFFFF))
                    for ((offset, name, data) in set) p.flash(offset, name, data)
                    p.command(OP_FLASH_END, u32(1))  // 1 = stay in bootloader
                    p.hardReset()
                }
                _state.value = State.Done("Flashed ${set.size} images. DashKit is restarting.")
            } catch (e: Exception) {
                Log.e(TAG, "flash failed", e)
                _state.value = State.Error(e.message ?: e.toString())
            } finally {
                running = false
            }
        }
    }

    private fun busy(msg: String, p: Float = -1f) { _state.value = State.Busy(msg, p) }

    private fun readSet(app: Context, zip: Uri): List<Triple<Long, String, ByteArray>> {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(app.contentResolver.openInputStream(zip)!!).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory) files[e.name.substringAfterLast('/')] = z.readBytes()
            }
        }
        val plan = files["FLASH.txt"]?.toString(Charsets.US_ASCII)
            ?: throw IllegalArgumentException("Zip has no FLASH.txt (use the CI recovery-set zip)")
        val tokens = plan.replace("\\", " ").split(Regex("\\s+")).filter { it.isNotEmpty() }
        val set = ArrayList<Triple<Long, String, ByteArray>>()
        for (i in 0 until tokens.size - 1) {
            val off = tokens[i].takeIf { it.startsWith("0x") }?.substring(2)?.toLongOrNull(16) ?: continue
            val name = tokens[i + 1]
            val data = files[name] ?: throw IllegalArgumentException("FLASH.txt names $name, not in zip")
            set.add(Triple(off, name, data))
        }
        if (set.isEmpty()) throw IllegalArgumentException("FLASH.txt lists no <offset> <file> pairs")
        return set
    }

    // ---- device plumbing -------------------------------------------------

    private fun findDevice(usb: UsbManager) = usb.deviceList.values.firstOrNull { it.vendorId == ESPRESSIF_VID }

    private fun withPermission(app: Context, usb: UsbManager, device: UsbDevice) {
        if (usb.hasPermission(device)) return
        busy("Waiting for USB permission…")
        val latch = CountDownLatch(1)
        var granted = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                app.unregisterReceiver(this); latch.countDown()
            }
        }
        app.registerReceiver(receiver, IntentFilter(ACTION_PERMISSION), Context.RECEIVER_NOT_EXPORTED)
        usb.requestPermission(device, PendingIntent.getBroadcast(
            app, 1, Intent(ACTION_PERMISSION).setPackage(app.packageName), PendingIntent.FLAG_MUTABLE))
        if (!latch.await(60, TimeUnit.SECONDS) || !granted) throw IllegalStateException("USB permission denied")
    }

    /** esptool's UsbJtagSerialReset: DTR/RTS pattern the S3 maps onto EN/IO0. The chip re-enumerates. */
    private fun enterBootloader(app: Context): String {
        val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
        val dev = findDevice(usb) ?: throw IllegalStateException("No DashKit on USB")
        withPermission(app, usb, dev)
        Port(app, dev).use { p ->  // each step mirrors one pyserial setDTR/setRTS call
            p.lines(dtr = false, rts = false); Thread.sleep(100)
            p.lines(dtr = true, rts = false); Thread.sleep(100)
            p.lines(dtr = true, rts = true)
            p.lines(dtr = false, rts = true)
            p.lines(dtr = false, rts = true); Thread.sleep(100)
            runCatching { p.lines(dtr = false, rts = false) }  // chip is resetting; may already be gone
        }
        return dev.deviceName
    }

    /** The reset re-enumerates the chip: wait for a device with a new name (or the old one if it never left). */
    private fun waitForDevice(app: Context, oldName: String): UsbDevice {
        val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
        val deadline = System.currentTimeMillis() + 10_000
        var seenGone = false
        while (System.currentTimeMillis() < deadline) {
            val d = findDevice(usb)
            if (d == null) seenGone = true
            else if (d.deviceName != oldName || seenGone) { withPermission(app, usb, d); return d }
            Thread.sleep(200)
        }
        return findDevice(usb)?.also { withPermission(app, usb, it) }
            ?: throw IllegalStateException("DashKit did not come back after reset")
    }

    // ---- ROM bootloader protocol -----------------------------------------

    private const val OP_FLASH_BEGIN = 0x02
    private const val OP_FLASH_DATA = 0x03
    private const val OP_FLASH_END = 0x04
    private const val OP_SYNC = 0x08
    private const val OP_READ_REG = 0x0A
    private const val OP_SPI_SET_PARAMS = 0x0B
    private const val OP_SPI_ATTACH = 0x0D
    private const val OP_FLASH_MD5 = 0x13
    private const val STATUS_BYTES = 4  // ESP32-family ROM (stub would be 2)

    private fun u32(vararg v: Long): ByteArray {
        val b = ByteArray(v.size * 4)
        v.forEachIndexed { i, x -> for (k in 0..3) b[i * 4 + k] = (x shr (8 * k)).toByte() }
        return b
    }

    private class Port(app: Context, dev: UsbDevice) : AutoCloseable {
        private val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
        private val conn: UsbDeviceConnection = usb.openDevice(dev) ?: throw IllegalStateException("Could not open USB device")
        private val comm: UsbInterface
        private val data: UsbInterface
        private val epIn: UsbEndpoint
        private val epOut: UsbEndpoint
        private val rx = ByteArrayOutputStream()
        private val buf = ByteArray(4096)

        init {
            val ifaces = (0 until dev.interfaceCount).map(dev::getInterface)
            comm = ifaces.first { it.interfaceClass == UsbConstants.USB_CLASS_COMM }
            data = ifaces.first { it.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA }
            val eps = (0 until data.endpointCount).map(data::getEndpoint).filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
            epIn = eps.first { it.direction == UsbConstants.USB_DIR_IN }
            epOut = eps.first { it.direction == UsbConstants.USB_DIR_OUT }
            if (!conn.claimInterface(comm, true) || !conn.claimInterface(data, true)) {
                conn.close(); throw IllegalStateException("Could not claim USB interfaces")
            }
        }

        fun lines(dtr: Boolean, rts: Boolean) {  // CDC SET_CONTROL_LINE_STATE
            val v = (if (dtr) 1 else 0) or (if (rts) 2 else 0)
            if (conn.controlTransfer(0x21, 0x22, v, comm.id, null, 0, 1000) < 0) throw IllegalStateException("DTR/RTS set failed")
        }

        fun hardReset() {
            lines(dtr = false, rts = true); Thread.sleep(100)
            runCatching { lines(dtr = false, rts = false) }
        }

        private fun write(pkt: ByteArray) {
            val out = ByteArrayOutputStream(pkt.size + 16)
            out.write(0xC0)
            for (b in pkt) when (b.toInt() and 0xFF) {
                0xC0 -> { out.write(0xDB); out.write(0xDC) }
                0xDB -> { out.write(0xDB); out.write(0xDD) }
                else -> out.write(b.toInt())
            }
            out.write(0xC0)
            val bytes = out.toByteArray()
            if (conn.bulkTransfer(epOut, bytes, bytes.size, 2000) != bytes.size) throw IllegalStateException("USB write failed")
        }

        /** Next SLIP frame, or null on timeout. Non-SLIP bytes (ROM banner) are skipped. */
        private fun readFrame(timeoutMs: Long): ByteArray? {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val all = rx.toByteArray()
                val start = all.indexOf(0xC0.toByte())
                if (start >= 0) {
                    val end = all.indexOfFrom(0xC0.toByte(), start + 1)
                    if (end > start + 1) {
                        rx.reset(); rx.write(all, end + 1, all.size - end - 1)
                        val f = ByteArrayOutputStream(); var i = start + 1
                        while (i < end) {
                            val b = all[i].toInt() and 0xFF
                            if (b == 0xDB && i + 1 < end) { f.write(if (all[i + 1].toInt() == 0xDC) 0xC0 else 0xDB); i += 2 } else { f.write(b); i++ }
                        }
                        return f.toByteArray()
                    } else if (end == start + 1) {  // empty C0 C0: drop the first
                        rx.reset(); rx.write(all, end, all.size - end); continue
                    }
                } else if (all.isNotEmpty()) rx.reset()  // garbage before any frame start
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return null
                val n = conn.bulkTransfer(epIn, buf, buf.size, left.coerceAtMost(500).toInt())
                if (n > 0) rx.write(buf, 0, n)
            }
        }

        private fun ByteArray.indexOfFrom(v: Byte, from: Int): Int { for (i in from until size) if (this[i] == v) return i; return -1 }

        /** Send a command, return (value, data-minus-status). Throws on ROM error status. */
        fun command(op: Int, payload: ByteArray, chk: Long = 0, timeoutMs: Long = 3000, check: Boolean = true): Pair<Long, ByteArray> {
            val pkt = ByteArray(8 + payload.size)
            pkt[0] = 0; pkt[1] = op.toByte(); pkt[2] = payload.size.toByte(); pkt[3] = (payload.size shr 8).toByte()
            u32(chk).copyInto(pkt, 4); payload.copyInto(pkt, 8)
            write(pkt)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val f = readFrame(deadline - System.currentTimeMillis()) ?: throw IllegalStateException("No response to command 0x${op.toString(16)}")
                if (f.size < 8 || f[0].toInt() != 1 || (f[1].toInt() and 0xFF) != op) continue
                val value = (0..3).fold(0L) { a, k -> a or ((f[4 + k].toLong() and 0xFF) shl (8 * k)) }
                val body = f.copyOfRange(8, f.size)
                if (!check) return value to body
                if (body.size < STATUS_BYTES) throw IllegalStateException("Short response to 0x${op.toString(16)}")
                if (body[body.size - STATUS_BYTES].toInt() != 0) {
                    throw IllegalStateException("ROM error 0x${(body[body.size - STATUS_BYTES + 1].toInt() and 0xFF).toString(16)} on command 0x${op.toString(16)}")
                }
                return value to body.copyOfRange(0, body.size - STATUS_BYTES)
            }
        }

        fun sync() {
            val payload = byteArrayOf(0x07, 0x07, 0x12, 0x20) + ByteArray(32) { 0x55 }
            var ok = false
            for (attempt in 1..10) {
                try { command(OP_SYNC, payload, timeoutMs = 500, check = false); ok = true; break } catch (_: IllegalStateException) {}
            }
            if (!ok) throw IllegalStateException("No SYNC reply from the ROM bootloader")
            while (readFrame(200) != null) { /* drain the extra SYNC replies */ }
        }

        fun readReg(addr: Long): Long = command(OP_READ_REG, u32(addr)).first

        fun flash(offset: Long, name: String, image: ByteArray) {
            val blocks = (image.size + FLASH_WRITE_SIZE - 1) / FLASH_WRITE_SIZE
            busy("Erasing $name at 0x${offset.toString(16)}…")
            command(OP_FLASH_BEGIN, u32(image.size.toLong(), blocks.toLong(), FLASH_WRITE_SIZE.toLong(), offset, 0),
                timeoutMs = 10_000 + 30_000L * ((image.size + 0xFFFFF) / 0x100000))
            for (seq in 0 until blocks) {
                val block = ByteArray(FLASH_WRITE_SIZE) { 0xFF.toByte() }
                val n = minOf(FLASH_WRITE_SIZE, image.size - seq * FLASH_WRITE_SIZE)
                image.copyInto(block, 0, seq * FLASH_WRITE_SIZE, seq * FLASH_WRITE_SIZE + n)
                val chk = block.fold(0xEFL) { a, b -> a xor (b.toLong() and 0xFF) }
                command(OP_FLASH_DATA, u32(FLASH_WRITE_SIZE.toLong(), seq.toLong(), 0, 0) + block, chk)
                if (seq % 16 == 0 || seq == blocks - 1) busy("Writing $name ${seq + 1}/$blocks", (seq + 1f) / blocks)
            }
            busy("Verifying $name…")
            val want = MessageDigest.getInstance("MD5").digest(image).joinToString("") { "%02x".format(it) }
            val got = command(OP_FLASH_MD5, u32(offset, image.size.toLong(), 0, 0), timeoutMs = 10_000 + 8_000L * ((image.size + 0xFFFFF) / 0x100000)).second
            val gotHex = if (got.size == 32) String(got, Charsets.US_ASCII) else got.joinToString("") { "%02x".format(it) }
            if (!gotHex.equals(want, ignoreCase = true)) throw IllegalStateException("$name verify failed ($gotHex != $want)")
        }

        override fun close() {
            runCatching { conn.releaseInterface(data) }; runCatching { conn.releaseInterface(comm) }; conn.close()
        }
    }
}
