package com.jonathan.multitool.feature.audio

import com.jonathan.multitool.ui.*
import com.jonathan.multitool.core.util.stamp

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.jonathan.multitool.core.audio.AudioEngine
import com.jonathan.multitool.core.audio.AudioFileProcessor
import com.jonathan.multitool.core.audio.TonePlayer
import com.jonathan.multitool.core.audio.WavIo
import com.jonathan.multitool.core.data.SettingsStore
import com.jonathan.multitool.core.dsp.Biquad
import com.jonathan.multitool.core.dsp.Fft
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun sliderToFreq(t: Float): Double = 40.0 * (18000.0 / 40.0).pow(t.toDouble())
private fun freqToSlider(f: Double): Float = (ln(f / 40.0) / ln(18000.0 / 40.0)).toFloat().coerceIn(0f, 1f)

@Composable
fun AudioScreen(
    settings: SettingsStore,
    engine: AudioEngine,
    startMode: Int = 0,
    showChrome: Boolean = true
) {
    val context = LocalContext.current
    val accent = com.jonathan.multitool.ui.theme.LocalAccent.current
    var mode by rememberSaveable { mutableStateOf(startMode) }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { hasPermission = it }

    var paused by rememberSaveable { mutableStateOf(false) }
    var windowMs by rememberSaveable { mutableStateOf(93f) }
    var probeText by rememberSaveable { mutableStateOf("440") }

    val spec by engine.spectrum.collectAsState()
    val postSpec by engine.postSpectrum.collectAsState()

    LaunchedEffect(windowMs) { engine.windowSeconds = windowMs / 1000.0 }
    LaunchedEffect(settings.smoothing.value) { engine.smoothing = settings.smoothing.value.toDouble() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, hasPermission, paused) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (hasPermission && !paused) engine.start()
                Lifecycle.Event.ON_PAUSE -> engine.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (hasPermission && !paused) engine.start() else engine.stop()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            engine.monitor = false
            engine.stop()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (showChrome) {
                    Text("Audio Spectrum", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Live microphone analysis",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (hasPermission) {
                IconButton(onClick = { paused = !paused }) {
                    Icon(
                        if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = if (paused) "Resume" else "Pause",
                        tint = accent
                    )
                }
            }
        }

        if (showChrome) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("Analyze", "Filter", "Record", "Tools").forEachIndexed { i, label ->
                    ChoiceChip(label, mode == i, accent) { mode = i }
                }
            }
        }

        if (!hasPermission) {
            SectionCard {
                Text("Multitool needs microphone access for live audio analysis.")
                Button(onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                    Text("Grant microphone access")
                }
            }
            return@Column
        }

        when (mode) {
            0 -> AnalyzeAudioSection(settings, engine, spec, windowMs, { windowMs = it }, probeText, { probeText = it })
            1 -> FilterAudioSection(settings, engine, spec, postSpec)
            2 -> RecordAudioSection(settings, engine)
            else -> ToolsAudioSection(settings, engine, spec)
        }
        Spacer(Modifier.height(4.dp))
    }
}

// ------------------------------ Analyze ------------------------------

@Composable
private fun AnalyzeAudioSection(
    settings: SettingsStore,
    engine: AudioEngine,
    spec: AudioEngine.Spectrum?,
    windowMs: Float,
    onWindowMs: (Float) -> Unit,
    probeText: String,
    onProbeText: (String) -> Unit
) {
    val accent = com.jonathan.multitool.ui.theme.LocalAccent.current
    val probeFreq = probeText.toDoubleOrNull()
    SectionCard {
        SpectrumPlot(
            mags = spec?.mags,
            binHz = spec?.binHz ?: 0.0,
            logAxis = settings.logFreqAxis.value,
            showGrid = settings.showGrid.value,
            accent = accent,
            probeFreq = probeFreq,
            modifier = Modifier
                .fillMaxWidth()
                .height(230.dp)
        )
    }
    SectionCard("Time window") {
        val binHz = spec?.binHz
        Text(
            String.format("%.0f ms", windowMs) +
                (if (binHz != null && binHz > 0) String.format("  ·  resolution ≈ %.1f Hz", binHz) else ""),
            fontWeight = FontWeight.SemiBold
        )
        Slider(value = windowMs, onValueChange = onWindowMs, valueRange = 20f..1000f)
        SmallNote("Longer window → finer frequency resolution, slower response.")
    }
    SectionCard("Prominent frequencies") {
        val peaks = spec?.let { s -> Fft.findPeaks(s.mags, s.binHz, settings.peakCount.value) } ?: emptyList()
        PeakChips(peaks, accent)
    }
    run {
        var participation: Double? = null
        var detail: String? = null
        if (probeFreq != null && spec != null && probeFreq > 0 && spec.totalPower > 0) {
            val bin = (probeFreq / spec.binHz).roundToInt()
            if (bin in 1 until spec.mags.size) {
                var power = 0.0
                for (b in (bin - 1)..(bin + 1)) {
                    if (b in 1 until spec.mags.size) power += spec.mags[b] * spec.mags[b]
                }
                participation = (power / spec.totalPower).coerceIn(0.0, 1.0)
                detail = String.format(
                    "%.1f dB at %s (nearest bin %s)",
                    20.0 * log10(spec.mags[bin] + 1e-12),
                    formatHz(probeFreq),
                    formatHz(bin * spec.binHz)
                )
            }
        }
        ProbeCard(
            title = "Frequency probe",
            unitLabel = "Frequency (Hz)",
            text = probeText,
            onTextChange = onProbeText,
            participation = participation,
            detail = detail,
            accent = accent
        )
    }
}

// ------------------------------ Filter ------------------------------

@Composable
private fun FilterAudioSection(
    settings: SettingsStore,
    engine: AudioEngine,
    spec: AudioEngine.Spectrum?,
    postSpec: AudioEngine.Spectrum?
) {
    val accent = com.jonathan.multitool.ui.theme.LocalAccent.current
    var filterType by rememberSaveable { mutableStateOf(Biquad.TYPE_NONE) }
    var freqT by rememberSaveable { mutableStateOf(freqToSlider(1000.0)) }
    var q by rememberSaveable { mutableStateOf(1.5f) }
    var monitor by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(filterType, freqT, q, monitor) {
        engine.filterType = filterType
        engine.filterFreq = sliderToFreq(freqT)
        engine.filterQ = q.toDouble()
        engine.monitor = monitor
    }
    DisposableEffect(Unit) {
        onDispose { engine.monitor = false }
    }

    SectionCard("Live filter") {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                Biquad.TYPE_NONE to "Off",
                Biquad.TYPE_LOWPASS to "Low-pass",
                Biquad.TYPE_HIGHPASS to "High-pass",
                Biquad.TYPE_BANDPASS to "Band-pass",
                Biquad.TYPE_NOTCH to "Notch"
            ).forEach { (t, label) ->
                ChoiceChip(label, filterType == t, accent) { filterType = t }
            }
        }
        if (filterType != Biquad.TYPE_NONE) {
            Text(
                (if (filterType == Biquad.TYPE_NOTCH) "Remove " else "") + formatHz(sliderToFreq(freqT)),
                fontWeight = FontWeight.SemiBold
            )
            Slider(value = freqT, onValueChange = { freqT = it }, valueRange = 0f..1f)
            Text(String.format("Sharpness (Q): %.1f", q))
            Slider(value = q, onValueChange = { q = it }, valueRange = 0.5f..8f)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Monitor through speaker/headphones", style = MaterialTheme.typography.bodyMedium)
                SmallNote("Use headphones — speaker + mic = feedback loop!")
            }
            Switch(
                checked = monitor,
                onCheckedChange = { monitor = it },
                colors = SwitchDefaults.colors(checkedTrackColor = accent)
            )
        }
    }
    SectionCard("Input") {
        SpectrumPlot(
            mags = spec?.mags,
            binHz = spec?.binHz ?: 0.0,
            logAxis = settings.logFreqAxis.value,
            showGrid = settings.showGrid.value,
            accent = accent.copy(alpha = 0.5f),
            probeFreq = if (filterType != Biquad.TYPE_NONE) sliderToFreq(freqT) else null,
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
        )
    }
    SectionCard("Output") {
        if (filterType == Biquad.TYPE_NONE) {
            SmallNote("Filter is off — output equals input.")
        } else {
            SpectrumPlot(
                mags = postSpec?.mags,
                binHz = postSpec?.binHz ?: 0.0,
                logAxis = settings.logFreqAxis.value,
                showGrid = settings.showGrid.value,
                accent = accent,
                probeFreq = sliderToFreq(freqT),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
            )
        }
    }
}

// ------------------------------ Record ------------------------------

@Composable
private fun RecordAudioSection(settings: SettingsStore, engine: AudioEngine) {
    val context = LocalContext.current
    val accent = com.jonathan.multitool.ui.theme.LocalAccent.current
    val scope = rememberCoroutineScope()
    val recordedSeconds by engine.recordedSeconds.collectAsState()
    var isRecording by remember { mutableStateOf(engine.isRecording) }
    var savedTo by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    // file cleaning
    var cleanUri by remember { mutableStateOf<Uri?>(null) }
    var cleaning by remember { mutableStateOf(false) }
    var cleanProgress by remember { mutableStateOf(0f) }
    var cleanResult by remember { mutableStateOf<String?>(null) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        if (it != null) { cleanUri = it; cleanResult = null }
    }

    SectionCard("Record from microphone") {
        SmallNote("Records what you hear in the Filter tab — set up a filter there first, or record raw with the filter off.")
        if (isRecording) {
            Text(
                String.format("Recording… %.1f s", recordedSeconds),
                fontWeight = FontWeight.SemiBold,
                color = accent
            )
        }
        Button(
            onClick = {
                if (isRecording) {
                    val pcm = engine.endRecording()
                    isRecording = false
                    if (pcm.isNotEmpty()) {
                        saving = true
                        scope.launch {
                            savedTo = try {
                                withContext(Dispatchers.Default) {
                                    WavIo.savePcm(context, pcm, 44100, 1, "JSA_rec_${stamp()}.wav")
                                }
                            } catch (t: Throwable) {
                                "Save failed"
                            }
                            saving = false
                        }
                    }
                } else {
                    savedTo = null
                    engine.beginRecording()
                    isRecording = true
                }
            },
            enabled = !saving
        ) {
            Text(if (isRecording) "Stop & save WAV" else "Start recording")
        }
        if (saving) SmallNote("Saving…")
        val s = savedTo
        if (s != null) SmallNote("Saved: $s")
    }

    SectionCard("Clean an audio file") {
        SmallNote("Pick any audio file, run it through the current Filter settings (e.g. a notch at hum frequency), and save the result as WAV.")
        Button(onClick = { filePicker.launch("audio/*") }, enabled = !cleaning) {
            Text(if (cleanUri == null) "Pick audio file" else "Pick another file")
        }
        if (engine.filterType == Biquad.TYPE_NONE) {
            SmallNote("Note: the filter is currently OFF — the file would be saved unchanged. Set a filter in the Filter tab.")
        } else {
            SmallNote(
                "Will apply: " + when (engine.filterType) {
                    Biquad.TYPE_LOWPASS -> "low-pass"
                    Biquad.TYPE_HIGHPASS -> "high-pass"
                    Biquad.TYPE_BANDPASS -> "band-pass"
                    else -> "notch"
                } + " at " + formatHz(engine.filterFreq)
            )
        }
        val u = cleanUri
        Button(
            onClick = {
                if (u == null) return@Button
                cleaning = true
                cleanProgress = 0f
                cleanResult = null
                scope.launch {
                    cleanResult = try {
                        withContext(Dispatchers.Default) {
                            AudioFileProcessor.process(
                                context, u, engine.filterType, engine.filterFreq, engine.filterQ
                            ) { p -> cleanProgress = p }
                        }.let { "Saved: $it" }
                    } catch (t: Throwable) {
                        "Failed: ${t.message ?: "unknown error"}"
                    }
                    cleaning = false
                }
            },
            enabled = u != null && !cleaning
        ) {
            Text(if (cleaning) "Processing…" else "Process file")
        }
        if (cleaning) {
            LinearProgressIndicator(
                progress = { cleanProgress },
                modifier = Modifier.fillMaxWidth(),
                color = accent
            )
        }
        val r = cleanResult
        if (r != null) SmallNote(r)
    }
}

// ------------------------------ Tools ------------------------------

@Composable
private fun ToolsAudioSection(
    settings: SettingsStore,
    engine: AudioEngine,
    spec: AudioEngine.Spectrum?
) {
    val accent = com.jonathan.multitool.ui.theme.LocalAccent.current
    val tone = remember { TonePlayer() }
    var toneOn by remember { mutableStateOf(false) }
    var toneT by rememberSaveable { mutableStateOf(freqToSlider(440.0)) }
    var toneAmp by rememberSaveable { mutableStateOf(0.4f) }

    LaunchedEffect(toneT, toneAmp) {
        tone.freq = sliderToFreq(toneT)
        tone.amplitude = toneAmp.toDouble()
    }
    DisposableEffect(Unit) {
        onDispose { tone.stop() }
    }

    SectionCard("Tone generator") {
        Text(formatHz(sliderToFreq(toneT)) + "  ·  " + Fft.noteName(sliderToFreq(toneT)), fontWeight = FontWeight.SemiBold)
        Slider(value = toneT, onValueChange = { toneT = it }, valueRange = 0f..1f)
        Text(String.format("Volume: %.0f%%", toneAmp * 100))
        Slider(value = toneAmp, onValueChange = { toneAmp = it }, valueRange = 0f..1f)
        Button(onClick = {
            if (toneOn) { tone.stop(); toneOn = false }
            else { tone.start(); toneOn = true }
        }) {
            Text(if (toneOn) "Stop tone" else "Play tone")
        }
        SmallNote("Play a tone and watch it appear on the Analyze tab — or probe it exactly.")
    }

    SectionCard("Tuner") {
        val dom = spec?.let { Fft.dominantFrequency(it.mags, it.binHz, 50.0) }
        val nc = dom?.let { Fft.noteAndCents(it) }
        if (dom != null && nc != null && spec != null) {
            val bin = (dom / spec.binHz).roundToInt().coerceIn(0, spec.mags.size - 1)
            val strong = 20.0 * log10(spec.mags[bin] + 1e-12) > -70.0
            if (strong) {
                Text(
                    nc.first,
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (abs(nc.second) < 8) accent else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Text(
                    String.format("%.1f Hz  ·  %+.0f cents", dom, nc.second),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                CentsMeter(nc.second, accent)
            } else {
                SmallNote("Listening… play or sing a note.")
            }
        } else {
            SmallNote("Listening… play or sing a note.")
        }
    }
}

@Composable
private fun CentsMeter(cents: Double, accent: androidx.compose.ui.graphics.Color) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val center = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
    ) {
        val w = size.width
        val h = size.height
        val cy = h / 2
        drawLine(track, Offset(0f, cy), Offset(w, cy), strokeWidth = 8f)
        drawLine(center, Offset(w / 2, cy - h * 0.35f), Offset(w / 2, cy + h * 0.35f), strokeWidth = 2f)
        val t = (cents.coerceIn(-50.0, 50.0) / 50.0).toFloat()
        val x = w / 2 + t * (w / 2 - 12f)
        drawCircle(accent, radius = 12f, center = Offset(x, cy))
    }
}
