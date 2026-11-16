package com.softwiredtech.dashpilot.datasource

class RawCanFrame(
    val bus: Int,
    val address: Int,
    val data: ByteArray,
)

// Wire format from firmware (build_ble_packet):
//   [count : 1]
//   per frame:
//     [timestamp_us : LE32]
//     [bus          : 1]
//     [addr         : LE32]
//     [len          : 1]
//     [data         : len bytes]
internal fun parseCanPacket(payload: ByteArray): List<RawCanFrame> {
    if (payload.isEmpty()) return emptyList()
    if (payload[0] == COMPACT_MARK) return parseCompact(payload)
    val frames = ArrayList<RawCanFrame>(payload[0].toInt() and 0xFF)
    var offset = 1
    val count = payload[0].toInt() and 0xFF
    for (i in 0 until count) {
        if (offset + 4 > payload.size) break
        offset += 4
        if (offset >= payload.size) break
        val bus = payload[offset].toInt() and 0xFF
        offset += 1
        if (offset + 4 > payload.size) break
        val addr = java.nio.ByteBuffer.wrap(payload, offset, 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        offset += 4
        if (offset >= payload.size) break
        val len = payload[offset].toInt() and 0xFF
        offset += 1
        if (offset + len > payload.size) break
        frames.add(RawCanFrame(bus, addr, payload.copyOfRange(offset, offset + len)))
        offset += len
    }
    return frames
}

// Compact packet, sent once the app has asked for it (VehicleControl.CMD_COMPACT):
// [0x80][id | bus << 11 (LE16), len, data]...  The original format starts with a frame count below 0x80.
private const val COMPACT_MARK = 0x80.toByte()

private fun parseCompact(p: ByteArray): List<RawCanFrame> {
    val frames = ArrayList<RawCanFrame>((p.size - 1) / 11 + 1)
    var o = 1
    while (o + 3 <= p.size) {
        val h = (p[o].toInt() and 0xFF) or ((p[o + 1].toInt() and 0xFF) shl 8)
        val len = p[o + 2].toInt() and 0xFF
        o += 3
        if (len > 8 || o + len > p.size) break
        frames.add(RawCanFrame(h ushr 11, h and 0x7FF, p.copyOfRange(o, o + len)))
        o += len
    }
    return frames
}
