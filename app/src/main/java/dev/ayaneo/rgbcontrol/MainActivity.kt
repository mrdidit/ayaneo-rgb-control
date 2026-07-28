package dev.ayaneo.rgbcontrol

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {
    private lateinit var controller: RgbController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = RgbController(this)
        controller.bind()
        setContent { AyaneoRgbApp(controller) }
    }

    override fun onDestroy() {
        controller.unbind()
        super.onDestroy()
    }
}

private data class RgbPreset(val key: String, val name: String, val color: Color)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AyaneoRgbApp(controller: RgbController) {
    val deviceProfile = remember { controller.deviceProfile }
    val saved = remember { controller.loadSettings() }
    val savedHsv = remember(saved) {
        FloatArray(3).also {
            AndroidColor.colorToHSV(AndroidColor.rgb(saved.red, saved.green, saved.blue), it)
        }
    }
    var hue by remember { mutableFloatStateOf(savedHsv[0]) }
    var saturation by remember { mutableFloatStateOf(savedHsv[1]) }
    var value by remember { mutableFloatStateOf(savedHsv[2]) }
    var brightness by remember { mutableFloatStateOf(saved.brightness.toFloat()) }
    var mode by remember { mutableIntStateOf(saved.mode) }
    var livePreview by remember { mutableStateOf(saved.livePreview) }
    var colorCorrection by remember { mutableStateOf(saved.colorCorrection) }
    var globalGreenPercent by remember {
        mutableFloatStateOf(saved.mixedGreenPercent.toFloat())
    }
    var globalBluePercent by remember {
        mutableFloatStateOf(saved.mixedBluePercent.toFloat())
    }
    var mixedGreenPercent by remember { mutableFloatStateOf(globalGreenPercent) }
    var mixedBluePercent by remember { mutableFloatStateOf(globalBluePercent) }
    var calibrationTargetKey by remember { mutableStateOf<String?>(null) }
    var calibrationTargetName by remember { mutableStateOf<String?>(null) }
    var calibrationOverrideEnabled by remember { mutableStateOf(false) }
    var ledEnabled by remember { mutableStateOf(saved.ledEnabled) }
    var reactiveIdleColor by remember { mutableIntStateOf(saved.reactiveIdleColor) }
    var reactiveHighlightColor by remember { mutableIntStateOf(saved.reactiveHighlightColor) }
    var themeColor by remember { mutableIntStateOf(saved.themeColor) }
    var reactiveTarget by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("Connected to AYANEO GameWindow") }
    var pendingApply by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val rgb = remember(hue, saturation, value) { hsvToRgb(hue, saturation, value) }
    var hexText by remember(rgb) { mutableStateOf("%02X%02X%02X".format(rgb[0], rgb[1], rgb[2])) }

    val presets = remember {
        listOf(
            RgbPreset("red", "Red", Color(0xFFFF0000)),
            RgbPreset("orange", "Orange", Color(0xFFFF8000)),
            RgbPreset("green", "Green", Color(0xFF00FF00)),
            RgbPreset("cyan", "Cyan", Color(0xFF00FFFF)),
            RgbPreset("blue", "Blue", Color(0xFF0000FF)),
            RgbPreset("violet", "Violet", Color(0xFF8000FF)),
            RgbPreset("cream", "Cream", Color(0xFFFFB820)),
            RgbPreset("white", "White", Color.White),
        )
    }
    val customColors = remember { controller.loadCustomColors().toMutableStateList() }

    fun selectArgb(color: Int, targetKey: String? = null, targetName: String? = null) {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(color, hsv)
        hue = hsv[0]
        saturation = hsv[1]
        value = hsv[2]
        calibrationTargetKey = targetKey
        calibrationTargetName = targetName
        val calibration = targetKey?.let { controller.loadCalibrationOverride(it) }
        calibrationOverrideEnabled = calibration != null
        mixedGreenPercent = calibration?.first?.toFloat() ?: globalGreenPercent
        mixedBluePercent = calibration?.second?.toFloat() ?: globalBluePercent
    }

    fun saveCustomColor(index: Int) {
        val color = AndroidColor.rgb(rgb[0], rgb[1], rgb[2])
        customColors[index] = color
        controller.saveCustomColor(index, color)
        val key = "custom_$index"
        controller.saveCalibrationOverride(
            key,
            mixedGreenPercent.toInt(),
            mixedBluePercent.toInt(),
        )
        calibrationTargetKey = key
        calibrationTargetName = "Custom colour ${index + 1}"
        calibrationOverrideEnabled = true
        status = "Saved #${"%02X%02X%02X".format(rgb[0], rgb[1], rgb[2])} to Custom ${index + 1}"
    }

    fun applyNow() {
        pendingApply?.cancel()
        pendingApply = scope.launch {
            val result = controller.apply(
                mode,
                rgb[0],
                rgb[1],
                rgb[2],
                brightness.toInt(),
                colorCorrection,
                mixedGreenPercent.toInt(),
                mixedBluePercent.toInt(),
                reactiveIdleColor,
                reactiveHighlightColor,
            )
            status = result.message
        }
    }

    LaunchedEffect(
        hue,
        saturation,
        value,
        brightness,
        mode,
        livePreview,
        colorCorrection,
        mixedGreenPercent,
        mixedBluePercent,
        reactiveTarget,
    ) {
        if (mode == 7) {
            val selectedColor = AndroidColor.rgb(rgb[0], rgb[1], rgb[2]) and 0xFFFFFF
            if (reactiveTarget == 0) reactiveIdleColor = selectedColor
            else reactiveHighlightColor = selectedColor
        }
        if (livePreview && ledEnabled) {
            delay(100)
            applyNow()
        }
    }

    val themeAccent = Color(themeColor or 0xFF000000.toInt())
    val themeTextColor = if (
        ((themeColor shr 16 and 255) * 0.299f +
            (themeColor shr 8 and 255) * 0.587f +
            (themeColor and 255) * 0.114f) > 150f
    ) {
        Color(0xFF101116)
    } else {
        Color.White
    }
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = themeAccent,
            secondary = Color(0xFFBD93F9),
            background = Color(0xFF101116),
            surface = Color(0xFF191B22),
        ),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                buildAnnotatedString {
                                    val rainbow = listOf(
                                        Color(0xFFFF4D4D),
                                        Color(0xFFFF9F43),
                                        Color(0xFFFFD93D),
                                        Color(0xFF6BCB77),
                                        Color(0xFF4DDBFF),
                                        Color(0xFF4D79FF),
                                        Color(0xFFB967FF),
                                    )
                                    var colourIndex = 0
                                    "AYANEO RGB".forEach { character ->
                                        if (character == ' ') {
                                            append(character)
                                        } else {
                                            withStyle(
                                                SpanStyle(
                                                    color = rainbow[colourIndex % rainbow.size],
                                                    fontWeight = FontWeight.Bold,
                                                ),
                                            ) {
                                                append(character)
                                            }
                                            colourIndex++
                                        }
                                    }
                                },
                            )
                            Text(
                                if (deviceProfile.supportsDirectUart) {
                                    "${deviceProfile.name} · ${deviceProfile.uartPath}"
                                } else {
                                    "${deviceProfile.name} · safe mode"
                                },
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .padding(horizontal = 20.dp)
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(pass = PointerEventPass.Initial)
                            focusManager.clearFocus()
                        }
                    }
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (!deviceProfile.supportsDirectUart) {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                "Help add this device",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "This model is in safe mode. Direct UART writes are disabled " +
                                    "until its device node and protocol are verified.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text("1. Open AYANEO's stock RGB controls and select Static.")
                            Text("2. Select a clear green and note what you see.")
                            Text("3. Select a clear blue and note what you see.")
                            Text("4. Export the diagnostics bundle.")
                            Text("5. Send the exported text file from the AYARGB folder.")
                            Text(
                                "The report is read-only and does not capture UART packet bytes. " +
                                    "A developer may arrange a separate controlled trace afterward. " +
                                    "Do not try UART nodes manually.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                            )
                            Button(
                                onClick = {
                                    scope.launch {
                                        status = "Exporting diagnostics…"
                                        status = controller.exportDiagnosticsBundle().message
                                    }
                                },
                            ) {
                                Text("Export test bundle to AYARGB")
                            }
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        status = "Building GameWindow research bundle…"
                                        status = controller.exportGameWindowResearchBundle().message
                                    }
                                },
                            ) {
                                Text("Export GameWindow research ZIP")
                            }
                        }
                    }
                }

                val colorSelectionSupported = mode != 2 && mode != 3
                Text(
                    if (colorSelectionSupported) {
                        "Colour"
                    } else {
                        "◆  Colour fixed by this RGB effect"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (colorSelectionSupported) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        Color(0xFFFF9F43)
                    },
                    fontWeight = if (colorSelectionSupported) {
                        FontWeight.Normal
                    } else {
                        FontWeight.Bold
                    },
                )
                HoneycombColorPicker(
                    hue = hue,
                    saturation = saturation,
                    value = value,
                    enabled = colorSelectionSupported,
                    onChange = { h, s ->
                        hue = h
                        saturation = s
                        calibrationTargetKey = null
                        calibrationTargetName = null
                        calibrationOverrideEnabled = false
                        mixedGreenPercent = globalGreenPercent
                        mixedBluePercent = globalBluePercent
                    },
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(280.dp)
                        .alpha(if (colorSelectionSupported) 1f else 0.38f),
                )

                val selectedColor = Color(rgb[0], rgb[1], rgb[2])
                val perceivedBrightness =
                    rgb[0] * 0.299f + rgb[1] * 0.587f + rgb[2] * 0.114f
                val codeTextColor =
                    if (perceivedBrightness > 150f) Color(0xFF101116) else Color.White
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(selectedColor)
                        .border(
                            1.dp,
                            themeAccent.copy(alpha = .85f),
                            RoundedCornerShape(14.dp),
                        )
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("#", color = codeTextColor, fontWeight = FontWeight.Bold)
                    BasicTextField(
                        value = hexText,
                        enabled = colorSelectionSupported,
                        onValueChange = { input ->
                            val clean = input.uppercase().filter { it in "0123456789ABCDEF" }.take(6)
                            hexText = clean
                            if (clean.length == 6) {
                                val parsed = clean.toLong(16).toInt()
                                val hsv = FloatArray(3)
                                AndroidColor.colorToHSV(
                                    AndroidColor.rgb(parsed shr 16 and 255, parsed shr 8 and 255, parsed and 255),
                                    hsv,
                                )
                                hue = hsv[0]; saturation = hsv[1]; value = hsv[2]
                                calibrationTargetKey = null
                                calibrationTargetName = null
                                calibrationOverrideEnabled = false
                                mixedGreenPercent = globalGreenPercent
                                mixedBluePercent = globalBluePercent
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        textStyle = TextStyle(
                            color = codeTextColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                        ),
                        modifier = Modifier.width(72.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "RGB ${rgb[0]}, ${rgb[1]}, ${rgb[2]}",
                        color = codeTextColor,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (colorSelectionSupported) 1f else 0.38f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(themeAccent)
                        .border(
                            1.dp,
                            themeTextColor.copy(alpha = .28f),
                            RoundedCornerShape(14.dp),
                        )
                        .clickable(enabled = colorSelectionSupported) {
                            themeColor =
                                AndroidColor.rgb(rgb[0], rgb[1], rgb[2]) and 0xFFFFFF
                            controller.saveThemeColor(themeColor)
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(themeTextColor.copy(alpha = .78f)),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Use selected colour as theme",
                        color = themeTextColor,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Text("Presets", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(presets) { preset ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val presetInput = if (colorSelectionSupported) {
                                Modifier.pointerInput(preset) {
                                    detectTapGestures {
                                        selectArgb(
                                            preset.color.toArgbCompat(),
                                            "preset_${preset.key}",
                                            preset.name,
                                        )
                                    }
                                }
                            } else {
                                Modifier
                            }
                            Box(
                                Modifier
                                    .size(42.dp)
                                    .alpha(if (colorSelectionSupported) 1f else 0.38f)
                                    .clip(CircleShape)
                                    .background(preset.color)
                                    .border(2.dp, themeAccent.copy(alpha = .85f), CircleShape)
                                    .then(presetInput),
                            )
                            Text(preset.name, fontSize = 10.sp)
                        }
                    }
                }

                Text("Custom colours", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Tap an empty slot to save. Tap a saved colour to use it; hold to replace it.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(customColors.size) { index ->
                        val customColor = customColors[index]
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val customInput = if (colorSelectionSupported) {
                                Modifier.pointerInput(index, customColor) {
                                    detectTapGestures(
                                        onTap = {
                                            if (customColor == null) saveCustomColor(index)
                                            else selectArgb(
                                                customColor,
                                                "custom_$index",
                                                "Custom colour ${index + 1}",
                                            )
                                        },
                                        onLongPress = { saveCustomColor(index) },
                                    )
                                }
                            } else {
                                Modifier
                            }
                            Box(
                                Modifier
                                    .size(42.dp)
                                    .alpha(if (colorSelectionSupported) 1f else 0.38f)
                                    .clip(CircleShape)
                                    .background(customColor?.let { Color(it) } ?: Color.Transparent)
                                    .border(
                                        2.dp,
                                        themeAccent.copy(alpha = .85f),
                                        CircleShape,
                                    )
                                    .then(customInput),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (customColor == null) {
                                    Text("+", fontSize = 22.sp, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Text("${index + 1}", fontSize = 10.sp)
                        }
                    }
                }

                val brightnessSupported = mode != 2 && mode != 3
                Text(
                    if (brightnessSupported) {
                        "Brightness ${brightness.toInt()}%"
                    } else {
                        "◆  Brightness fixed by this RGB effect"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (brightnessSupported) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        Color(0xFFFF9F43)
                    },
                    fontWeight = if (brightnessSupported) {
                        FontWeight.Normal
                    } else {
                        FontWeight.Bold
                    },
                )
                Slider(
                    value = brightness,
                    onValueChange = { brightness = it },
                    valueRange = 1f..100f,
                    enabled = brightnessSupported,
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    buildList {
                        add(6 to "Static")
                        add(1 to "Single Breath")
                        if (deviceProfile.supportsRgbCycle) add(2 to "RGB Breath")
                        add(3 to "Rainbow")
                        if (deviceProfile.supportsReactive) add(7 to "Reactive")
                    }.forEach { (id, label) ->
                        FilterChip(
                            selected = mode == id,
                            onClick = {
                                mode = id
                                controller.saveMode(id)
                                if (id == 7) {
                                    selectArgb(
                                        if (reactiveTarget == 0) reactiveIdleColor
                                        else reactiveHighlightColor,
                                    )
                                }
                            },
                            label = { Text(label) },
                        )
                    }
                }

                if (mode == 7 && deviceProfile.supportsReactive) {
                    Text("Reactive colours", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            Triple(0, "Idle", reactiveIdleColor),
                            Triple(1, "Reactive", reactiveHighlightColor),
                        ).forEach { (target, label, color) ->
                            FilterChip(
                                selected = reactiveTarget == target,
                                onClick = {
                                    reactiveTarget = target
                                    selectArgb(color)
                                },
                                leadingIcon = {
                                    Box(
                                        Modifier
                                            .size(18.dp)
                                            .clip(CircleShape)
                                            .background(Color(color or 0xFF000000.toInt())),
                                    )
                                },
                                label = { Text(label) },
                            )
                        }
                    }
                    Text(
                        "Select Idle or Reactive, then choose its colour above.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = livePreview,
                        onCheckedChange = {
                            livePreview = it
                            controller.saveLivePreview(it)
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Live preview")
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { applyNow() }) { Text("Apply") }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = colorCorrection,
                        onCheckedChange = { colorCorrection = it },
                        enabled = colorSelectionSupported,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Colour correction")
                }
                if (colorCorrection) {
                    calibrationTargetKey?.let { targetKey ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = calibrationOverrideEnabled,
                                onCheckedChange = { enabled ->
                                    calibrationOverrideEnabled = enabled
                                    if (enabled) {
                                        controller.saveCalibrationOverride(
                                            targetKey,
                                            mixedGreenPercent.toInt(),
                                            mixedBluePercent.toInt(),
                                        )
                                    } else {
                                        controller.clearCalibrationOverride(targetKey)
                                        mixedGreenPercent = globalGreenPercent
                                        mixedBluePercent = globalBluePercent
                                    }
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Custom strengths for " +
                                    if (targetKey.startsWith("preset_")) {
                                        "Preset: ${calibrationTargetName ?: "this colour"}"
                                    } else {
                                        calibrationTargetName ?: "this colour"
                                    },
                            )
                        }
                    }
                    Text("Mixed green strength: ${mixedGreenPercent.toInt()}%")
                    Slider(
                        value = mixedGreenPercent,
                        onValueChange = {
                            mixedGreenPercent = it
                            val targetKey = calibrationTargetKey
                            if (calibrationOverrideEnabled && targetKey != null) {
                                controller.saveCalibrationOverride(
                                    targetKey,
                                    it.toInt(),
                                    mixedBluePercent.toInt(),
                                )
                            } else {
                                globalGreenPercent = it
                                controller.saveGlobalCalibration(
                                    it.toInt(),
                                    mixedBluePercent.toInt(),
                                )
                            }
                        },
                        valueRange = 0f..100f,
                        steps = 19,
                    )
                    Text("Mixed blue strength: ${mixedBluePercent.toInt()}%")
                    Slider(
                        value = mixedBluePercent,
                        onValueChange = {
                            mixedBluePercent = it
                            val targetKey = calibrationTargetKey
                            if (calibrationOverrideEnabled && targetKey != null) {
                                controller.saveCalibrationOverride(
                                    targetKey,
                                    mixedGreenPercent.toInt(),
                                    it.toInt(),
                                )
                            } else {
                                globalBluePercent = it
                                controller.saveGlobalCalibration(
                                    mixedGreenPercent.toInt(),
                                    it.toInt(),
                                )
                            }
                        },
                        valueRange = 0f..100f,
                        steps = 19,
                    )
                    Text(
                        "Tune with White until the LEDs look neutral, then check Cream. " +
                            "Pure green and blue are left unchanged.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = ledEnabled,
                        onCheckedChange = { enabled ->
                            ledEnabled = enabled
                            scope.launch {
                                status = controller.setLedEnabled(enabled).message
                                if (enabled) applyNow()
                            }
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("LEDs enabled")
                }

                Text(
                    status,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            status = "Exporting diagnostics…"
                            status = controller.exportDiagnosticsBundle().message
                        }
                    },
                    modifier = Modifier.padding(bottom = 12.dp),
                ) {
                    Text("Export diagnostics")
                }
            }
        }
    }
}

@Composable
private fun HoneycombColorPicker(
    hue: Float,
    saturation: Float,
    value: Float,
    enabled: Boolean,
    onChange: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    fun pick(point: Offset, width: Float, height: Float) {
        val center = Offset(width / 2f, height / 2f)
        val rings = 7
        val cellRadius = min(width, height) / 25f
        val horizontalStep = sqrt(3f) * cellRadius
        val verticalStep = 1.5f * cellRadius
        val paletteRadius = verticalStep * rings
        var nearest = center
        var nearestDistance = Float.MAX_VALUE

        for (q in -rings..rings) {
            val rMin = maxOf(-rings, -q - rings)
            val rMax = minOf(rings, -q + rings)
            for (r in rMin..rMax) {
                val candidate = Offset(
                    center.x + horizontalStep * (q + r / 2f),
                    center.y + verticalStep * r,
                )
                val dx = point.x - candidate.x
                val dy = point.y - candidate.y
                val distance = dx * dx + dy * dy
                if (distance < nearestDistance) {
                    nearestDistance = distance
                    nearest = candidate
                }
            }
        }

        val dx = nearest.x - center.x
        val dy = nearest.y - center.y
        val saturationAtCell = (sqrt(dx * dx + dy * dy) / paletteRadius).coerceIn(0f, 1f)
        val hueAtCell = if (saturationAtCell == 0f) 0f
        else ((atan2(dy, dx) * 180f / PI.toFloat()) + 360f) % 360f
        onChange(hueAtCell, saturationAtCell)
    }

    val inputModifier = if (enabled) {
        Modifier
            .aspectRatio(1f)
            .pointerInput(Unit) {
                detectTapGestures { point ->
                    pick(point, size.width.toFloat(), size.height.toFloat())
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { point ->
                        pick(point, size.width.toFloat(), size.height.toFloat())
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        pick(change.position, size.width.toFloat(), size.height.toFloat())
                    },
                )
            }
    } else {
        Modifier.aspectRatio(1f)
    }
    Canvas(
        modifier = modifier.then(inputModifier),
    ) {
        val center = this.center
        val rings = 7
        val cellRadius = min(size.width, size.height) / 25f
        val horizontalStep = sqrt(3f) * cellRadius
        val verticalStep = 1.5f * cellRadius
        val paletteRadius = verticalStep * rings
        var marker = center
        var markerScore = Float.MAX_VALUE

        for (q in -rings..rings) {
            val rMin = maxOf(-rings, -q - rings)
            val rMax = minOf(rings, -q + rings)
            for (r in rMin..rMax) {
                val x = center.x + horizontalStep * (q + r / 2f)
                val y = center.y + verticalStep * r
                val dx = x - center.x
                val dy = y - center.y
                val distance = sqrt(dx * dx + dy * dy)
                val cellSaturation = (distance / paletteRadius).coerceIn(0f, 1f)
                val cellHue = if (distance < 0.5f) 0f else
                    ((atan2(dy, dx) * 180f / PI.toFloat()) + 360f) % 360f
                val hueDistance = min(
                    kotlin.math.abs(cellHue - hue),
                    360f - kotlin.math.abs(cellHue - hue),
                ) / 180f
                val score = hueDistance * hueDistance +
                    (cellSaturation - saturation) * (cellSaturation - saturation) * 4f
                if (score < markerScore) {
                    markerScore = score
                    marker = Offset(x, y)
                }

                val hexagon = Path()
                repeat(6) { index ->
                    val cornerAngle = Math.toRadians(60.0 * index - 30.0)
                    val corner = Offset(
                        x + cos(cornerAngle).toFloat() * cellRadius * 0.94f,
                        y + sin(cornerAngle).toFloat() * cellRadius * 0.94f,
                    )
                    if (index == 0) hexagon.moveTo(corner.x, corner.y)
                    else hexagon.lineTo(corner.x, corner.y)
                }
                hexagon.close()
                drawPath(hexagon, Color.hsv(cellHue, cellSaturation, value))
            }
        }

        drawCircle(Color.Black.copy(alpha = .75f), cellRadius * .72f, marker, style = Stroke(5.dp.toPx()))
        drawCircle(Color.White, cellRadius * .72f, marker, style = Stroke(2.dp.toPx()))
    }
}

private fun hsvToRgb(hue: Float, saturation: Float, value: Float): IntArray {
    val color = AndroidColor.HSVToColor(floatArrayOf(hue, saturation, value))
    return intArrayOf(AndroidColor.red(color), AndroidColor.green(color), AndroidColor.blue(color))
}

private fun Color.toArgbCompat(): Int =
    AndroidColor.argb(
        (alpha * 255).toInt(),
        (red * 255).toInt(),
        (green * 255).toInt(),
        (blue * 255).toInt(),
    )
