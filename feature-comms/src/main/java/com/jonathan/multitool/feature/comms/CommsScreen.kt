package com.jonathan.multitool.feature.comms

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.jonathan.multitool.core.data.SettingsStore
import com.jonathan.multitool.ui.SectionCard
import com.jonathan.multitool.ui.SmallNote
import com.jonathan.multitool.ui.theme.LocalAccent
import com.jonathan.multitool.ui.theme.LocalShell
import com.jonathan.multitool.ui.theme.Mono
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

/**
 * Flashlight communication. Torch out and camera in run at the same time on one device — the
 * torch is driven through the camera's own capture session (`CameraControl.enableTorch`), which is
 * the same path a camera app in video mode uses, so the frames keep flowing while the light is on.
 */
@Composable
fun CommsScreen(settings: SettingsStore, mode: Mode) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val accent = LocalAccent.current
    val t = LocalShell.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { hasPermission = it }

    val link = remember { TorchLink() }
    val codec = remember(mode) { codecFor(mode) }
    val executor = remember { Executors.newSingleThreadExecutor() }

    var received by remember { mutableStateOf("") }
    var outgoing by remember { mutableStateOf("") }
    var symbolMs by remember { mutableStateOf(150f) }
    var log by remember { mutableStateOf(listOf<String>()) }
    var calibrating by remember { mutableStateOf(false) }

    fun addLog(s: String) { log = (listOf(s) + log).take(6) }

    remember(mode) { codec.reset(); link.resetReceiver(); true }

    link.onRun = { level, dur ->
        val out = codec.pushRun(level, dur, symbolMs.toLong() * 1_000_000L)
        if (out.isNotEmpty()) received += out
    }
    link.onLog = { addLog(it) }
    link.symbolMs = symbolMs.toLong()

    val stats by link.stats.collectAsState()
    val trace by link.trace.collectAsState()
    val sending by link.txActive.collectAsState()

    val previewView = remember { PreviewView(context) }

    DisposableEffect(hasPermission) {
        var provider: ProcessCameraProvider? = null
        if (hasPermission) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    provider = future.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setTargetResolution(Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(executor) { proxy ->
                        try {
                            val plane = proxy.planes[0]
                            val buf = plane.buffer
                            val rs = plane.rowStride
                            val ps = plane.pixelStride
                            val w = proxy.width
                            val h = proxy.height
                            val cx = w / 2
                            val cy = h / 2
                            val r1 = minOf(w, h) / 12      // aiming box
                            val r2 = minOf(w, h) / 5       // background annulus
                            val step = max(1, r2 / 16)
                            var sIn = 0.0; var nIn = 0
                            var sOut = 0.0; var nOut = 0
                            var y = cy - r2
                            while (y < cy + r2) {
                                if (y in 0 until h) {
                                    val rowOff = y * rs
                                    var x = cx - r2
                                    while (x < cx + r2) {
                                        if (x in 0 until w) {
                                            val v = (buf.get(rowOff + x * ps).toInt() and 0xFF) / 255.0
                                            if (abs(x - cx) <= r1 && abs(y - cy) <= r1) { sIn += v; nIn++ }
                                            else { sOut += v; nOut++ }
                                        }
                                        x += step
                                    }
                                }
                                y += step
                            }
                            if (nIn > 0 && nOut > 0) {
                                link.onFrame(sIn / nIn, sOut / nOut, System.nanoTime())
                            }
                        } catch (e: Throwable) {
                            // frame dropped
                        } finally {
                            proxy.close()
                        }
                    }
                    provider?.unbindAll()
                    val cam = provider?.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                    )
                    link.control = cam?.cameraControl
                    // Auto-exposure hunting would erase the signal every time either torch fires,
                    // so pin the exposure as low as the device allows.
                    val range = cam?.cameraInfo?.exposureState?.exposureCompensationRange
                    if (range != null) cam?.cameraControl?.setExposureCompensationIndex(range.lower)
                    link.start()
                } catch (e: Throwable) {
                    // camera unavailable
                }
            }, ContextCompat.getMainExecutor(context))
        }
        onDispose {
            link.stop()
            link.control = null
            provider?.unbindAll()
        }
    }

    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    if (!hasPermission) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            SectionCard {
                Text("Multitool needs the camera to receive light, and to hold the torch on while receiving.")
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Grant camera access")
                }
            }
        }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        // ── viewfinder with the aiming reticle ──
        Box(
            Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black)
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                val r1 = minOf(size.width, size.height) / 6f
                val r2 = minOf(size.width, size.height) / 2.5f
                val c = Offset(size.width / 2, size.height / 2)
                drawRect(
                    color = accent,
                    topLeft = Offset(c.x - r1, c.y - r1),
                    size = androidx.compose.ui.geometry.Size(r1 * 2, r1 * 2),
                    style = Stroke(width = 2f)
                )
                drawCircle(accent.copy(alpha = 0.25f), radius = r2, center = c, style = Stroke(width = 1f))
            }
            Text(
                if (sending) "TRANSMITTING" else "LISTENING",
                style = Mono.labelMedium,
                color = if (sending) accent else Color.White.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp)
            )
            Text(
                stats.duplexActive.name,
                style = Mono.labelMedium,
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
            )
        }
        SmallNote("Point the box at the other phone's torch. Both phones can transmit and receive at once.")

        // ── live signal after echo removal ──
        SectionCard("Signal") {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(t.soft)
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    if (trace.size < 2) return@Canvas
                    var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
                    for (v in trace) { if (v < mn) mn = v; if (v > mx) mx = v }
                    val span = max(1e-4f, mx - mn)
                    val dx = size.width / (trace.size - 1)
                    for (i in 0 until trace.size - 1) {
                        val y0 = size.height - (trace[i] - mn) / span * size.height
                        val y1 = size.height - (trace[i + 1] - mn) / span * size.height
                        drawLine(accent, Offset(i * dx, y0), Offset((i + 1) * dx, y1), strokeWidth = 2f)
                    }
                }
            }
            Text(
                "contrast %.3f · echo gain %.3f · residual %.0f%% · %.0f fps"
                    .format(stats.contrast, stats.echoGain, stats.echoResidual * 100, stats.fps),
                style = Mono.label, color = t.fg40
            )
        }

        // ── send ──
        SectionCard("Send") {
            OutlinedTextField(
                value = outgoing,
                onValueChange = { outgoing = it },
                label = { Text("Message") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.fillMaxWidth()
            )
            val symbols = if (outgoing.isBlank()) 0 else codec.symbolCount(outgoing)
            val seconds = symbols * symbolMs / 1000f
            Text(
                "$symbols symbols · %.1f s at %d ms".format(seconds, symbolMs.toInt()),
                style = Mono.label, color = t.fg40
            )
            if (mode == Mode.FAST) {
                val bad = (codec as? FastCodec)?.unsupportedCount(outgoing) ?: 0
                if (bad > 0) SmallNote("$bad character(s) aren't in the fast alphabet and will become spaces.")
            }
            if (mode == Mode.MORSE && outgoing.isNotBlank()) {
                Text(
                    (codec as MorseCodec).pattern(outgoing).take(120),
                    style = Mono.label, color = accent
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (outgoing.isNotBlank()) {
                            link.enqueue(codec.encode(outgoing))
                            addLog("queued ${outgoing.length} chars")
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(if (sending) "Queue" else "Send") }
                OutlinedButton(onClick = { link.clearQueue(); addLog("queue cleared") }) { Text("Stop") }
            }
        }

        // ── receive ──
        SectionCard("Received") {
            Text(
                received.ifBlank { "—" },
                style = MaterialTheme.typography.bodyLarge,
                color = if (received.isBlank()) t.fg30 else t.fg
            )
            if (mode == Mode.FAST) {
                val s = (codec as? FastCodec)?.lastStatus.orEmpty()
                if (s.isNotEmpty()) Text(s, style = Mono.label, color = t.fg40)
            }
            if (mode == Mode.UART) {
                val e = (codec as? UartCodec)?.errors ?: 0
                if (e > 0) Text("$e framing errors", style = Mono.label, color = t.fg40)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { received = "" }) { Text("Clear") }
                OutlinedButton(onClick = {
                    codec.reset(); link.resetReceiver(); addLog("receiver reset")
                }) { Text("Resync") }
            }
        }

        // ── link ──
        SectionCard("Link") {
            Text("Symbol period ${symbolMs.toInt()} ms", style = Mono.label, color = t.fg60)
            Slider(
                value = symbolMs,
                onValueChange = { symbolMs = it },
                valueRange = 60f..400f,
                steps = 16
            )
            SmallNote("Both phones must use the same period. Lower is faster; too low and jitter eats the bit.")

            Text(
                if (stats.calibrated)
                    "measured latency %.0f ms · jitter %.1f ms".format(stats.latencyMs, stats.jitterMs)
                else "not calibrated",
                style = Mono.label, color = t.fg40
            )
            OutlinedButton(
                enabled = !calibrating,
                onClick = {
                    calibrating = true
                    addLog("calibrating — hold still, torch will blink")
                    link.calibrate { lat, jit, amp ->
                        calibrating = false
                        addLog("latency %.0f ms · jitter %.1f ms · echo %.3f".format(lat, jit, amp))
                    }
                }
            ) { Text(if (calibrating) "Calibrating…" else "Calibrate this phone") }
            SmallNote("Flashes a known pattern and watches its own reflection to measure this handset's real torch latency and jitter — that sets the floor for the symbol period.")
        }

        if (log.isNotEmpty()) {
            SectionCard("Log") {
                log.forEach { Text(it, style = Mono.label, color = t.fg50) }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
