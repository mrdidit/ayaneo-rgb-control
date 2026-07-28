package dev.ayaneo.rgbcontrol

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ApplyResult(val success: Boolean, val message: String)
data class SavedRgbSettings(
    val red: Int = 255,
    val green: Int = 255,
    val blue: Int = 43,
    val brightness: Int = 87,
    val mode: Int = 6,
    val colorCorrection: Boolean = true,
    val livePreview: Boolean = true,
    val ledEnabled: Boolean = true,
)

class RgbController(private val context: Context) {
    companion object {
        private const val GAMEWINDOW_PACKAGE = "com.ayaneo.gamewindow"
        private const val GAMEWINDOW_SERVICE =
            "com.ayaneo.gamewindow.utils.aidl.AyaAidlService"
        private const val AIDL_DESCRIPTOR =
            "com.ayaneo.gamewindow.AyaAidlInterface"
        private const val CONFIG_DIR = "/data/media/0/.aya"
    }

    @Volatile
    private var binder: IBinder? = null
    private val preferences =
        context.getSharedPreferences("rgb_settings", Context.MODE_PRIVATE)

    fun loadSettings(): SavedRgbSettings = SavedRgbSettings(
        red = preferences.getInt("red", 255),
        green = preferences.getInt("green", 255),
        blue = preferences.getInt("blue", 43),
        brightness = preferences.getInt("brightness", 87),
        mode = preferences.getInt("mode", 6).let { if (it == 100) 3 else it },
        colorCorrection = preferences.getBoolean("color_correction", true),
        livePreview = preferences.getBoolean("live_preview", true),
        ledEnabled = preferences.getBoolean("led_enabled", true),
    )

    fun saveLivePreview(enabled: Boolean) {
        preferences.edit().putBoolean("live_preview", enabled).apply()
    }

    fun saveMode(mode: Int) {
        preferences.edit().putInt("mode", mode).apply()
    }

    fun loadCustomColors(): List<Int?> =
        List(8) { index ->
            val key = "custom_color_$index"
            if (preferences.contains(key)) preferences.getInt(key, 0) else null
        }

    fun saveCustomColor(index: Int, color: Int) {
        if (index in 0..7) {
            preferences.edit().putInt("custom_color_$index", color).apply()
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
        }
    }

    fun bind(): Boolean {
        val intent = Intent().setClassName(GAMEWINDOW_PACKAGE, GAMEWINDOW_SERVICE)
        return runCatching {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
    }

    fun unbind() {
        runCatching { context.unbindService(connection) }
        binder = null
    }

    suspend fun apply(
        mode: Int,
        red: Int,
        green: Int,
        blue: Int,
        brightness: Int,
        colorCorrection: Boolean,
        persist: Boolean = true,
    ): ApplyResult =
        withContext(Dispatchers.IO) {
            if (persist) {
                preferences.edit()
                    .putInt("red", red)
                    .putInt("green", green)
                    .putInt("blue", blue)
                    .putInt("brightness", brightness)
                    .putInt("mode", mode)
                    .putBoolean("color_correction", colorCorrection)
                    .apply()
            }
            val correctedGreen = if (colorCorrection && red > 0 && green > 0) {
                (green * 0.20f).toInt().coerceAtLeast(1)
            } else green
            val correctedBlue = if (colorCorrection && red > 0 && blue > 0) {
                (blue * 0.35f).toInt().coerceAtLeast(1)
            } else blue
            val colorFile = when (mode) {
                3 -> "aya_rgb_breath_single_mode_color.conf"
                6 -> "aya_rgb_single_mode_color.conf"
                else -> "aya_rgb_default_mode_color.conf"
            }
            val brightnessFile = when (mode) {
                3 -> "aya_rgb_breath_single_mode_bright.conf"
                6 -> "aya_rgb_single_mode_bright.conf"
                else -> "aya_rgb_default_mode_bright.conf"
            }
            val values = mapOf(
                "aya_rgb_mode.conf" to mode.toString(),
                "aya_rgb_is_open.conf" to "true",
                colorFile to "${red.coerceIn(0, 255)},${correctedGreen.coerceIn(0, 255)},${correctedBlue.coerceIn(0, 255)}",
                brightnessFile to brightness.coerceIn(1, 100).toString(),
            )

            var command = values.entries.joinToString(" && ") { (file, value) ->
                "printf '%s' '$value' > '$CONFIG_DIR/$file'"
            }
            if (mode == 6) {
                command += " && ${buildDirectStaticCommand(
                    red.coerceIn(0, 255),
                    correctedGreen.coerceIn(0, 255),
                    correctedBlue.coerceIn(0, 255),
                    brightness.coerceIn(1, 100),
                )}"
            }
            val process = runCatching {
                ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            }.getOrElse {
                return@withContext ApplyResult(false, "Could not start root shell: ${it.message}")
            }
            val output = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor() != 0) {
                return@withContext ApplyResult(false, output.ifBlank { "Root write failed" })
            }

            val sent = sendApplyMessage()
            ApplyResult(
                sent,
                if (sent) "Applied #${"%02X%02X%02X".format(red, green, blue)}"
                else "Saved values, but GameWindow IPC is not connected",
            )
        }

    private fun buildDirectStaticCommand(
        red: Int,
        green: Int,
        blue: Int,
        brightness: Int,
    ): String {
        val level = (brightness * 255 / 100).coerceIn(1, 255)
        val packet = intArrayOf(
            0xF7, 0x00, 0x1C,
            0x20, 0x01,
            0x80, 0x00,
            0x81, 0x00,
            0x8B, 0x0F,
            0x88, red,
            0x89, green,
            0x8A, blue,
            0x86, level,
            0x87, level,
            0x58, 0x08,
            0x45, 0x00,
            0x00, 0xED,
        )
        packet[25] = (1..24).sumOf { packet[it] } and 0xFF
        val escaped = packet.joinToString(separator = "") { "\\%03o".format(it) }
        return "printf '$escaped' > /dev/ttyHS5"
    }

    suspend fun setLedEnabled(enabled: Boolean): ApplyResult = withContext(Dispatchers.IO) {
        preferences.edit().putBoolean("led_enabled", enabled).apply()
        val value = enabled.toString()
        val process = runCatching {
            ProcessBuilder(
                "su",
                "-c",
                "printf '%s' '$value' > '$CONFIG_DIR/aya_rgb_is_open.conf'",
            ).redirectErrorStream(true).start()
        }.getOrElse {
            return@withContext ApplyResult(false, "Could not start root shell: ${it.message}")
        }
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() != 0) {
            return@withContext ApplyResult(false, output.ifBlank { "LED state write failed" })
        }
        val sent = sendRgbMessage("com_set_rgb_is_open:$value")
        ApplyResult(sent, if (sent) "LEDs ${if (enabled) "on" else "off"}" else "Saved LED state; IPC unavailable")
    }

    private fun sendApplyMessage(): Boolean =
        sendRgbMessage("com_set_rgb_is_open:true")

    private fun sendRgbMessage(message: String): Boolean {
        val remote = binder ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(AIDL_DESCRIPTOR)
            data.writeString("rgbpicker:msg_type_rgb:$message")
            remote.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0)
            reply.readException()
            true
        } catch (_: Exception) {
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
