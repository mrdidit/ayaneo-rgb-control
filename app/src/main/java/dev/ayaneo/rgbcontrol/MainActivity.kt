package dev.ayaneo.rgbcontrol

import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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

private data class RgbPreset(val name: String, val color: Color)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AyaneoRgbApp(controller: RgbController) {
    val context = LocalContext.current
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
    var ledEnabled by remember { mutableStateOf(saved.ledEnabled) }
    var reactiveIdleColor by remember { mutableIntStateOf(saved.reactiveIdleColor) }
    var reactiveHighlightColor by remember { mutableIntStateOf(saved.reactiveHighlightColor) }
    var reactiveTarget by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("Connected to AYANEO GameWindow") }
    var pendingApply by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val rgb = remember(hue, saturation, value) { hsvToRgb(hue, saturation, value) }
    var hexText by remember(rgb) { mutableStateOf("%02X%02X%02X".format(rgb[0], rgb[1], rgb[2])) }

    val presets = remember {
        listOf(
            RgbPreset("Red", Color(0xFFFF0000)),
            RgbPreset("Orange", Color(0xFFFF8000)),
            RgbPreset("Green", Color(0xFF00FF00)),
            RgbPreset("Cyan", Color(0xFF00FFFF)),
            RgbPreset("Blue", Color(0xFF0000FF)),
            RgbPreset("Violet", Color(0xFF8000FF)),
            RgbPreset("Cream", Color(0xFFFFDC15)),
            RgbPreset("White", Color.White),
        )
    }
    val customColors = remember { controller.loadCustomColors().toMutableStateList() }

    fun copyToClipboard(label: String, text: String) {
        val clipboard =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    fun selectArgb(color: Int) {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(color, hsv)
        hue = hsv[0]
        saturation = hsv[1]
        value = hsv[2]
    }

    fun saveCustomColor(index: Int) {
        val color = AndroidColor.rgb(rgb[0], rgb[1], rgb[2])
        customColors[index] = color
        controller.saveCustomColor(index, color)
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

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF8BE9FD),
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
                            Text("AYANEO RGB", fontWeight = FontWeight.Bold)
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
                            Text("1. Select Static in this app.")
                            Text("2. Select the Green preset (#00FF00), tap Apply, and note what you see.")
                            Text("3. Select the Blue preset (#0000FF), tap Apply, and note what you see.")
                            Text("4. Export the diagnostics bundle.")
                            Text("5. Send both exported text files from the AYARGB folder.")
                            Text(
                                "The report is read-only and does not capture UART packet bytes. " +
                                    "A developer may arrange a separate controlled trace afterward. " +
                                    "Do not try UART nodes manually.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            status = "Collecting read-only diagnostics…"
                                            val report = controller.collectDiagnostics()
                                            copyToClipboard("AYANEO RGB diagnostics", report)
                                            status = "Diagnostics copied to clipboard"
                                        }
                                    },
                                ) {
                                    Text("Copy diagnostics")
                                }
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            status = "Exporting diagnostics…"
                                            status = controller.exportDiagnosticsBundle().message
                                        }
                                    },
                                ) {
                                    Text("Export to AYARGB")
                                }
                            }
                        }
                    }
                }

                HoneycombColorPicker(
                    hue = hue,
                    saturation = saturation,
                    value = value,
                    onChange = { h, s -> hue = h; saturation = s },
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(280.dp),
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(58.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(rgb[0], rgb[1], rgb[2]))
                            .border(1.dp, Color.White.copy(alpha = .2f), RoundedCornerShape(14.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = hexText,
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
                            }
                        },
                        prefix = { Text("#") },
                        label = { Text("Hex") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("${rgb[0]}, ${rgb[1]}, ${rgb[2]}", fontWeight = FontWeight.Medium)
                }

                Text("Presets", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(presets) { preset ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(preset.color)
                                    .border(1.dp, Color.White.copy(alpha = .25f), CircleShape)
                                    .pointerInput(preset) {
                                        detectTapGestures {
                                            selectArgb(preset.color.toArgbCompat())
                                        }
                                    },
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
                            Box(
                                Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(customColor?.let { Color(it) } ?: Color.Transparent)
                                    .border(
                                        1.dp,
                                        if (customColor == null) MaterialTheme.colorScheme.primary
                                        else Color.White.copy(alpha = .25f),
                                        CircleShape,
                                    )
                                    .pointerInput(index, customColor) {
                                        detectTapGestures(
                                            onTap = {
                                                if (customColor == null) saveCustomColor(index)
                                                else selectArgb(customColor)
                                            },
                                            onLongPress = { saveCustomColor(index) },
                                        )
                                    },
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

                Text("Brightness ${brightness.toInt()}%", style = MaterialTheme.typography.labelLarge)
                Slider(value = brightness, onValueChange = { brightness = it }, valueRange = 1f..100f)

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
                    Switch(checked = colorCorrection, onCheckedChange = { colorCorrection = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Colour correction")
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

    Canvas(
        modifier = modifier
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
            },
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
