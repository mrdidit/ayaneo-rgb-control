package dev.ayaneo.rgbcontrol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PocketEvoRgbProtocolTest {
    @Test
    fun ringAndZoneMappingsAreStable() {
        assertEquals(0x21, PocketEvoRgbProtocol.Ring.LEFT.selector)
        assertEquals(0x20, PocketEvoRgbProtocol.Ring.RIGHT.selector)
        assertEquals(0x1C, PocketEvoRgbProtocol.Ring.BROADCAST.selector)

        assertEquals(0, PocketEvoRgbProtocol.Zone.LEFT_270.index)
        assertEquals(1, PocketEvoRgbProtocol.Zone.TOP_0.index)
        assertEquals(2, PocketEvoRgbProtocol.Zone.RIGHT_90.index)
        assertEquals(3, PocketEvoRgbProtocol.Zone.BOTTOM_180.index)
    }

    @Test
    fun staticFrameMatchesGoldenVector() {
        val frame = PocketEvoRgbProtocol.buildStaticFrame(
            red = 0x12,
            green = 0x34,
            blue = 0x56,
            brightness = 0x78,
        )

        assertArrayEquals(
            bytes(
                0xF7, 0x00, 0x1C,
                0x20, 0x01,
                0x80, 0x00,
                0x81, 0x00,
                0x8B, 0x0F,
                0x88, 0x12,
                0x89, 0x34,
                0x8A, 0x56,
                0x86, 0x78,
                0x87, 0x78,
                0x58, 0x02,
                0x45, 0x00,
                0xAB, 0xED,
            ),
            frame,
        )
        assertRegisterFrame(frame, PocketEvoRgbProtocol.Ring.BROADCAST)
    }

    @Test
    fun sameColourReactiveInitializerMatchesGoldenVector() {
        val frame = PocketEvoRgbProtocol.buildSameColourReactiveInitializerFrame(
            red = 0x12,
            green = 0x34,
            blue = 0x56,
            brightness = 0x7F,
        )

        assertArrayEquals(
            bytes(0xF7, 0x55, 0x7F, 0x12, 0x34, 0x56, 0x12, 0x34, 0x56, 0x0C, 0xED),
            frame,
        )
        assertEquals(
            checksum(frame, 1, 8),
            frame[9].toInt() and 0xFF,
        )
    }

    @Test
    fun perZoneFrameMatchesGoldenVectorAndUsesZeroSentinelPadding() {
        val frame = PocketEvoRgbProtocol.buildPerZoneFrame(
            ring = PocketEvoRgbProtocol.Ring.RIGHT,
            zone = PocketEvoRgbProtocol.Zone.LEFT_270,
            red = 0xFF,
            green = 0x00,
            blue = 0x00,
            brightness = 0x80,
        )

        assertArrayEquals(
            bytes(
                0xF7, 0x00, 0x20,
                0x20, 0x01,
                0x58, 0x02,
                0x8B, 0x40,
                0x46, 0xFF,
                0x47, 0x00,
                0x48, 0x00,
                0x21, 0x80,
                0x45, 0x00,
                0x00, 0x00,
                0x00, 0x00,
                0x00, 0x00,
                0x20, 0xED,
            ),
            frame,
        )
        assertRegisterFrame(frame, PocketEvoRgbProtocol.Ring.RIGHT)
        assertArrayEquals(ByteArray(6), frame.copyOfRange(19, 25))
    }

    @Test
    fun everyZoneUsesItsValidatedRgbAndBrightnessRegisters() {
        PocketEvoRgbProtocol.Zone.entries.forEach { zone ->
            val frame = PocketEvoRgbProtocol.buildPerZoneFrame(
                ring = PocketEvoRgbProtocol.Ring.LEFT,
                zone = zone,
                red = 0x11,
                green = 0x22,
                blue = 0x33,
                brightness = 0x44,
            )
            val rgbBase = 0x46 + zone.index * 3
            assertEquals(rgbBase, frame[9].toInt() and 0xFF)
            assertEquals(0x11, frame[10].toInt() and 0xFF)
            assertEquals(rgbBase + 1, frame[11].toInt() and 0xFF)
            assertEquals(0x22, frame[12].toInt() and 0xFF)
            assertEquals(rgbBase + 2, frame[13].toInt() and 0xFF)
            assertEquals(0x33, frame[14].toInt() and 0xFF)
            assertEquals(0x21 + zone.index, frame[15].toInt() and 0xFF)
            assertEquals(0x44, frame[16].toInt() and 0xFF)
            assertRegisterFrame(frame, PocketEvoRgbProtocol.Ring.LEFT)
        }
    }

    @Test
    fun perZoneFrameRejectsBroadcastAndOutOfRangeChannels() {
        assertThrows(IllegalArgumentException::class.java) {
            PocketEvoRgbProtocol.buildPerZoneFrame(
                ring = PocketEvoRgbProtocol.Ring.BROADCAST,
                zone = PocketEvoRgbProtocol.Zone.TOP_0,
                red = 0,
                green = 0,
                blue = 0,
                brightness = 0,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PocketEvoRgbProtocol.buildStaticFrame(
                red = 256,
                green = 0,
                blue = 0,
                brightness = 0,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PocketEvoRgbProtocol.buildSameColourReactiveInitializerFrame(
                red = 0,
                green = -1,
                blue = 0,
                brightness = 0,
            )
        }
    }

    @Test
    fun octalShellEscapeCoversUnsignedByteRange() {
        assertEquals(
            "\\367\\000\\034\\200\\355",
            PocketEvoRgbProtocol.toOctalShellEscape(bytes(0xF7, 0x00, 0x1C, 0x80, 0xED)),
        )
    }

    private fun assertRegisterFrame(
        frame: ByteArray,
        ring: PocketEvoRgbProtocol.Ring,
    ) {
        assertEquals(PocketEvoRgbProtocol.REGISTER_FRAME_SIZE, frame.size)
        assertEquals(0xF7, frame[0].toInt() and 0xFF)
        assertEquals(0x00, frame[1].toInt() and 0xFF)
        assertEquals(ring.selector, frame[2].toInt() and 0xFF)
        assertEquals(checksum(frame, 1, 24), frame[25].toInt() and 0xFF)
        assertEquals(0xED, frame[26].toInt() and 0xFF)
    }

    private fun checksum(frame: ByteArray, first: Int, last: Int): Int =
        (first..last).sumOf { frame[it].toInt() and 0xFF } and 0xFF

    private fun bytes(vararg values: Int): ByteArray =
        ByteArray(values.size) { index -> values[index].toByte() }
}
