package dev.ayaneo.rgbcontrol

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.File

data class ApplyResult(val success: Boolean, val message: String)
data class DeviceProfile(
    val name: String,
    val deviceNames: Set<String>,
    val uartPath: String?,
    val protocolSelector: Int?,
    val supportsRgbCycle: Boolean,
    val supportsReactive: Boolean,
) {
    val supportsDirectUart: Boolean
        get() = uartPath != null && protocolSelector != null
}

data class SavedRgbSettings(
    val red: Int = 255,
    val green: Int = 255,
    val blue: Int = 43,
    val brightness: Int = 87,
    val mode: Int = 6,
    val colorCorrection: Boolean = true,
    val livePreview: Boolean = true,
    val ledEnabled: Boolean = true,
    val reactiveIdleColor: Int = 0xFF0000,
    val reactiveHighlightColor: Int = 0xFFC000,
)

class RgbController(private val context: Context) {
    companion object {
        private const val GAMEWINDOW_PACKAGE = "com.ayaneo.gamewindow"
        private const val GAMEWINDOW_SERVICE =
            "com.ayaneo.gamewindow.utils.aidl.AyaAidlService"
        private const val AIDL_DESCRIPTOR =
            "com.ayaneo.gamewindow.AyaAidlInterface"
        private const val CONFIG_DIR = "/data/media/0/.aya"

        private val DEVICE_PROFILES = listOf(
            DeviceProfile(
                name = "Pocket S2",
                deviceNames = setOf("PocketS2", "PocketS2Pro"),
                uartPath = "/dev/ttyHS5",
                protocolSelector = 0x08,
                supportsRgbCycle = false,
                supportsReactive = false,
            ),
            DeviceProfile(
                name = "Pocket EVO",
                deviceNames = setOf("PocketEVO"),
                uartPath = "/dev/ttyHS4",
                protocolSelector = 0x02,
                supportsRgbCycle = true,
                supportsReactive = true,
            ),
        )
    }

    val deviceProfile: DeviceProfile = DEVICE_PROFILES.firstOrNull {
        Build.DEVICE in it.deviceNames
    } ?: DeviceProfile(
        name = Build.MODEL.ifBlank { "Unknown AYANEO device" },
        deviceNames = setOf(Build.DEVICE),
        uartPath = null,
        protocolSelector = null,
        supportsRgbCycle = false,
        supportsReactive = false,
    )

    @Volatile
    private var binder: IBinder? = null
    private val eventLog = ArrayDeque<String>()
    private val preferences =
        context.getSharedPreferences("rgb_settings", Context.MODE_PRIVATE)

    init {
        logEvent(
            "Detected ${Build.MANUFACTURER} ${Build.MODEL} " +
                "(device=${Build.DEVICE}, profile=${deviceProfile.name}, " +
                "directUart=${deviceProfile.supportsDirectUart})",
        )
    }

    private fun logEvent(message: String) {
        synchronized(eventLog) {
            if (eventLog.size >= 40) eventLog.removeFirst()
            eventLog.addLast(
                "${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())} $message",
            )
        }
    }

    fun loadSettings(): SavedRgbSettings = SavedRgbSettings(
        red = preferences.getInt("red", 255),
        green = preferences.getInt("green", 255),
        blue = preferences.getInt("blue", 43),
        brightness = preferences.getInt("brightness", 87),
        mode = preferences.getInt("mode", 6).let { if (it == 100) 3 else it },
        colorCorrection = preferences.getBoolean("color_correction", true),
        livePreview = preferences.getBoolean("live_preview", true),
        ledEnabled = preferences.getBoolean("led_enabled", true),
        reactiveIdleColor = preferences.getInt("reactive_idle_color", 0xFF0000),
        reactiveHighlightColor = preferences.getInt("reactive_highlight_color", 0xFFC000),
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
        reactiveIdleColor: Int,
        reactiveHighlightColor: Int,
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
                    .putInt("reactive_idle_color", reactiveIdleColor)
                    .putInt("reactive_highlight_color", reactiveHighlightColor)
                    .apply()
            }
            fun corrected(color: Int): Triple<Int, Int, Int> {
                val r = color shr 16 and 255
                val g = color shr 8 and 255
                val b = color and 255
                val correctedGreen = if (colorCorrection && r > 0 && g > 0) {
                    (g * 0.20f).toInt().coerceAtLeast(1)
                } else g
                val correctedBlue = if (colorCorrection && r > 0 && b > 0) {
                    (b * 0.35f).toInt().coerceAtLeast(1)
                } else b
                return Triple(r, correctedGreen, correctedBlue)
            }
            val (correctedRed, correctedGreen, correctedBlue) =
                corrected((red shl 16) or (green shl 8) or blue)
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
            val values = mutableMapOf(
                "aya_rgb_mode.conf" to mode.toString(),
                "aya_rgb_is_open.conf" to "true",
                colorFile to "$correctedRed,$correctedGreen,$correctedBlue",
                brightnessFile to brightness.coerceIn(1, 100).toString(),
            )
            if (mode == 7 && deviceProfile.supportsReactive) {
                val (idleRed, idleGreen, idleBlue) = corrected(reactiveIdleColor)
                val (highlightRed, highlightGreen, highlightBlue) =
                    corrected(reactiveHighlightColor)
                values.remove(colorFile)
                values.remove(brightnessFile)
                values["aya_rgb_follow_mode_back_color.conf"] =
                    "$idleRed,$idleGreen,$idleBlue"
                values["aya_rgb_follow_mode_front_color.conf"] =
                    "$highlightRed,$highlightGreen,$highlightBlue"
                values["aya_rgb_follow_mode_bright.conf"] =
                    brightness.coerceIn(1, 100).toString()
            }

            var command = values.entries.joinToString(" && ") { (file, value) ->
                "printf '%s' '$value' > '$CONFIG_DIR/$file'"
            }
            if (mode == 6) {
                buildDirectStaticCommand(
                    red = red.coerceIn(0, 255),
                    green = correctedGreen.coerceIn(0, 255),
                    blue = correctedBlue.coerceIn(0, 255),
                    brightness = brightness.coerceIn(1, 100),
                )?.let { command += " && $it" }
            }
            val process = runCatching {
                ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            }.getOrElse {
                return@withContext ApplyResult(false, "Could not start root shell: ${it.message}")
            }
            val output = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor() != 0) {
                logEvent("Apply mode=$mode failed: ${output.ifBlank { "root write failed" }}")
                return@withContext ApplyResult(false, output.ifBlank { "Root write failed" })
            }

            val sent = sendApplyMessage()
            logEvent(
                "Apply mode=$mode rgb=$red,$green,$blue corrected=" +
                    "$correctedRed,$correctedGreen,$correctedBlue brightness=$brightness " +
                    "uart=${if (mode == 6 && deviceProfile.supportsDirectUart) deviceProfile.uartPath else "none"} " +
                    "ipc=$sent",
            )
            ApplyResult(
                sent,
                if (sent && mode == 6 && !deviceProfile.supportsDirectUart) {
                    "Saved Static settings; direct UART is disabled for this unknown device"
                } else if (sent) "Applied #${"%02X%02X%02X".format(red, green, blue)}"
                else "Saved values, but GameWindow IPC is not connected",
            )
        }

    private fun buildDirectStaticCommand(
        red: Int,
        green: Int,
        blue: Int,
        brightness: Int,
    ): String? {
        val uartPath = deviceProfile.uartPath ?: return null
        val selector = deviceProfile.protocolSelector ?: return null
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
            0x58, selector,
            0x45, 0x00,
            0x00, 0xED,
        )
        packet[25] = (1..24).sumOf { packet[it] } and 0xFF
        val escaped = packet.joinToString(separator = "") { "\\%03o".format(it) }
        return "printf '$escaped' > '$uartPath'"
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
            logEvent("LED enabled=$enabled failed: ${output.ifBlank { "root write failed" }}")
            return@withContext ApplyResult(false, output.ifBlank { "LED state write failed" })
        }
        val sent = sendRgbMessage("com_set_rgb_is_open:$value")
        logEvent("LED enabled=$enabled ipc=$sent")
        ApplyResult(sent, if (sent) "LEDs ${if (enabled) "on" else "off"}" else "Saved LED state; IPC unavailable")
    }

    suspend fun collectDiagnostics(): String = withContext(Dispatchers.IO) {
        val packageManager = context.packageManager
        val appVersion = runCatching {
            packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        val gameWindowVersion = runCatching {
            packageManager.getPackageInfo(GAMEWINDOW_PACKAGE, 0).versionName
        }.getOrNull() ?: "not installed"
        val recentEvents = synchronized(eventLog) {
            if (eventLog.isEmpty()) "(none)" else eventLog.joinToString("\n")
        }
        val rootCommand = """
            echo "root_id=$(id)"
            echo "gamewindow_pid=$(pidof $GAMEWINDOW_PACKAGE)"
            echo "--- candidate UART nodes ---"
            ls -lZ /dev/ttyHS* 2>&1
            echo "--- GameWindow UART descriptors ---"
            ls -l /proc/$(pidof $GAMEWINDOW_PACKAGE)/fd 2>&1 | grep '/dev/ttyHS' || true
            echo "--- RGB configuration files ---"
            ls -lZ $CONFIG_DIR/aya_rgb*.conf 2>&1
        """.trimIndent()
        val rootProbe = runCatching {
            val process = ProcessBuilder("su", "-c", rootCommand)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            "exit=$exitCode\n${output.ifBlank { "(no output)" }}"
        }.getOrElse { "unavailable: ${it.javaClass.simpleName}: ${it.message}" }

        """
            AYANEO RGB Control diagnostics
            generated=${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}
            app_version=$appVersion
            package=${context.packageName}

            manufacturer=${Build.MANUFACTURER}
            brand=${Build.BRAND}
            model=${Build.MODEL}
            device=${Build.DEVICE}
            product=${Build.PRODUCT}
            board=${Build.BOARD}
            hardware=${Build.HARDWARE}
            android=${Build.VERSION.RELEASE}
            sdk=${Build.VERSION.SDK_INT}

            selected_profile=${deviceProfile.name}
            uart_path=${deviceProfile.uartPath ?: "disabled"}
            protocol_selector=${deviceProfile.protocolSelector?.let { "0x%02X".format(it) } ?: "unknown"}
            direct_uart_enabled=${deviceProfile.supportsDirectUart}
            rgb_cycle_advertised=${deviceProfile.supportsRgbCycle}
            reactive_advertised=${deviceProfile.supportsReactive}
            gamewindow_version=$gameWindowVersion
            gamewindow_ipc_connected=${binder != null}

            --- recent app events ---
            $recentEvents

            --- read-only root probe ---
            $rootProbe

            Note: this report does not write to a UART and does not capture packet bytes.
        """.trimIndent()
    }

    suspend fun exportDiagnosticsBundle(): ApplyResult = withContext(Dispatchers.IO) {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val safeDevice = Build.DEVICE.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val baseName = "$timestamp-$safeDevice"
        val reportFile = File(context.cacheDir, "$baseName-diagnostics.txt")
        val notesFile = File(context.cacheDir, "$baseName-test-notes.txt")
        reportFile.writeText(collectDiagnostics())
        notesFile.writeText(
            """
                Unknown AYANEO RGB device test
                Model: ${Build.MODEL}
                Device: ${Build.DEVICE}

                In AYANEO RGB Control safe mode:
                1. Select Static.
                2. Select the Green preset (#00FF00), tap Apply, and record its appearance.
                3. Select the Blue preset (#0000FF), tap Apply, and record its appearance.
                4. Direct UART remains disabled throughout this test.

                #00FF00 appeared as:

                #0000FF appeared as:

                Other available stock modes:

                Additional notes:
            """.trimIndent(),
        )

        val exportDir = "/data/media/0/AYARGB"
        val reportTarget = "$exportDir/${reportFile.name}"
        val notesTarget = "$exportDir/${notesFile.name}"
        val command =
            "mkdir -p '$exportDir' && " +
                "cp '${reportFile.absolutePath}' '$reportTarget' && " +
                "cp '${notesFile.absolutePath}' '$notesTarget' && " +
                "chown media_rw:media_rw '$exportDir' '$reportTarget' '$notesTarget' && " +
                "chmod 775 '$exportDir' && chmod 664 '$reportTarget' '$notesTarget'"
        val process = runCatching {
            ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        }.getOrElse {
            return@withContext ApplyResult(false, "Could not start root export: ${it.message}")
        }
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() != 0) {
            return@withContext ApplyResult(
                false,
                output.ifBlank { "Diagnostics export failed" },
            )
        }
        logEvent("Exported diagnostics bundle to /storage/emulated/0/AYARGB")
        ApplyResult(
            true,
            "Exported ${reportFile.name} and ${notesFile.name} to /storage/emulated/0/AYARGB",
        )
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
