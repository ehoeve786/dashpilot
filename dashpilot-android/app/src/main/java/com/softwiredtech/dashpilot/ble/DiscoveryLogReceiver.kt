package com.softwiredtech.dashpilot.ble

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records the DashKit's CAN-discovery stream: the DashKit (discovery mode, on
 * this phone's hotspot) sends "ms,bus,id,data" lines (bus 0=chassis, 1=vehicle)
 * over UDP to the hotspot gateway, i.e. this phone. Saved to Downloads as CSV.
 */
object DiscoveryLogReceiver {
    private const val TAG = "DiscoveryLog"
    const val PORT = 4242

    /** Loss since recording started: [lostPackets] UDP datagrams (seq gaps), [overflows]
     *  CAN-chip RX FIFO overflows per bus, [queueDrops] lines the DashKit's Wi-Fi queue dropped. */
    data class Stats(
        val file: String? = null,
        val lines: Long = 0,
        val ids: Int = 0,
        val lostPackets: Long = 0,
        val overflows: List<Long> = listOf(0, 0),
        val queueDrops: Long = 0,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    fun start(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        _running.value = true
        job = scope.launch {
            try {
                record(app)
            } catch (e: Exception) {
                Log.e(TAG, "recording stopped", e)
            } finally {
                _running.value = false
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    private suspend fun record(context: Context) {
        val name = "dashkit_can_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("cannot create $name in Downloads")
        val ids = HashSet<Int>()
        var lines = 0L
        var lastSeq = -1L
        var lost = 0L
        var base: List<Long>? = null  // DashKit counters (since its boot) at recording start
        var loss = listOf(0L, 0L, 0L)
        _stats.value = Stats(name)

        DatagramSocket(null as java.net.SocketAddress?).use { sock ->
            sock.reuseAddress = true
            sock.soTimeout = 500  // wake up to notice cancellation
            sock.bind(InetSocketAddress(PORT))
            resolver.openOutputStream(uri)!!.buffered().use { out ->
                out.write("ms,bus,id,data\n".toByteArray())
                val buf = ByteArray(2048)
                val pkt = DatagramPacket(buf, buf.size)
                while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                    try {
                        sock.receive(pkt)
                    } catch (_: SocketTimeoutException) {
                        out.flush()
                        continue
                    }
                    // Datagrams are line-aligned: "#<seq>", optional "#stat,ms,ovf0,ovf1,drops", frames.
                    for (line in String(buf, 0, pkt.length, Charsets.US_ASCII).split('\n')) {
                        val f = line.split(',')
                        if (f[0] == "#stat") {
                            val v = f.drop(2).mapNotNull { it.toLongOrNull() }
                            if (v.size != 3) continue
                            val b = base
                            // First report, or the DashKit rebooted (counters went back): rebase.
                            if (b == null || v.indices.any { v[it] < b[it] }) base = v
                            loss = v.indices.map { v[it] - base!![it] }
                        } else if (line.startsWith("#")) {
                            val s = line.substring(1).toLongOrNull() ?: continue
                            if (lastSeq in 0 until s) lost += s - lastSeq - 1
                            lastSeq = s
                        } else if (f.size == 4 && (f[1] == "0" || f[1] == "1") && f[2].startsWith("0x")) {
                            f[2].substring(2).toIntOrNull(16)?.let { ids.add(f[1].toInt() shl 12 or it) }
                            out.write(line.toByteArray())
                            out.write('\n'.code)
                            lines++
                        }
                    }
                    _stats.value = Stats(name, lines, ids.size, lost, loss.take(2), loss[2])
                }
            }
        }
    }
}
