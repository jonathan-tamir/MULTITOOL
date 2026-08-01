package com.jonathan.multitool.feature.drone

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.jonathan.multitool.core.data.SettingsStore
import com.jonathan.multitool.core.drone.DroneEngine
import com.jonathan.multitool.core.drone.DroneModel
import com.jonathan.multitool.core.drone.Featurizer
import com.jonathan.multitool.ui.LocalHaptics
import com.jonathan.multitool.ui.SectionCard
import com.jonathan.multitool.ui.SmallNote
import com.jonathan.multitool.ui.theme.LocalAccent
import com.jonathan.multitool.ui.theme.LocalShell
import com.jonathan.multitool.ui.theme.Mono
import com.jonathan.multitool.ui.theme.oklch
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Single-node acoustic drone detector: 1 s windows at 16 kHz, 192-dim pooled log-mel features,
 * MLP from assets/model.json, then 3-of-last-4 temporal consensus before it calls a detection.
 * The launch self-test compares the Kotlin featurizer against the Python reference value, so a
 * broken DSP port shows up as FAIL instead of quietly degrading recall.
 */
@Composable
fun DroneScreen(settings: SettingsStore) {
    val context = LocalContext.current
    val accent = LocalAccent.current
    val t = LocalShell.current
    val alert = oklch(if (t.dark) 0.70f else 0.55f, 0.19f, 25f)
    val clock = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    val haptics = LocalHaptics.current

    val events = remember { mutableStateListOf<String>() }
    fun log(msg: String) {
        events.add(0, "${clock.format(Date())}  $msg")
        while (events.size > 60) events.removeAt(events.size - 1)
    }

    val model = remember {
        runCatching { DroneModel.fromAssets(context) }
            .onFailure { log("model load FAILED: ${it.message}") }
            .getOrNull()
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (!granted) log("microphone permission denied")
    }

    val results = remember { MutableStateFlow(0f to false) }
    val engine = remember(model) {
        model?.let { m -> DroneEngine(m) { p, d -> results.value = p to d } }
    }
    var listening by remember { mutableStateOf(false) }
    val (prob, detection) = results.collectAsState().value

    // one-shot: model summary + DSP self-test against the Python reference
    LaunchedEffect(model) {
        val m = model ?: return@LaunchedEffect
        log("model loaded: in=${m.nIn} hid=${m.nHid} thr=%.2f".format(m.threshold))
        val n = 16000
        val x = FloatArray(n) {
            (0.6 * sin(2 * PI * 180 * it / n) +
                0.3 * sin(2 * PI * 360 * it / n) +
                0.2 * sin(2 * PI * 95 * it / n)).toFloat()
        }
        var s = 0f
        for (v in Featurizer().extract(x)) s += v
        val expected = -1176.30f
        val ok = abs(s - expected) < 5f
        log("selftest featsum=%.2f (exp %.2f) -> %s".format(s, expected, if (ok) "PASS" else "FAIL"))
    }

    LaunchedEffect(detection) {
        if (detection) {
            haptics.alert()
            log("DETECTION  p=%.2f (consensus)".format(prob))
        }
    }

    DisposableEffect(engine) {
        onDispose {
            engine?.stop()
            listening = false
        }
    }

    val stateLabel = when {
        detection -> "DRONE DETECTED"
        listening -> "LISTENING"
        else -> "IDLE"
    }
    val stateColor = when {
        detection -> alert
        listening -> accent
        else -> t.fg30
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---- state badge ----
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(stateColor.copy(alpha = if (detection) 0.22f else 0.12f))
                .padding(vertical = 18.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(stateLabel, style = Mono.code, color = stateColor)
        }

        // ---- probability + meter ----
        SectionCard("Rotor likelihood") {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    if (listening) "${(prob * 100).toInt()}%" else "—",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = t.fg
                )
                Spacer(Modifier.fillMaxWidth(0.06f))
                Text(
                    "thr %.2f".format(model?.threshold ?: 0f),
                    style = Mono.label,
                    color = t.fg40,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(t.soft)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(if (listening) prob.coerceIn(0f, 1f) else 0f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (detection) alert else accent)
                )
            }
            SmallNote("Recall-first by design: a single node is meant to over-report. Precision comes from agreeing with other nodes.")
        }

        // ---- control ----
        if (model == null) {
            SectionCard { Text("Model asset missing — detector unavailable.", color = MaterialTheme.colorScheme.error) }
        } else if (!hasPermission) {
            SectionCard {
                Text("Multitool needs microphone access to listen for rotors.")
                Button(onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                    Text("Grant microphone access")
                }
            }
        } else {
            Button(
                onClick = {
                    if (listening) {
                        engine?.stop(); listening = false
                        results.value = 0f to false
                        log("listening stopped")
                    } else {
                        engine?.start(); listening = true
                        log("listening started")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (listening) "STOP LISTENING" else "START LISTENING") }
        }

        // ---- event log ----
        SectionCard("Event log") {
            if (events.isEmpty()) SmallNote("No events yet.")
            events.forEach { line ->
                Text(line, style = Mono.label, color = t.fg60)
            }
        }
    }
}
