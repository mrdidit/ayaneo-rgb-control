package dev.ayaneo.rgbcontrol

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
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
    val themeColor: Int = 0x8BE9FD,
)

class RgbController(private val context: Context) {
    companion object {
        private const val GAMEWINDOW_PACKAGE = "com.ayaneo.gamewindow"
        private const val GAMEWINDOW_SERVICE =
            "com.ayaneo.gamewindow.utils.aidl.AyaAidlService"
        private const val AIDL_DESCRIPTOR =
            "com.ayaneo.gamewindow.AyaAidlInterface"
        private const val CONFIG_DIR = "/data/media/0/.aya"
        private const val POCKET_EVO_VALIDATED_FIRMWARE_MARKER = "23"
        private const val POCKET_EVO_VALIDATED_GAMEWINDOW_VERSION = "1.5.66"
        private const val POCKET_EVO_VALIDATED_GAMEWINDOW_CODE = 186L
        private const val POCKET_EVO_VALIDATED_INPUT_PATH = "/dev/input/event6"
        private const val POCKET_EVO_MAGISK_MODULE_DIR =
            "/data/adb/modules/ayaneo_rgb_uart"
        private const val POCKET_EVO_UART_SELINUX_CONTEXT =
            "u:object_r:ayaneo_rgb_device:s0"
        private const val POCKET_EVO_CONTROL_STATE_KEY = "pocket_evo_control_state"
        private const val POCKET_EVO_STATE_STOCK = "stock"
        private const val POCKET_EVO_STATE_STOPPING = "stopping"
        private const val POCKET_EVO_STATE_DIRECT = "direct"
        private const val POCKET_EVO_STATE_RESTORING = "restoring"

        // A single lock is shared by every controller instance. This matters during
        // Activity recreation: an old non-cancellable transaction must finish before
        // a newly-created UI can issue another ownership-changing operation.
        private val CONTROLLER_TRANSACTION_MUTEX = Mutex()

        @Volatile
        private var sharedInstance: RgbController? = null

        fun shared(context: Context): RgbController =
            sharedInstance ?: synchronized(this) {
                sharedInstance ?: RgbController(context.applicationContext).also {
                    sharedInstance = it
                }
            }

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

    val supportsPocketEvoAdvancedRgb: Boolean
        get() = Build.DEVICE == "PocketEVO" && deviceProfile.name == "Pocket EVO"

    @Volatile
    private var binder: IBinder? = null
    @Volatile
    private var serviceBindingRequested = false
    private val eventLog = ArrayDeque<String>()
    private val preferences =
        context.getSharedPreferences("rgb_settings", Context.MODE_PRIVATE)

    val isPocketEvoDirectControlActive: Boolean
        get() = pocketEvoControlState() == POCKET_EVO_STATE_DIRECT

    val hasInterruptedPocketEvoTransaction: Boolean
        get() = pocketEvoControlState() in setOf(
            POCKET_EVO_STATE_STOPPING,
            POCKET_EVO_STATE_RESTORING,
        )

    init {
        logEvent(
            "Detected ${Build.MANUFACTURER} ${Build.MODEL} " +
                "(device=${Build.DEVICE}, profile=${deviceProfile.name}, " +
                "directUart=${deviceProfile.supportsDirectUart})",
        )
    }

    private fun pocketEvoControlState(): String =
        preferences.getString(POCKET_EVO_CONTROL_STATE_KEY, POCKET_EVO_STATE_STOCK)
            ?: POCKET_EVO_STATE_STOCK

    private fun setPocketEvoControlState(state: String) {
        // Ownership is safety-critical across process death, so commit it before
        // proceeding instead of using the asynchronous SharedPreferences apply().
        check(preferences.edit().putString(POCKET_EVO_CONTROL_STATE_KEY, state).commit()) {
            "Could not persist Pocket EVO RGB ownership state"
        }
    }

    fun shouldBindGameWindow(): Boolean =
        !supportsPocketEvoAdvancedRgb || pocketEvoControlState() == POCKET_EVO_STATE_STOCK

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
        themeColor = preferences.getInt("theme_color", 0x8BE9FD),
    )

    fun saveThemeColor(color: Int) {
        preferences.edit().putInt("theme_color", color and 0xFFFFFF).apply()
    }

    fun saveLivePreview(enabled: Boolean) {
        preferences.edit().putBoolean("live_preview", enabled).apply()
    }

    fun saveMode(mode: Int) {
        preferences.edit().apply {
            putInt("mode", mode)
            if (mode !in setOf(8, 9)) putInt("last_stock_mode", mode)
        }.apply()
    }

    fun loadLastStockMode(): Int =
        preferences.getInt("last_stock_mode", 6).takeIf { it in setOf(1, 2, 3, 6, 7) }
            ?: 6

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

    fun loadPocketEvoStickColors(): List<Int> {
        val fallback =
            (preferences.getInt("red", 255).coerceIn(0, 255) shl 16) or
                (preferences.getInt("green", 255).coerceIn(0, 255) shl 8) or
                preferences.getInt("blue", 43).coerceIn(0, 255)
        return List(2) { index ->
            preferences.getInt("pocket_evo_stick_color_$index", fallback) and 0xFFFFFF
        }
    }

    fun loadPocketEvoStickBrightness(): List<Int> {
        val fallback = preferences.getInt("brightness", 100).coerceIn(1, 100)
        return List(2) { index ->
            preferences.getInt("pocket_evo_stick_brightness_$index", fallback)
                .coerceIn(1, 100)
        }
    }

    fun savePocketEvoStick(index: Int, color: Int, brightness: Int) {
        if (index !in 0..1) return
        preferences.edit()
            .putInt("pocket_evo_stick_color_$index", color and 0xFFFFFF)
            .putInt("pocket_evo_stick_brightness_$index", brightness.coerceIn(1, 100))
            .apply()
    }

    fun loadPocketEvoZoneColors(): List<Int> {
        val fallback =
            (preferences.getInt("red", 255).coerceIn(0, 255) shl 16) or
                (preferences.getInt("green", 255).coerceIn(0, 255) shl 8) or
                preferences.getInt("blue", 43).coerceIn(0, 255)
        return List(8) { index ->
            preferences.getInt("pocket_evo_zone_color_$index", fallback) and 0xFFFFFF
        }
    }

    fun loadPocketEvoZoneBrightness(): List<Int> {
        val fallback = preferences.getInt("brightness", 100).coerceIn(1, 100)
        return List(8) { index ->
            preferences.getInt("pocket_evo_zone_brightness_$index", fallback)
                .coerceIn(1, 100)
        }
    }

    fun savePocketEvoZone(index: Int, color: Int, brightness: Int) {
        if (index !in 0..7) return
        preferences.edit()
            .putInt("pocket_evo_zone_color_$index", color and 0xFFFFFF)
            .putInt("pocket_evo_zone_brightness_$index", brightness.coerceIn(1, 100))
            .apply()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
        }

        override fun onBindingDied(name: ComponentName?) {
            detachGameWindowBinding()
        }

        override fun onNullBinding(name: ComponentName?) {
            detachGameWindowBinding()
        }
    }

    fun bind(): Boolean {
        if (!shouldBindGameWindow()) return false
        return bindGameWindowService(createIfNeeded = true)
    }

    @Synchronized
    private fun bindGameWindowService(createIfNeeded: Boolean = false): Boolean {
        if (serviceBindingRequested) return true
        val intent = Intent().setClassName(GAMEWINDOW_PACKAGE, GAMEWINDOW_SERVICE)
        val bound = runCatching {
            // The public entry point gates direct ownership. Recovery explicitly
            // starts GameWindow first and binds with flags=0 to avoid a second owner.
            context.bindService(
                intent,
                connection,
                if (createIfNeeded) Context.BIND_AUTO_CREATE else 0,
            )
        }.getOrDefault(false)
        serviceBindingRequested = bound
        return bound
    }

    fun unbind() {
        detachGameWindowBinding()
    }

    @Synchronized
    private fun detachGameWindowBinding() {
        if (serviceBindingRequested) runCatching { context.unbindService(connection) }
        serviceBindingRequested = false
        binder = null
    }

    private suspend fun <T> serializedControllerOperation(block: () -> T): T =
        withContext(Dispatchers.IO) {
            CONTROLLER_TRANSACTION_MUTEX.lock()
            try {
                // Once ownership begins changing, Activity destruction/cancellation
                // must not strand the controller with GameWindow force-stopped.
                withContext(NonCancellable) { block() }
            } finally {
                CONTROLLER_TRANSACTION_MUTEX.unlock()
            }
        }

    @Synchronized
    private fun runAsRoot(command: String): RootResult {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = StringBuilder()
        var readFailure: Throwable? = null
        val drainThread = Thread({
            runCatching {
                process.inputStream.reader().use { reader ->
                    val buffer = CharArray(4_096)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        output.append(buffer, 0, count)
                    }
                }
            }.onFailure { readFailure = it }
        }, "ayargb-root-output").apply {
            isDaemon = true
            start()
        }
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
            drainThread.join(2_000)
            return RootResult(124, "Root command timed out")
        }
        drainThread.join(2_000)
        readFailure?.let { throw it }
        if (drainThread.isAlive) {
            return RootResult(125, "Root command output did not close")
        }
        return RootResult(
            exitCode = process.exitValue(),
            output = output.toString().trim(),
        )
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
        serializedControllerOperation {
            if (
                supportsPocketEvoAdvancedRgb &&
                pocketEvoControlState() != POCKET_EVO_STATE_STOCK
            ) {
                val restored = restorePocketEvoGameWindowLocked()
                if (!restored.success) return@serializedControllerOperation restored
            }
            if (persist) {
                preferences.edit()
                    .putInt("red", red)
                    .putInt("green", green)
                    .putInt("blue", blue)
                    .putInt("brightness", brightness)
                    .putInt("mode", mode)
                    .putInt("last_stock_mode", mode)
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

            val isKr02DirectMode =
                deviceProfile.usesKr02Protocol && mode in setOf(1, 3, 6)
            val directModeCommand = if (
                isKr02DirectMode ||
                (mode == 6 && !supportsPocketEvoAdvancedRgb)
            ) {
                buildDirectModeCommand(
                    mode = mode,
                    red = correctedRed.coerceIn(0, 255),
                    green = correctedGreen.coerceIn(0, 255),
                    blue = correctedBlue.coerceIn(0, 255),
                    brightness = brightness.coerceIn(1, 100),
                )
            } else null
            val command = if (isKr02DirectMode) {
                // Stop GameWindow before issuing the stock KR02 MCU command so its
                // cached effect cannot race with or overwrite the requested mode.
                sendRgbMessage("com_set_rgb_is_open:false")
                // Its shutdown coroutine sleeps for 100 ms before serial cleanup.
                Thread.sleep(350)
                directModeCommand ?: return@serializedControllerOperation ApplyResult(
                    false,
                    "Pocket FIT Elite direct command is unavailable",
                )
            } else {
                var configCommand =
                    "mkdir -p '$CONFIG_DIR' && chown media_rw:media_rw '$CONFIG_DIR' && " +
                        "chmod 0775 '$CONFIG_DIR' && "
                configCommand += values.entries.joinToString(" && ") { (file, value) ->
                    "printf '%s' '$value' > '$CONFIG_DIR/$file'"
                }
                val writtenFiles = values.keys.joinToString(" ") { "'$CONFIG_DIR/$it'" }
                configCommand +=
                    " && chown system:system $writtenFiles && chmod 0660 $writtenFiles"
                directModeCommand?.let { configCommand += " && $it" }
                configCommand
            }
            val rootResult = runCatching { runAsRoot(command) }.getOrElse {
                return@serializedControllerOperation ApplyResult(
                    false,
                    "Could not start root shell: ${it.message}",
                )
            }
            if (rootResult.exitCode != 0) {
                val output = rootResult.output
                logEvent("Apply mode=$mode failed: ${output.ifBlank { "root write failed" }}")
                return@serializedControllerOperation ApplyResult(
                    false,
                    output.ifBlank { "Root write failed" },
                )
            }

            val sent = if (isKr02DirectMode) {
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
            if (sent && persist) {
                preferences.edit().putBoolean("led_enabled", true).apply()
            }
            ApplyResult(
                sent,
                if (sent && mode == 6 && !deviceProfile.supportsDirectUart) {
                    "Saved Static settings; direct UART is disabled for this unknown device"
                } else if (sent) "Applied #${"%02X%02X%02X".format(red, green, blue)}"
                else "Saved values, but GameWindow IPC is not connected",
            )
        }

    suspend fun applyPocketEvoPerStick(
        colors: List<Int>,
        brightness: List<Int>,
        colorCorrection: Boolean,
        mixedGreenPercent: Int,
        mixedBluePercent: Int,
    ): ApplyResult = serializedControllerOperation {
        if (!supportsPocketEvoAdvancedRgb) {
            return@serializedControllerOperation ApplyResult(
                false,
                "Per-stick RGB is Pocket EVO only",
            )
        }
        if (colors.size != 2 || brightness.size != 2) {
            return@serializedControllerOperation ApplyResult(
                false,
                "Per-stick RGB requires two colours and brightness values",
            )
        }
        preferences.edit().apply {
            colors.indices.forEach { index ->
                putInt("pocket_evo_stick_color_$index", colors[index] and 0xFFFFFF)
                putInt(
                    "pocket_evo_stick_brightness_$index",
                    brightness[index].coerceIn(1, 100),
                )
            }
            putBoolean("color_correction", colorCorrection)
            putInt("mixed_green_percent", mixedGreenPercent.coerceIn(0, 100))
            putInt("mixed_blue_percent", mixedBluePercent.coerceIn(0, 100))
        }.apply()

        val corrected = colors.map { color ->
            correctedPackedColor(
                color,
                colorCorrection,
                mixedGreenPercent,
                mixedBluePercent,
            )
        }
        val levels = brightness.map(::pocketEvoBrightnessLevel)
        val left = corrected[0]
        val right = corrected[1]

        executePocketEvoDirectTransaction transaction@ {
            // A broadcast Static frame reliably leaves segmented mode. The targeted
            // frames that follow retain separate left and right driver state.
            val frames = listOf(
                PocketEvoRgbProtocol.buildStaticFrame(
                    red = left shr 16 and 0xFF,
                    green = left shr 8 and 0xFF,
                    blue = left and 0xFF,
                    brightness = levels[0],
                    ring = PocketEvoRgbProtocol.Ring.BROADCAST,
                ),
                PocketEvoRgbProtocol.buildStaticFrame(
                    red = left shr 16 and 0xFF,
                    green = left shr 8 and 0xFF,
                    blue = left and 0xFF,
                    brightness = levels[0],
                    ring = PocketEvoRgbProtocol.Ring.LEFT,
                ),
                PocketEvoRgbProtocol.buildStaticFrame(
                    red = right shr 16 and 0xFF,
                    green = right shr 8 and 0xFF,
                    blue = right and 0xFF,
                    brightness = levels[1],
                    ring = PocketEvoRgbProtocol.Ring.RIGHT,
                ),
            )
            frames.forEach { frame ->
                writePocketEvoFrameRepeated(frame)?.let { return@transaction it }
            }
            if (!preferences.edit().putBoolean("led_enabled", true).commit()) {
                return@transaction ApplyResult(false, "Could not save enabled LED state")
            }
            logEvent("Applied Pocket EVO independent per-stick Static RGB")
            ApplyResult(true, "Applied independent left/right colours · GameWindow paused")
        }
    }

    suspend fun applyPocketEvoQuadrants(
        colors: List<Int>,
        brightness: List<Int>,
        colorCorrection: Boolean,
        mixedGreenPercent: Int,
        mixedBluePercent: Int,
    ): ApplyResult = serializedControllerOperation {
        if (!supportsPocketEvoAdvancedRgb) {
            return@serializedControllerOperation ApplyResult(
                false,
                "Per-quadrant RGB is Pocket EVO only",
            )
        }
        if (colors.size != 8 || brightness.size != 8) {
            return@serializedControllerOperation ApplyResult(
                false,
                "Per-quadrant RGB requires eight colours and brightness values",
            )
        }
        preferences.edit().apply {
            colors.indices.forEach { index ->
                putInt("pocket_evo_zone_color_$index", colors[index] and 0xFFFFFF)
                putInt(
                    "pocket_evo_zone_brightness_$index",
                    brightness[index].coerceIn(1, 100),
                )
            }
            putBoolean("color_correction", colorCorrection)
            putInt("mixed_green_percent", mixedGreenPercent.coerceIn(0, 100))
            putInt("mixed_blue_percent", mixedBluePercent.coerceIn(0, 100))
        }.apply()

        val corrected = colors.map { color ->
            correctedPackedColor(
                color,
                colorCorrection,
                mixedGreenPercent,
                mixedBluePercent,
            )
        }
        val levels = brightness.map(::pocketEvoBrightnessLevel)

        executePocketEvoDirectTransaction transaction@ {
            // Controller-follow initialises all eight retained banks. Supplying the
            // same colour in both slots keeps the visual state deterministic while
            // the MCU performs its own proven per-zone setup sequence.
            val initialColor = corrected.first()
            val initializer = PocketEvoRgbProtocol.buildSameColourReactiveInitializerFrame(
                red = initialColor shr 16 and 0xFF,
                green = initialColor shr 8 and 0xFF,
                blue = initialColor and 0xFF,
                brightness = levels.first(),
            )
            writePocketEvoFrameRepeated(initializer, delayMillis = 1_000)?.let {
                return@transaction it
            }
            Thread.sleep(1_500)

            val rings = listOf(
                PocketEvoRgbProtocol.Ring.LEFT,
                PocketEvoRgbProtocol.Ring.RIGHT,
            )
            rings.forEachIndexed { ringIndex, ring ->
                PocketEvoRgbProtocol.Zone.entries.forEach { zone ->
                    val index = ringIndex * 4 + zone.index
                    val color = corrected[index]
                    val frame = PocketEvoRgbProtocol.buildPerZoneFrame(
                        ring = ring,
                        zone = zone,
                        red = color shr 16 and 0xFF,
                        green = color shr 8 and 0xFF,
                        blue = color and 0xFF,
                        brightness = levels[index],
                    )
                    writePocketEvoFrameRepeated(frame)?.let { return@transaction it }
                }
            }
            if (!preferences.edit().putBoolean("led_enabled", true).commit()) {
                return@transaction ApplyResult(false, "Could not save enabled LED state")
            }
            logEvent("Applied Pocket EVO eight-zone RGB and brightness")
            ApplyResult(true, "Applied eight quadrant colours · GameWindow paused")
        }
    }

    suspend fun restorePocketEvoGameWindow(): ApplyResult =
        serializedControllerOperation { restorePocketEvoGameWindowLocked() }

    suspend fun recoverInterruptedPocketEvoTransaction(): ApplyResult? =
        serializedControllerOperation {
            if (!hasInterruptedPocketEvoTransaction) {
                null
            } else {
                restorePocketEvoGameWindowLocked()
            }
        }

    private fun correctedPackedColor(
        color: Int,
        colorCorrection: Boolean,
        mixedGreenPercent: Int,
        mixedBluePercent: Int,
    ): Int {
        val channels = correctedRgb(
            red = color shr 16 and 0xFF,
            green = color shr 8 and 0xFF,
            blue = color and 0xFF,
            colorCorrection = colorCorrection,
            mixedGreenPercent = mixedGreenPercent,
            mixedBluePercent = mixedBluePercent,
        )
        return (channels[0] shl 16) or (channels[1] shl 8) or channels[2]
    }

    private fun pocketEvoBrightnessLevel(percent: Int): Int =
        (percent.coerceIn(1, 100) * 255 / 100).coerceIn(1, 255)

    private fun executePocketEvoDirectTransaction(
        action: () -> ApplyResult,
    ): ApplyResult {
        validatePocketEvoDirectEnvironment()?.let { return it }
        val stateStarted = runCatching {
            setPocketEvoControlState(POCKET_EVO_STATE_STOPPING)
        }.exceptionOrNull()
        if (stateStarted != null) {
            return ApplyResult(
                false,
                "Could not persist RGB ownership; GameWindow was not stopped",
            )
        }

        val preflightOrStopFailure = stopGameWindowForPocketEvo()
        if (preflightOrStopFailure != null) {
            return recoverPocketEvoFailure(preflightOrStopFailure)
        }
        val result = runCatching(action).getOrElse {
            ApplyResult(false, "Pocket EVO RGB transaction failed: ${it.message}")
        }
        if (!result.success) return recoverPocketEvoFailure(result)

        return runCatching {
            setPocketEvoControlState(POCKET_EVO_STATE_DIRECT)
            result
        }.getOrElse {
            recoverPocketEvoFailure(
                ApplyResult(false, "Could not retain direct RGB ownership: ${it.message}"),
            )
        }
    }

    private fun validatePocketEvoDirectEnvironment(): ApplyResult? {
        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(GAMEWINDOW_PACKAGE, 0)
        }.getOrElse {
            return ApplyResult(false, "Could not verify GameWindow: ${it.message}")
        }
        if (
            packageInfo.versionName != POCKET_EVO_VALIDATED_GAMEWINDOW_VERSION ||
            packageInfo.longVersionCode != POCKET_EVO_VALIDATED_GAMEWINDOW_CODE
        ) {
            return ApplyResult(
                false,
                "Direct RGB is validated with GameWindow " +
                    "$POCKET_EVO_VALIDATED_GAMEWINDOW_VERSION " +
                    "($POCKET_EVO_VALIDATED_GAMEWINDOW_CODE) only",
            )
        }
        val version = runCatching {
            runAsRoot("cat '$CONFIG_DIR/aya_firmware_local_version.conf' 2>/dev/null")
        }.getOrElse {
            return ApplyResult(false, "Could not read Pocket EVO controller marker: ${it.message}")
        }
        if (version.exitCode != 0 ||
            version.output.trim() != POCKET_EVO_VALIDATED_FIRMWARE_MARKER
        ) {
            return ApplyResult(
                false,
                "Direct RGB requires the physically validated Pocket EVO controller marker " +
                POCKET_EVO_VALIDATED_FIRMWARE_MARKER,
            )
        }
        val uartPath = deviceProfile.uartPath
            ?: return ApplyResult(false, "Pocket EVO UART is unavailable")
        val module = runCatching {
            runAsRoot(
                "test -d '$POCKET_EVO_MAGISK_MODULE_DIR' && " +
                    "test ! -e '$POCKET_EVO_MAGISK_MODULE_DIR/disable' && " +
                    "test ! -e '$POCKET_EVO_MAGISK_MODULE_DIR/remove' && " +
                    "test -c '$uartPath' && ls -lZ '$uartPath'",
            )
        }.getOrElse {
            return ApplyResult(false, "Could not verify the AYANEO RGB UART module: ${it.message}")
        }
        if (
            module.exitCode != 0 ||
            POCKET_EVO_UART_SELINUX_CONTEXT !in module.output
        ) {
            return ApplyResult(
                false,
                "Direct RGB requires the active AYANEO RGB UART Magisk module; " +
                    "install or enable it, then reboot",
            )
        }
        return null
    }

    private fun stopGameWindowForPocketEvo(): ApplyResult? {
        // The IPC request is cooperative only; force-stop below is authoritative.
        sendRgbMessage("com_set_rgb_is_open:false")
        Thread.sleep(350)
        detachGameWindowBinding()
        val stop = runCatching { runAsRoot("am force-stop $GAMEWINDOW_PACKAGE") }.getOrElse {
            return ApplyResult(false, "Could not stop GameWindow: ${it.message}")
        }
        if (stop.exitCode != 0) {
            return ApplyResult(false, stop.output.ifBlank { "Could not stop GameWindow" })
        }
        var lastPid = "unknown"
        var lastHolders = "unknown"
        var consecutiveClearChecks = 0
        repeat(20) {
            val owner = runCatching { runAsRoot("pidof $GAMEWINDOW_PACKAGE") }.getOrElse {
                return ApplyResult(false, "Could not verify GameWindow stopped: ${it.message}")
            }
            val holders = runCatching { scanPocketEvoUartOwners() }.getOrElse {
                return ApplyResult(false, "Could not inspect RGB UART owners: ${it.message}")
            }
            if (
                owner.exitCode != 0 &&
                !(owner.exitCode == 1 && owner.output.isBlank())
            ) {
                return ApplyResult(
                    false,
                    owner.output.ifBlank { "Could not verify GameWindow stopped" },
                )
            }
            if (holders.exitCode != 0) {
                return ApplyResult(
                    false,
                    holders.output.ifBlank { "RGB UART ownership scan failed closed" },
                )
            }
            lastPid = owner.output.trim()
            lastHolders = holders.output.trim()
            if (lastPid.isBlank() && lastHolders.isBlank()) {
                consecutiveClearChecks += 1
                if (consecutiveClearChecks >= 2) return null
            } else {
                consecutiveClearChecks = 0
            }
            Thread.sleep(100)
        }
        return ApplyResult(
            false,
            "Could not acquire RGB UART safely (GameWindow PID=${lastPid.ifBlank { "none" }}, " +
                "holders=${lastHolders.ifBlank { "none" }})",
        )
    }

    private fun scanPocketEvoUartOwners(): RootResult {
        val uartPath = deviceProfile.uartPath ?: return RootResult(1, "UART unavailable")
        val command =
            "if [ ! -c '$uartPath' ]; then\n" +
                "  printf '%s\\n' 'RGB UART unavailable'\n" +
                "  exit 2\n" +
                "fi\n" +
                "if [ ! -x /system/bin/toybox ]; then\n" +
                "  printf '%s\\n' 'Android toybox unavailable'\n" +
                "  exit 126\n" +
                "fi\n" +
                "exec /system/bin/toybox timeout -s KILL 4s " +
                "/system/bin/toybox lsof -t '$uartPath'"
        val result = runAsRoot(command)
        if (result.exitCode != 0) return result
        val owners = result.output.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()
        if (owners.any { owner -> owner.any { char -> !char.isDigit() } }) {
            return RootResult(2, "Unexpected RGB UART owner output: ${result.output}")
        }
        return result.copy(output = owners.joinToString(" "))
    }

    private fun writePocketEvoFrameRepeated(
        frame: ByteArray,
        repeats: Int = 3,
        delayMillis: Long = 350,
    ): ApplyResult? {
        val uartPath = deviceProfile.uartPath
            ?: return ApplyResult(false, "Pocket EVO UART is unavailable")
        val escaped = PocketEvoRgbProtocol.toOctalShellEscape(frame)
        repeat(repeats) { index ->
            val result = runCatching {
                runAsRoot("printf '$escaped' > '$uartPath'")
            }.getOrElse {
                return ApplyResult(false, "Pocket EVO UART write failed: ${it.message}")
            }
            if (result.exitCode != 0) {
                return ApplyResult(
                    false,
                    result.output.ifBlank { "Pocket EVO UART write failed" },
                )
            }
            Thread.sleep(delayMillis)
        }
        return null
    }

    private fun recoverPocketEvoFailure(failure: ApplyResult): ApplyResult {
        val recovery = runCatching { restorePocketEvoGameWindowLocked() }.getOrElse {
            ApplyResult(false, "Verified GameWindow recovery failed: ${it.message}")
        }
        logEvent("Pocket EVO direct transaction failed; recovery=${recovery.success}")
        return failure.copy(
            message = failure.message + if (recovery.success) {
                " · GameWindow PID/UART recovery verified"
            } else {
                " · ${recovery.message}; recovery marker retained"
            },
        )
    }

    private fun restorePocketEvoGameWindowLocked(): ApplyResult {
        if (!supportsPocketEvoAdvancedRgb) {
            return ApplyResult(false, "GameWindow handoff is Pocket EVO only")
        }
        runCatching { setPocketEvoControlState(POCKET_EVO_STATE_RESTORING) }.getOrElse {
            return ApplyResult(false, "Could not persist GameWindow recovery state: ${it.message}")
        }
        detachGameWindowBinding()

        val ledEnabled = preferences.getBoolean("led_enabled", true)
        val config = runCatching {
            runAsRoot(
                "mkdir -p '$CONFIG_DIR' && " +
                    "printf '%s' '${ledEnabled}' > '$CONFIG_DIR/aya_rgb_is_open.conf' && " +
                    "chown system:system '$CONFIG_DIR/aya_rgb_is_open.conf' && " +
                    "chmod 0660 '$CONFIG_DIR/aya_rgb_is_open.conf'",
            )
        }.getOrNull()
        if (config?.exitCode != 0) {
            logEvent("Could not synchronise stock LED preference before GameWindow restart")
        }

        val started = runCatching {
            runAsRoot(
                "am startservice -n " +
                    "$GAMEWINDOW_PACKAGE/$GAMEWINDOW_PACKAGE.utils.aidl.AyaAidlService",
            )
        }.getOrElse {
            return ApplyResult(false, "Could not restart GameWindow: ${it.message}")
        }
        if (started.exitCode != 0) {
            return ApplyResult(
                false,
                started.output.ifBlank { "GameWindow restart failed; recovery marker retained" },
            )
        }

        val uartPath = deviceProfile.uartPath
            ?: return ApplyResult(false, "Pocket EVO UART is unavailable")
        val inputProbe = runCatching {
            runAsRoot("test -c '$POCKET_EVO_VALIDATED_INPUT_PATH'")
        }.getOrNull()
        if (inputProbe?.exitCode != 0) {
            return ApplyResult(
                false,
                "Validated controller input $POCKET_EVO_VALIDATED_INPUT_PATH is unavailable; " +
                    "recovery marker retained",
            )
        }
        var verifiedPid: String? = null
        for (attempt in 0 until 35) {
            val pidResult = runCatching {
                runAsRoot("pidof $GAMEWINDOW_PACKAGE")
            }.getOrElse {
                return ApplyResult(
                    false,
                    "Could not verify restarted GameWindow: ${it.message}; " +
                        "recovery marker retained",
                )
            }
            if (
                pidResult.exitCode != 0 &&
                !(pidResult.exitCode == 1 && pidResult.output.isBlank())
            ) {
                return ApplyResult(
                    false,
                    pidResult.output.ifBlank {
                        "Could not verify restarted GameWindow; recovery marker retained"
                    },
                )
            }
            val pid = pidResult.output
                .split(Regex("\\s+"))
                .firstOrNull { token -> token.isNotEmpty() && token.all(Char::isDigit) }
            if (pid != null) {
                val descriptors = runCatching {
                    runAsRoot("ls -l '/proc/$pid/fd' 2>/dev/null")
                }.getOrElse {
                    return ApplyResult(
                        false,
                        "Could not inspect restarted GameWindow: ${it.message}; " +
                            "recovery marker retained",
                    )
                }
                if (descriptors.exitCode == 0) {
                    val targets = descriptors.output.lineSequence()
                        .map { it.substringAfter(" -> ", "").trim() }
                        .filter(String::isNotEmpty)
                        .toList()
                    val uartCount = targets.count { it == uartPath }
                    if (uartCount == 1 || !ledEnabled) {
                        verifiedPid = pid
                        break
                    }
                } else if (descriptors.output.isNotBlank()) {
                    return ApplyResult(
                        false,
                        descriptors.output + "; recovery marker retained",
                    )
                }
            }
            Thread.sleep(150)
        }
        if (verifiedPid == null) {
            return ApplyResult(
                false,
                "GameWindow started but PID/UART/input ownership was not verified; " +
                    "recovery marker retained",
            )
        }

        // Force a fresh binder after the old service process was killed.
        detachGameWindowBinding()
        if (!bindGameWindowService()) {
            return ApplyResult(false, "GameWindow process recovered but fresh IPC bind failed")
        }
        for (attempt in 0 until 30) {
            if (liveBinder() != null) break
            Thread.sleep(100)
        }
        val sent = sendRgbMessage("com_set_rgb_is_open:${ledEnabled}")
        if (!sent) {
            return ApplyResult(
                false,
                "GameWindow PID/UART recovered but stock IPC handoff failed; " +
                "recovery marker retained",
            )
        }
        val stockMode = loadLastStockMode()
        if (!preferences.edit().putInt("mode", stockMode).commit()) {
            return ApplyResult(
                false,
                "GameWindow recovered but the stock UI mode could not be restored; " +
                    "recovery marker retained",
            )
        }
        runCatching { setPocketEvoControlState(POCKET_EVO_STATE_STOCK) }.getOrElse {
            return ApplyResult(
                false,
                "GameWindow recovered but ownership state could not be cleared: ${it.message}",
            )
        }
        logEvent(
            "Returned Pocket EVO RGB control to verified GameWindow PID=$verifiedPid " +
                "ledEnabled=$ledEnabled",
        )
        return ApplyResult(
            true,
            if (ledEnabled) {
                "Returned RGB control to AYANEO · PID/UART verified"
            } else {
                "Returned RGB control to AYANEO with LEDs off · process/input verified"
            },
        )
    }

    private fun buildDirectModeCommand(
        mode: Int,
        red: Int,
        green: Int,
        blue: Int,
        brightness: Int,
    ): String? {
        val uartPath = deviceProfile.uartPath ?: return null
        if (deviceProfile.usesKr02Protocol) {
            val kr02Mode = when (mode) {
                1 -> 0x02 // Stock Single Breath
                3 -> 0x03 // Stock Google/Rainbow Breath
                6 -> 0x01 // Stock Static
                else -> return null
            }
            val stockBrightness = brightness.coerceIn(1, 100) * 255 / 100
            fun kr02Channel(channel: Int): Int {
                val scaled = (channel.coerceIn(0, 255) * stockBrightness * 0.6f / 255f)
                    .toInt()
                    .coerceIn(0, 99)
                return (33.0 + scaled * (120.0 / 99.0)).roundToInt()
            }
            val packet = intArrayOf(
                0xF7, kr02Mode,
                if (mode == 3) 0 else kr02Channel(red),
                if (mode == 3) 0 else kr02Channel(green),
                if (mode == 3) 0 else kr02Channel(blue),
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

    suspend fun setLedEnabled(enabled: Boolean): ApplyResult = serializedControllerOperation {
        if (supportsPocketEvoAdvancedRgb && hasInterruptedPocketEvoTransaction) {
            val restored = restorePocketEvoGameWindowLocked()
            if (!restored.success) return@serializedControllerOperation restored
        }
        if (supportsPocketEvoAdvancedRgb && isPocketEvoDirectControlActive) {
            if (enabled) {
                if (!preferences.edit().putBoolean("led_enabled", true).commit()) {
                    return@serializedControllerOperation ApplyResult(
                        false,
                        "Could not save LED state",
                    )
                }
                return@serializedControllerOperation ApplyResult(
                    true,
                    "LEDs enabled; applying the selected Pocket EVO layout",
                )
            }
            return@serializedControllerOperation executePocketEvoDirectTransaction transaction@ {
                val offFrame = PocketEvoRgbProtocol.buildStaticFrame(
                    red = 0,
                    green = 0,
                    blue = 0,
                    brightness = 0,
                    ring = PocketEvoRgbProtocol.Ring.BROADCAST,
                )
                writePocketEvoFrameRepeated(offFrame)?.let { return@transaction it }
                if (!preferences.edit().putBoolean("led_enabled", false).commit()) {
                    return@transaction ApplyResult(
                        false,
                        "LEDs were disabled but state could not be saved",
                    )
                }
                logEvent("Pocket EVO direct RGB disabled with broadcast black Static frame")
                ApplyResult(true, "LEDs off · GameWindow remains paused")
            }
        }
        val value = enabled.toString()
        if (!enabled) sendRgbMessage("com_set_rgb_is_open:false")
        var command =
            "mkdir -p '$CONFIG_DIR' && chown media_rw:media_rw '$CONFIG_DIR' && " +
                "chmod 0775 '$CONFIG_DIR' && " +
                "printf '%s' '$value' > '$CONFIG_DIR/aya_rgb_is_open.conf' && " +
                "chown system:system '$CONFIG_DIR/aya_rgb_is_open.conf' && " +
                "chmod 0660 '$CONFIG_DIR/aya_rgb_is_open.conf'"
        if (!enabled && deviceProfile.usesKr02Protocol) {
            buildKr02ShutdownCommand()?.let { command += " && $it" }
        }
        val rootResult = runCatching {
            runAsRoot(command)
        }.getOrElse {
            return@serializedControllerOperation ApplyResult(
                false,
                "Could not start root shell: ${it.message}",
            )
        }
        if (rootResult.exitCode != 0) {
            val output = rootResult.output
            logEvent("LED enabled=$enabled failed: ${output.ifBlank { "root write failed" }}")
            return@serializedControllerOperation ApplyResult(
                false,
                output.ifBlank { "LED state write failed" },
            )
        }
        val sent = if (enabled) sendRgbMessage("com_set_rgb_is_open:true") else true
        if (sent) preferences.edit().putBoolean("led_enabled", enabled).apply()
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

    private fun liveBinder(): IBinder? {
        val candidate = binder ?: return null
        return if (candidate.isBinderAlive && candidate.pingBinder()) {
            candidate
        } else {
            invalidateGameWindowBinder(candidate)
            null
        }
    }

    @Synchronized
    private fun invalidateGameWindowBinder(expected: IBinder) {
        if (binder === expected) detachGameWindowBinding()
    }

    private fun sendRgbMessage(message: String): Boolean {
        val remote = liveBinder() ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(AIDL_DESCRIPTOR)
            data.writeString("rgbpicker:msg_type_rgb:$message")
            if (!remote.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0)) {
                invalidateGameWindowBinder(remote)
                return false
            }
            reply.readException()
            true
        } catch (_: Exception) {
            invalidateGameWindowBinder(remote)
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
