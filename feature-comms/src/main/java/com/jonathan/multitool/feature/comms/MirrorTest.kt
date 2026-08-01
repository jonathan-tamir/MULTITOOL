package com.jonathan.multitool.feature.comms

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.jonathan.multitool.core.data.SettingsStore
import com.jonathan.multitool.ui.LocalHaptics
import com.jonathan.multitool.ui.SectionCard
import com.jonathan.multitool.ui.SmallNote
import com.jonathan.multitool.ui.theme.LocalAccent
import com.jonathan.multitool.ui.theme.LocalShell
import com.jonathan.multitool.ui.theme.Mono
import com.jonathan.multitool.ui.theme.oklch
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Short probe for the speed sweep — every extra character costs seconds at these rates. */
private const val PROBE = "AB12"
private const val TEST_TEXT = "MIRROR OK"

/** Symbol periods tried fastest-last, so the sweep stops at the first failure. */
private val LADDER = listOf(250L, 180L, 130L, 100L, 80L)

private class Stage(val name: String) {
    var state by mutableStateOf(0)       // 0 pending, 1 running, 2 pass, 3 fail
    var detail by mutableStateOf("")
}

/**
 * Point the phone at a mirror and press one button.
 *
 * Loopback is the one case where the receiver's own design works against it: the echo canceller
 * exists to subtract anything correlated with what we're transmitting, and in a mirror the return
 * *is* what we're transmitting, perfectly correlated. So mirror mode turns cancellation off, pins
 * full duplex on (the take-turns fallback would mute the very slot the reflection arrives in), and
 * reads the aiming box raw.
 *
 * It also removes the two things that were yours to get wrong: nothing to aim at another phone,
 * and no symbol period to guess — the sweep finds the fastest one that round-trips intact and
 * saves it for the other tools.
 */
@Composable
fun MirrorTestScreen(settings: SettingsStore) {
    val context = LocalContext.current
    val accent = LocalAccent.current
    val t = LocalShell.current
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()
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
    val codec = remember { UartCodec() }
    val decoded = remember { StringBuffer() }
    var running by remember { mutableStateOf(false) }
    var verdict by remember { mutableStateOf("") }
    var bestPeriod by remember { mutableStateOf(0L) }

    val stages = remember {
        mutableStateListOf(
            Stage("Torch visible in frame"),
            Stage("Timing measured"),
            Stage("Fastest reliable speed"),
            Stage("Round trip intact")
        )
    }

    link.onRun = { level, dur ->
        decoded.append(codec.pushRun(level, dur, link.symbolMs * 1_000_000L))
    }

    val stats by link.stats.collectAsState()
    val trace by link.trace.collectAsState()
    val previewView = remember { PreviewView(context) }
    BindTorchCamera(link, previewView, hasPermission)

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

    suspend fun sendAndRead(text: String, periodMs: Long): String {
        link.symbolMs = periodMs
        link.clearQueue()
        codec.reset()
        link.resetReceiver()
        decoded.setLength(0)
        delay(400)
        val bits = codec.encode(text)
        link.enqueue(bits)
        delay(bits.size * periodMs + 1200)
        return decoded.toString().trim()
    }

    fun runAll() {
        running = true
        verdict = ""
        stages.forEach { it.state = 0; it.detail = "" }
        scope.launch {
            // ── 1. is the torch even coming back? ──
            val s1 = stages[0]
            s1.state = 1
            link.symbolMs = 300
            link.resetReceiver()
            link.enqueue(List(12) { it % 2 == 0 })
            var peak = 0.0
            repeat(40) {
                delay(100)
                if (stats.contrast > peak) peak = stats.contrast
            }
            s1.detail = "swing %.3f".format(peak)
            if (peak < TorchLink.MIN_CONTRAST * 2) {
                s1.state = 3
                verdict = "The camera can't see the torch. Move closer to the mirror, dim the room, " +
                    "and put the reflected torch inside the box."
                running = false
                haptics.alert()
                return@launch
            }
            s1.state = 2
            haptics.tap()

            // ── 2. this handset's own latency and jitter ──
            val s2 = stages[1]
            s2.state = 1
            var done = false
            link.calibrate { lat, jit, _ ->
                s2.detail = "latency %.0f ms · jitter %.1f ms".format(lat, jit)
                s2.state = 2
                done = true
            }
            var waited = 0
            while (!done && waited < 90) { delay(100); waited++ }
            if (!done) { s2.state = 3; s2.detail = "timed out" }
            haptics.tap()

            // ── 3. sweep down until it stops decoding ──
            val s3 = stages[2]
            s3.state = 1
            var best = 0L
            for (p in LADDER) {
                s3.detail = "trying $p ms…"
                val got = sendAndRead(PROBE, p)
                if (got == PROBE) { best = p; s3.detail = "$p ms ok" } else break
            }
            if (best == 0L) {
                s3.state = 3
                s3.detail = "nothing decoded, even at 250 ms"
                verdict = "Light is visible but nothing decodes. Usually the reflection is saturating " +
                    "the sensor — back off from the mirror or aim slightly off-centre."
                running = false
                haptics.alert()
                return@launch
            }
            bestPeriod = best
            s3.state = 2
            s3.detail = "$best ms"
            haptics.tap()

            // ── 4. confirm at the chosen speed ──
            val s4 = stages[3]
            s4.state = 1
            val got = sendAndRead(TEST_TEXT, best)
            if (got == TEST_TEXT) {
                s4.state = 2
                s4.detail = "\"$got\""
                settings.setLinkSymbolMs(best.toInt())
                verdict = "Link works. $best ms saved as the symbol period for the other tools."
                haptics.launch()
            } else {
                s4.state = 3
                s4.detail = "got \"$got\""
                verdict = "Decoded at $best ms during the sweep but not on the confirm pass — the link " +
                    "is marginal. Try one step slower."
                haptics.alert()
            }
            running = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black)
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                val r = minOf(size.width, size.height) / 6f
                val c = Offset(size.width / 2, size.height / 2)
                drawRect(
                    color = accent,
                    topLeft = Offset(c.x - r, c.y - r),
                    size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                    style = Stroke(width = 2f)
                )
            }
            Text(
                "LOOPBACK",
                style = Mono.labelMedium,
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp)
            )
        }
        SmallNote("Hold the phone facing a mirror so the reflected torch sits inside the box. A dim room helps a lot.")

        Button(
            onClick = { runAll() },
            enabled = !running,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (running) "Testing…" else "Run mirror test") }

        SectionCard("Checks") {
            stages.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val mark = when (s.state) { 2 -> "✓"; 3 -> "✕"; 1 -> "…"; else -> "·" }
                    val col = when (s.state) { 2 -> good; 3 -> bad; 1 -> accent; else -> t.fg30 }
                    Text(mark, style = Mono.labelMedium, color = col)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            s.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (s.state == 0) t.fg40 else t.fg
                        )
                        if (s.detail.isNotEmpty()) {
                            Text(s.detail, style = Mono.label, color = t.fg40)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }

        if (verdict.isNotEmpty()) {
            SectionCard {
                Text(verdict, style = MaterialTheme.typography.bodyMedium, color = t.fg)
            }
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
                    val span = kotlin.math.max(1e-4f, mx - mn)
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

        if (bestPeriod > 0) {
            OutlinedButton(onClick = { settings.setLinkSymbolMs(bestPeriod.toInt()) }) {
                Text("Use $bestPeriod ms everywhere")
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
