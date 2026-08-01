package com.jonathan.multitool.feature.comms

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.jonathan.multitool.core.data.SettingsStore
import com.jonathan.multitool.ui.ChoiceChip
import com.jonathan.multitool.ui.LocalHaptics
import com.jonathan.multitool.ui.SectionCard
import com.jonathan.multitool.ui.SmallNote
import com.jonathan.multitool.ui.theme.LocalAccent
import com.jonathan.multitool.ui.theme.LocalShell
import com.jonathan.multitool.ui.theme.Mono
import com.jonathan.multitool.ui.theme.oklch
import kotlin.math.max

/**
 * Loopback: flash at a mirror and read your own message back.
 *
 * Loopback is the one case where the receiver's normal design works against it — the echo canceller
 * exists to subtract whatever correlates with our own transmission, and here the return *is* our own
 * transmission. So this screen runs the link with cancellation off, the annulus subtraction off (a
 * reflection is a concentrated spot, not diffuse backscatter), and full duplex pinned.
 *
 * You pick the message and the codec; it shows you exactly what came back.
 */
@Composable
fun MirrorTestScreen(settings: SettingsStore) {
    val context = LocalContext.current
    val accent = LocalAccent.current
    val t = LocalShell.current
    val haptics = LocalHaptics.current
    val good = oklch(if (t.dark) 0.80f else 0.55f, 0.14f, 150f)
    val bad = oklch(if (t.dark) 0.70f else 0.55f, 0.19f, 25f)

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { hasPermission = it }

    val link = remember { TorchLink().apply { loopback = true } }
    var modeIdx by remember { mutableStateOf(1) }              // default: ASCII, the strictest
    val mode = Mode.values()[modeIdx]
    val codec = remember(mode) { codecFor(mode) }

    var outgoing by remember { mutableStateOf("MIRROR OK") }
    var sent by remember { mutableStateOf("") }
    var received by remember { mutableStateOf("") }
    var symbolMs by remember { mutableStateOf(settings.linkSymbolMs.value.toFloat()) }

    link.onRun = { level, dur ->
        val out = codec.pushRun(level, dur, symbolMs.toLong() * 1_000_000L)
        if (out.isNotEmpty()) received += out
    }
    link.symbolMs = symbolMs.toLong()

    val stats by link.stats.collectAsState()
    val trace by link.trace.collectAsState()
    val sending by link.txActive.collectAsState()
    val previewView = remember { PreviewView(context) }
    BindTorchCamera(link, previewView, hasPermission)

    LaunchedEffect(mode) {
        codec.reset(); link.resetReceiver(); received = ""
    }

    if (!hasPermission) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            SectionCard {
                Text("Multitool needs the camera to see the reflection of its own torch.")
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Grant camera access")
                }
            }
        }
        return
    }

    val seen = stats.contrast > TorchLink.MIN_CONTRAST * 2

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(170.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black)
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                val r = minOf(size.width, size.height) / 6f
                val c = Offset(size.width / 2, size.height / 2)
                drawRect(
                    color = if (seen) accent else Color.White.copy(alpha = 0.4f),
                    topLeft = Offset(c.x - r, c.y - r),
                    size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                    style = Stroke(width = 2f)
                )
            }
            Text(
                if (sending) "TRANSMITTING" else "LOOPBACK",
                style = Mono.labelMedium,
                color = if (sending) accent else Color.White.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp)
            )
            Text(
                if (seen) "LIGHT OK · %.2f".format(stats.contrast) else "NO LIGHT",
                style = Mono.labelMedium,
                color = if (seen) good else bad,
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
            )
        }
        SmallNote("Face a mirror so the reflected torch sits inside the box. Dim room, and back off if it saturates.")

        // ── what to send, and how ──
        SectionCard("Message") {
            OutlinedTextField(
                value = outgoing,
                onValueChange = { outgoing = it },
                label = { Text("Text to send") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Mode.values().forEachIndexed { i, m ->
                    ChoiceChip(m.label, modeIdx == i, accent) { modeIdx = i }
                }
            }
            val symbols = if (outgoing.isBlank()) 0 else codec.symbolCount(outgoing)
            Text(
                "$symbols symbols · %.1f s at %d ms".format(symbols * symbolMs / 1000f, symbolMs.toInt()),
                style = Mono.label, color = t.fg40
            )
            if (mode == Mode.MORSE && outgoing.isNotBlank()) {
                Text((codec as MorseCodec).pattern(outgoing).take(120), style = Mono.label, color = accent)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        haptics.emit()
                        codec.reset()
                        link.resetReceiver()
                        link.clearQueue()
                        received = ""
                        sent = outgoing
                        link.enqueue(codec.encode(outgoing))
                    },
                    enabled = outgoing.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) { Text(if (sending) "Sending…" else "Send and watch") }
                OutlinedButton(onClick = {
                    link.clearQueue(); codec.reset(); link.resetReceiver(); received = ""
                }) { Text("Reset") }
            }
        }

        // ── what came back ──
        SectionCard("Round trip") {
            Text("sent", style = Mono.label, color = t.fg40)
            Text(sent.ifBlank { "—" }, style = MaterialTheme.typography.bodyLarge, color = t.fg60)
            Spacer(Modifier.height(8.dp))
            Text("read back by the camera", style = Mono.label, color = t.fg40)
            Text(
                received.ifBlank { "—" },
                style = MaterialTheme.typography.bodyLarge,
                color = if (received.isBlank()) t.fg30 else t.fg
            )
            if (sent.isNotEmpty() && received.isNotEmpty()) {
                val expect = if (mode == Mode.FAST) (codec as FastCodec).sanitize(sent).trim()
                else if (mode == Mode.MORSE) sent.trim().uppercase() else sent
                val got = received.trim()
                val exact = got == expect
                val matched = expect.zip(got).count { it.first == it.second }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (exact) "exact match"
                    else "$matched of ${expect.length} characters match",
                    style = Mono.labelMedium,
                    color = if (exact) good else bad
                )
            }
            if (mode == Mode.FAST) {
                val s = (codec as? FastCodec)?.lastStatus.orEmpty()
                if (s.isNotEmpty()) Text(s, style = Mono.label, color = t.fg40)
            }
            if (mode == Mode.UART) {
                val e = (codec as? UartCodec)?.errors ?: 0
                if (e > 0) Text("$e framing errors", style = Mono.label, color = t.fg40)
            }
        }

        // ── speed ──
        SectionCard("Symbol period") {
            Text("${symbolMs.toInt()} ms", style = Mono.label, color = t.fg60)
            Slider(
                value = symbolMs,
                onValueChange = { symbolMs = it },
                onValueChangeFinished = { settings.setLinkSymbolMs(symbolMs.toInt()) },
                valueRange = 60f..400f,
                steps = 16
            )
            SmallNote("Shared with the other light tools. Walk it down until the round trip stops matching — that's this handset's floor.")
        }

        SectionCard("Live signal") {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
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
                        drawLine(
                            accent,
                            Offset(i * dx, size.height - (trace[i] - mn) / span * size.height),
                            Offset((i + 1) * dx, size.height - (trace[i + 1] - mn) / span * size.height),
                            strokeWidth = 2f
                        )
                    }
                }
            }
            Text(
                "swing %.3f · %.0f fps · echo cancel off".format(stats.contrast, stats.fps),
                style = Mono.label, color = t.fg40
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}
