package com.softwiredtech.dashpilot.datasource

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CanPacketCompactTest {
    @Test
    fun parses_compact_packet() {
        // 0x7F0 on bus 1 with 2 bytes, 0x118 on bus 3 with 0 bytes, then a frame cut short (dropped).
        val packet = byteArrayOf(
            0x80.toByte(),
            0xF0.toByte(), 0x0F, 2, 0x0A, 0x3C,
            0x18, 0x19, 0,
            0x29, 0x01, 8, 1, 2,
        )
        val frames = parseCanPacket(packet)
        assertEquals(2, frames.size)
        assertEquals(1, frames[0].bus)
        assertEquals(0x7F0, frames[0].address)
        assertArrayEquals(byteArrayOf(0x0A, 0x3C), frames[0].data)
        assertEquals(3, frames[1].bus)
        assertEquals(0x118, frames[1].address)
        assertEquals(0, frames[1].data.size)
    }
}
