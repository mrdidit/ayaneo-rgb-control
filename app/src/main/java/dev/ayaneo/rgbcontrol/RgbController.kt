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
import java.io.FileOutputStream
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

data class ApplyResult(val success: Boolean, val message: String)
private data class RootResult(val exitCode: Int, val output: String)

data class DeviceProfile(
    val name: String,
    val deviceNames: Set<String>,
    val uartPath: String?,
    val protocolSelector: Int?,
    val supportsRgbCycle: Boolean,
    val supportsReactive: Boolean,
    val usesKr02Protocol: Boolean = false,
) {
    val supportsDirectUart: Boolean
        get() = uartPath != null && (protocolSelector != null || usesKr02Protocol)
}

data class SavedRgbSettings(
    val red: Int = 255,
    val green: Int = 255,
    val blue: Int = 43,
    val brightness: Int = 87,
    val mode: Int = 6,
    val colorCorrection: Boolean = true,
    val mixedGreenPercent: Int = 20,
    val mixedBluePercent: Int = 20,
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
            DeviceProfile(
                name = "Pocket FIT Elite",
                deviceNames = setOf("PocketFITElite"),
                uartPath = "/dev/ttyHS1",
                protocolSelector = null,
                supportsRgbCycle = false,
                supportsReactive = false,
                usesKr02Protocol = true,
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
    private var rootProcess: Process? = null
    private var rootInput: BufferedWriter? = null
    private var rootOutput: BufferedReader? = null
    private val rootCommandId = AtomicLong()
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
        mixedGreenPercent = preferences.getInt("mixed_green_percent", 20),
        mixedBluePercent = preferences.getInt("mixed_blue_percent", 20),
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

    fun saveGlobalCalibration(greenPercent: Int, bluePercent: Int) {
        preferences.edit()
            .putInt("mixed_green_percent", greenPercent.coerceIn(0, 100))
            .putInt("mixed_blue_percent", bluePercent.coerceIn(0, 100))
            .apply()
    }

    fun loadCalibrationOverride(key: String): Pair<Int, Int>? {
        val greenKey = "calibration_${key}_green"
        val blueKey = "calibration_${key}_blue"
        if (!preferences.contains(greenKey) || !preferences.contains(blueKey)) return null
        return preferences.getInt(greenKey, 20) to preferences.getInt(blueKey, 20)
    }

    fun saveCalibrationOverride(key: String, greenPercent: Int, bluePercent: Int) {
        preferences.edit()
            .putInt("calibration_${key}_green", greenPercent.coerceIn(0, 100))
            .putInt("calibration_${key}_blue", bluePercent.coerceIn(0, 100))
            .apply()
    }

    fun clearCalibrationOverride(key: String) {
        preferences.edit()
            .remove("calibration_${key}_green")
            .remove("calibration_${key}_blue")
            .apply()
    }

    fun correctedRgb(
        red: Int,
        green: Int,
        blue: Int,
        colorCorrection: Boolean,
        mixedGreenPercent: Int,
        mixedBluePercent: Int,
    ): IntArray {
        val r = red.coerceIn(0, 255)
        val g = green.coerceIn(0, 255)
        val b = blue.coerceIn(0, 255)
        val correctedGreen = if (colorCorrection && r > 0 && g > 0) {
            (g * mixedGreenPercent.coerceIn(0, 100) / 100f).toInt().coerceAtLeast(1)
        } else g
        val correctedBlue = if (colorCorrection && r > 0 && b > 0) {
            (b * mixedBluePercent.coerceIn(0, 100) / 100f).toInt().coerceAtLeast(1)
        } else b
        return intArrayOf(r, correctedGreen, correctedBlue)
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
        closeRootShell()
    }

    @Synchronized
    private fun closeRootShell() {
        runCatching { rootInput?.apply { write("exit\n"); flush() } }
        runCatching { rootProcess?.destroy() }
        rootInput = null
        rootOutput = null
        rootProcess = null
    }

    @Synchronized
    private fun runAsRoot(command: String): RootResult {
        if (rootProcess?.isAlive != true) {
            closeRootShell()
            val process = ProcessBuilder("su").redirectErrorStream(true).start()
            rootProcess = process
            rootInput = BufferedWriter(OutputStreamWriter(process.outputStream))
            rootOutput = BufferedReader(InputStreamReader(process.inputStream))
        }
        val input = rootInput ?: error("Root shell input is unavailable")
        val output = rootOutput ?: error("Root shell output is unavailable")
        val marker = "__AYARGB_ROOT_${rootCommandId.incrementAndGet()}__"
        input.write(command)
        input.write("\naya_rgb_rc=\$?\nprintf '\\n$marker:%s\\n' \"\$aya_rgb_rc\"\n")
        input.flush()

        val lines = mutableListOf<String>()
        while (true) {
            val line = output.readLine() ?: error("Root shell closed unexpectedly")
            if (line.startsWith("$marker:")) {
                return RootResult(
                    exitCode = line.substringAfter(':').toIntOrNull() ?: 1,
                    output = lines.joinToString("\n").trim(),
                )
            }
            lines += line
        }
    }

    suspend fun apply(
        mode: Int,
        red: Int,
        green: Int,
        blue: Int,
        brightness: Int,
        colorCorrection: Boolean,
        mixedGreenPercent: Int,
        mixedBluePercent: Int,
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
            fun corrected(color: Int): IntArray {
                return correctedRgb(
                    red = color shr 16 and 255,
                    green = color shr 8 and 255,
                    blue = color and 255,
                    colorCorrection = colorCorrection,
                    mixedGreenPercent = mixedGreenPercent,
                    mixedBluePercent = mixedBluePercent,
                )
            }
            val (correctedRed, correctedGreen, correctedBlue) =
                corrected((red shl 16) or (green shl 8) or blue)
            val colorFile = when (mode) {
                1 -> "aya_rgb_breath_single_mode_color.conf"
                3 -> null
                6 -> "aya_rgb_single_mode_color.conf"
                else -> "aya_rgb_default_mode_color.conf"
            }
            val brightnessFile = when (mode) {
                1 -> "aya_rgb_breath_single_mode_bright.conf"
                3 -> null
                6 -> "aya_rgb_single_mode_bright.conf"
                else -> "aya_rgb_default_mode_bright.conf"
            }
            val values = mutableMapOf(
                "aya_rgb_is_open.conf" to "true",
            )
            colorFile?.let {
                values[it] = "$correctedRed,$correctedGreen,$correctedBlue"
            }
            brightnessFile?.let {
                values[it] = brightness.coerceIn(1, 100).toString()
            }
            // Write the mode last. GameWindow's file observer can immediately restart
            // the current effect when a colour or brightness file changes.
            values["aya_rgb_mode.conf"] = mode.toString()
            if (mode == 7 && deviceProfile.supportsReactive) {
                val (idleRed, idleGreen, idleBlue) = corrected(reactiveIdleColor)
                val (highlightRed, highlightGreen, highlightBlue) =
                    corrected(reactiveHighlightColor)
                colorFile?.let { values.remove(it) }
                brightnessFile?.let { values.remove(it) }
                values["aya_rgb_follow_mode_back_color.conf"] =
                    "$idleRed,$idleGreen,$idleBlue"
                values["aya_rgb_follow_mode_front_color.conf"] =
                    "$highlightRed,$highlightGreen,$highlightBlue"
                values["aya_rgb_follow_mode_bright.conf"] =
                    brightness.coerceIn(1, 100).toString()
            }

            val isKr02DirectStatic = mode == 6 && deviceProfile.usesKr02Protocol
            val directStaticCommand = if (mode == 6) {
                buildDirectStaticCommand(
                    red = correctedRed.coerceIn(0, 255),
                    green = correctedGreen.coerceIn(0, 255),
                    blue = correctedBlue.coerceIn(0, 255),
                    brightness = brightness.coerceIn(1, 100),
                )
            } else null
            val command = if (isKr02DirectStatic) {
                // KR02 GameWindow generates its own software light commands. Stop that
                // writer before issuing our direct Static packet to avoid a UART race.
                sendRgbMessage("com_set_rgb_is_open:false")
                // Its shutdown coroutine sleeps for 100 ms before completing the serial
                // cleanup. Leave enough margin so its off packet cannot overtake Static.
                Thread.sleep(350)
                directStaticCommand ?: return@withContext ApplyResult(
                    false,
                    "Pocket FIT Elite Static command is unavailable",
                )
            } else {
                var configCommand =
                    "mkdir -p '$CONFIG_DIR' && chown media_rw:media_rw '$CONFIG_DIR' && " +
                        "chmod 0775 '$CONFIG_DIR' && "
                configCommand += values.entries.joinToString(" && ") { (file, value) ->
                    "printf '%s' '$value' > '$CONFIG_DIR/$file'"
                }
                directStaticCommand?.let { configCommand += " && $it" }
                configCommand
            }
            val rootResult = runCatching { runAsRoot(command) }.getOrElse {
                return@withContext ApplyResult(false, "Could not start root shell: ${it.message}")
            }
            if (rootResult.exitCode != 0) {
                val output = rootResult.output
                logEvent("Apply mode=$mode failed: ${output.ifBlank { "root write failed" }}")
                return@withContext ApplyResult(false, output.ifBlank { "Root write failed" })
            }

            val sent = if (isKr02DirectStatic) {
                true
            } else {
                // Mode 2-5 changes are not dispatched by GameWindow's file observer.
                // Let it consume the new mode before asking its open path to start it.
                if (deviceProfile.usesKr02Protocol) Thread.sleep(200)
                sendApplyMessage()
            }
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
        if (deviceProfile.usesKr02Protocol) {
            val stockBrightness = brightness.coerceIn(1, 100) * 255 / 100
            fun kr02Channel(channel: Int): Int {
                val scaled = (channel.coerceIn(0, 255) * stockBrightness * 0.6f / 255f)
                    .toInt()
                    .coerceIn(0, 99)
                return (33.0 + scaled * (120.0 / 99.0)).roundToInt()
            }
            val packet = intArrayOf(
                0xF7, 0x01,
                kr02Channel(red),
                kr02Channel(green),
                kr02Channel(blue),
                0x00, 0x00, 0x00, 0x00,
                0x00, 0xED,
            )
            packet[9] = (1..7).sumOf { packet[it] } and 0xFF
            val escaped = packet.joinToString(separator = "") { "\\%03o".format(it) }
            return "printf '$escaped' > '$uartPath'"
        }
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
        if (!enabled) sendRgbMessage("com_set_rgb_is_open:false")
        var command =
            "mkdir -p '$CONFIG_DIR' && chown media_rw:media_rw '$CONFIG_DIR' && " +
                "chmod 0775 '$CONFIG_DIR' && " +
                "printf '%s' '$value' > '$CONFIG_DIR/aya_rgb_is_open.conf'"
        if (!enabled && deviceProfile.usesKr02Protocol) {
            buildKr02ShutdownCommand()?.let { command += " && $it" }
        }
        val rootResult = runCatching {
            runAsRoot(command)
        }.getOrElse {
            return@withContext ApplyResult(false, "Could not start root shell: ${it.message}")
        }
        if (rootResult.exitCode != 0) {
            val output = rootResult.output
            logEvent("LED enabled=$enabled failed: ${output.ifBlank { "root write failed" }}")
            return@withContext ApplyResult(false, output.ifBlank { "LED state write failed" })
        }
        val sent = if (enabled) sendRgbMessage("com_set_rgb_is_open:true") else true
        logEvent("LED enabled=$enabled ipc=$sent")
        ApplyResult(sent, if (sent) "LEDs ${if (enabled) "on" else "off"}" else "Saved LED state; IPC unavailable")
    }

    private fun buildKr02ShutdownCommand(): String? {
        val uartPath = deviceProfile.uartPath ?: return null
        val packet = intArrayOf(
            0xF7, 0x01,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x01, 0xED,
        )
        val escaped = packet.joinToString(separator = "") { "\\%03o".format(it) }
        return "printf '$escaped' > '$uartPath'"
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
            val result = runAsRoot(rootCommand)
            "exit=${result.exitCode}\n${result.output.ifBlank { "(no output)" }}"
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
            protocol=${if (deviceProfile.usesKr02Protocol) "KR02-11-byte" else "selector-" +
                (deviceProfile.protocolSelector?.let { "0x%02X".format(it) } ?: "unknown")}
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
        val report = collectDiagnostics()
        val testNotes =
            """

                Unknown AYANEO RGB device test
                Model: ${Build.MODEL}
                Device: ${Build.DEVICE}

                In AYANEO's stock RGB controls:
                1. Select Static.
                2. Select a clear green and record its appearance.
                3. Select a clear blue and record its appearance.

                Green appeared as:

                Blue appeared as:

                Other available stock modes:

                Additional notes:
            """.trimIndent()
        reportFile.writeText("$report\n\n$testNotes\n")

        val exportDir = "/data/media/0/AYARGB"
        val reportTarget = "$exportDir/${reportFile.name}"
        val command =
            "mkdir -p '$exportDir' && " +
                "cp '${reportFile.absolutePath}' '$reportTarget' && " +
                "chown media_rw:media_rw '$exportDir' '$reportTarget' && " +
                "chmod 775 '$exportDir' && chmod 664 '$reportTarget'"
        val rootResult = runCatching {
            runAsRoot(command)
        }.getOrElse {
            return@withContext ApplyResult(false, "Could not start root export: ${it.message}")
        }
        if (rootResult.exitCode != 0) {
            return@withContext ApplyResult(
                false,
                rootResult.output.ifBlank { "Diagnostics export failed" },
            )
        }
        logEvent("Exported diagnostics report to /storage/emulated/0/AYARGB")
        ApplyResult(
            true,
            "Exported ${reportFile.name} to /storage/emulated/0/AYARGB",
        )
    }

    suspend fun exportGameWindowResearchBundle(): ApplyResult = withContext(Dispatchers.IO) {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val safeDevice = Build.DEVICE.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val bundleFile = File(context.cacheDir, "$timestamp-$safeDevice-gamewindow-research.zip")
        val pathProcess = runCatching {
            ProcessBuilder("su", "-c", "pm path '$GAMEWINDOW_PACKAGE'")
                .redirectErrorStream(true)
                .start()
        }.getOrElse {
            return@withContext ApplyResult(false, "Could not query GameWindow: ${it.message}")
        }
        val pathOutput = pathProcess.inputStream.bufferedReader().readText().trim()
        if (pathProcess.waitFor() != 0) {
            return@withContext ApplyResult(false, pathOutput.ifBlank { "GameWindow was not found" })
        }
        val apkPaths = pathOutput.lineSequence()
            .mapNotNull { line -> line.removePrefix("package:").takeIf { line.startsWith("package:") } }
            .filter { it.isNotBlank() }
            .toList()
        if (apkPaths.isEmpty()) {
            return@withContext ApplyResult(false, "GameWindow APK paths were not found")
        }

        val buildResult = runCatching {
            ZipOutputStream(FileOutputStream(bundleFile)).use { zip ->
                zip.putNextEntry(ZipEntry("diagnostics.txt"))
                zip.write(collectDiagnostics().toByteArray())
                zip.closeEntry()
                apkPaths.forEachIndexed { index, apkPath ->
                    val entryName = if (index == 0) "gamewindow/base.apk" else {
                        "gamewindow/split-${index}.apk"
                    }
                    zip.putNextEntry(ZipEntry(entryName))
                    val copyProcess = ProcessBuilder("su", "-c", "cat '$apkPath'")
                        .redirectErrorStream(false)
                        .start()
                    copyProcess.inputStream.use { it.copyTo(zip) }
                    val errorOutput = copyProcess.errorStream.bufferedReader().readText().trim()
                    if (copyProcess.waitFor() != 0) {
                        error(
                            "Could not read ${File(apkPath).name}: " +
                                errorOutput.ifBlank { "root copy failed" },
                        )
                    }
                    zip.closeEntry()
                }
            }
        }
        if (buildResult.isFailure) {
            bundleFile.delete()
            return@withContext ApplyResult(
                false,
                "Research bundle failed: ${buildResult.exceptionOrNull()?.message}",
            )
        }

        val exportDir = "/data/media/0/AYARGB"
        val target = "$exportDir/${bundleFile.name}"
        val exportProcess = runCatching {
            ProcessBuilder(
                "su",
                "-c",
                "mkdir -p '$exportDir' && cp '${bundleFile.absolutePath}' '$target' && " +
                    "chown media_rw:media_rw '$exportDir' '$target' && " +
                    "chmod 775 '$exportDir' && chmod 664 '$target'",
            ).redirectErrorStream(true).start()
        }.getOrElse {
            return@withContext ApplyResult(false, "Could not start root export: ${it.message}")
        }
        val exportOutput = exportProcess.inputStream.bufferedReader().readText().trim()
        if (exportProcess.waitFor() != 0) {
            return@withContext ApplyResult(
                false,
                exportOutput.ifBlank { "Research bundle export failed" },
            )
        }
        logEvent("Exported GameWindow research bundle to /storage/emulated/0/AYARGB")
        ApplyResult(true, "Exported ${bundleFile.name} to /storage/emulated/0/AYARGB")
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
