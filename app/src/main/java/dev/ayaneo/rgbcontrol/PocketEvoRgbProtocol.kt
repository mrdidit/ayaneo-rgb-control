package dev.ayaneo.rgbcontrol

/** Pure packet builders for the validated Pocket EVO (AR07) RGB protocol. */
object PocketEvoRgbProtocol {
    const val REGISTER_FRAME_SIZE = 27
    const val CONTROLLER_FOLLOW_FRAME_SIZE = 11

    private const val FRAME_PREFIX = 0xF7
    private const val FRAME_SUFFIX = 0xED
    private const val REGISTER_PAIR_COUNT = 11

    enum class Ring(val selector: Int) {
        LEFT(0x21),
        RIGHT(0x20),
        BROADCAST(0x1C),
    }

    enum class Zone(val index: Int) {
        LEFT_270(0),
        TOP_0(1),
        RIGHT_90(2),
        BOTTOM_180(3),
    }

    private data class RegisterWrite(val register: Int, val value: Int)

    /**
     * Builds the stock-style Static register frame. [ring] defaults to both rings.
     */
    fun buildStaticFrame(
        red: Int,
        green: Int,
        blue: Int,
        brightness: Int,
        ring: Ring = Ring.BROADCAST,
    ): ByteArray = buildRegisterFrame(
        ring = ring,
        writes = listOf(
            RegisterWrite(0x20, 0x01),
            RegisterWrite(0x80, 0x00),
            RegisterWrite(0x81, 0x00),
            RegisterWrite(0x8B, 0x0F),
            RegisterWrite(0x88, requireByte("red", red)),
            RegisterWrite(0x89, requireByte("green", green)),
            RegisterWrite(0x8A, requireByte("blue", blue)),
            RegisterWrite(0x86, requireByte("brightness", brightness)),
            RegisterWrite(0x87, brightness),
            RegisterWrite(0x58, 0x02),
            RegisterWrite(0x45, 0x00),
        ),
    )

    /**
     * Builds the controller-follow initializer with the same colour in both RGB slots.
     */
    fun buildSameColourReactiveInitializerFrame(
        red: Int,
        green: Int,
        blue: Int,
        brightness: Int,
    ): ByteArray {
        val checkedRed = requireByte("red", red)
        val checkedGreen = requireByte("green", green)
        val checkedBlue = requireByte("blue", blue)
        val checkedBrightness = requireByte("brightness", brightness)
        val frame = byteArrayOf(
            FRAME_PREFIX.toByte(),
            0x55,
            checkedBrightness.toByte(),
            checkedRed.toByte(),
            checkedGreen.toByte(),
            checkedBlue.toByte(),
            checkedRed.toByte(),
            checkedGreen.toByte(),
            checkedBlue.toByte(),
            0x00,
            FRAME_SUFFIX.toByte(),
        )
        frame[9] = checksum(frame, 1, 8).toByte()
        return frame
    }

    /**
     * Builds one fixed-size register frame for one physical ring zone.
     *
     * AR07 stops processing register pairs at the first zero register. The unused
     * tail is therefore deliberately left as `00 00` pairs instead of being
     * populated with speculative writes.
     */
    fun buildPerZoneFrame(
        ring: Ring,
        zone: Zone,
        red: Int,
        green: Int,
        blue: Int,
        brightness: Int,
    ): ByteArray {
        require(ring != Ring.BROADCAST) {
            "Per-zone frames require LEFT or RIGHT, not BROADCAST"
        }
        val rgbBase = 0x46 + zone.index * 3
        return buildRegisterFrame(
            ring = ring,
            writes = listOf(
                RegisterWrite(0x20, 0x01),
                RegisterWrite(0x58, 0x02),
                RegisterWrite(0x8B, 0x40),
                RegisterWrite(rgbBase, requireByte("red", red)),
                RegisterWrite(rgbBase + 1, requireByte("green", green)),
                RegisterWrite(rgbBase + 2, requireByte("blue", blue)),
                RegisterWrite(0x21 + zone.index, requireByte("brightness", brightness)),
                RegisterWrite(0x45, 0x00),
            ),
        )
    }

    /** Returns bytes as a single-quoted-shell-safe octal escape sequence. */
    fun toOctalShellEscape(frame: ByteArray): String = buildString(frame.size * 4) {
        frame.forEach { byte ->
            append('\\')
            append((byte.toInt() and 0xFF).toString(8).padStart(3, '0'))
        }
    }

    private fun buildRegisterFrame(
        ring: Ring,
        writes: List<RegisterWrite>,
    ): ByteArray {
        require(writes.size <= REGISTER_PAIR_COUNT) {
            "A Pocket EVO register frame supports at most $REGISTER_PAIR_COUNT writes"
        }
        writes.forEach {
            require(it.register in 1..0xFF) {
                "Register IDs must be non-zero bytes; zero terminates the write list"
            }
            requireByte("register value", it.value)
        }

        val frame = ByteArray(REGISTER_FRAME_SIZE)
        frame[0] = FRAME_PREFIX.toByte()
        frame[1] = 0x00
        frame[2] = ring.selector.toByte()
        writes.forEachIndexed { index, write ->
            val offset = 3 + index * 2
            frame[offset] = write.register.toByte()
            frame[offset + 1] = write.value.toByte()
        }
        frame[25] = checksum(frame, 1, 24).toByte()
        frame[26] = FRAME_SUFFIX.toByte()
        return frame
    }

    private fun requireByte(name: String, value: Int): Int {
        require(value in 0..0xFF) { "$name must be in 0..255 (was $value)" }
        return value
    }

    private fun checksum(frame: ByteArray, first: Int, last: Int): Int =
        (first..last).sumOf { frame[it].toInt() and 0xFF } and 0xFF
}
