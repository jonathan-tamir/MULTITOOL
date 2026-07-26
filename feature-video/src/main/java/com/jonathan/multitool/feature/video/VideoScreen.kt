package com.jonathan.multitool.feature.video

import com.jonathan.multitool.ui.*

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.jonathan.multitool.core.data.SettingsStore
import com.jonathan.multitool.core.dsp.Biquad
import com.jonathan.multitool.core.dsp.Fft
import com.jonathan.multitool.core.image.ImageMath
import com.jonathan.multitool.core.video.Mp4Recorder
import com.jonathan.multitool.core.video.VideoTransformer
import java.util.ArrayDeque
import java.util.concurrent.Executors
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class TemporalSpec(
    val mags: DoubleArray,
    val binHz: Double,
    val totalPower: Double,
    val dominantHz: Double?,
    val fps: Double
)

private class VideoParams {
    @Volatile var mode = 0
    @Volatile var filterKind = 0 // 0 lp, 1 hp
    @Volatile var cutoffFrac = 0.25f
    @Volatile var ampFreq = 2.0f
    @Volatile var ampGain = 12f
    @Volatile var accentArgb = -1
}

private class CamState {
    var frameCount = 0
    val bright = ArrayDeque<Double>()
    val times = ArrayDeque<Double>()
    var fpsEst = 30.0
    var lastT = 0.0
    // filter mask cache
    var mask: DoubleArray? = null
    var maskKey = ""
    // motion amp per-pixel state
    var coeffs: DoubleArray? = null
    var coeffKey = ""
    var mx1: FloatArray? = null
    var mx2: FloatArray? = null
    var my1: FloatArray? = null
    var my2: FloatArray? = null
}

@Composable
fun VideoScreen(
    settings: SettingsStore,
    startMode: Int = 0,
    showChrome: Boolean = true
) {
    val context = LocalContext.current
    val accent = com.jonathan.multitool.ui.theme.LocalAccent.current
    val accentArgb = accent.toArgb()
    var mode by rememberSaveable { mutableStateOf(startMode) }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { hasPermission = it }

    var probeText by rememberSaveable { mutableStateOf("2") }
    var fftBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var displayBmp by remember { mutableStateOf<Bitmap?>(null) }
    var temporal by remember { mutableStateOf<TemporalSpec?>(null) }
    var cutoffFrac by rememberSaveable { mutableStateOf(0.25f) }
    var filterKind by rememberSaveable { mutableStateOf(0) }
    var ampFreq by rememberSaveable { mutableStateOf(2f) }
    var ampGain by rememberSaveable { mutableStateOf(12f) }
    var isRecording by remember { mutableStateOf(false) }
    var savedVideo by remember { mutableStateOf<String?>(null) }

    val params = remember { VideoParams() }
    params.mode = mode
    params.filterKind = filterKind
    params.cutoffFrac = cutoffFrac
    params.ampFreq = ampFreq
    params.ampGain = ampGain
    params.accentArgb = accentArgb

    val state = remember { CamState() }
    val recorder = remember { Mp4Recorder(context, 512) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraOn = mode < 3 && hasPermission

    DisposableEffect(cameraOn) {
        var provider: ProcessCameraProvider? = null
        if (cameraOn) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    provider = future.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    @Suppress("DEPRECATION")
                    val analysis = ImageAnalysis.Builder()
                        .setTargetResolution(Size(320, 240))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(executor) { proxy ->
                        try {
                            val plane = proxy.planes[0]
                            val buf = plane.buffer
                            val rowStride = plane.rowStride
                            val pixStride = plane.pixelStride
                            val w = proxy.width
                            val h = proxy.height
                            val n = 128
                            val gray = DoubleArray(n * n)
                            var meanV = 0.0
                            for (yy in 0 until n) {
                                val rowOff = (yy * h / n) * rowStride
                                for (xx in 0 until n) {
                                    val v = (buf.get(rowOff + (xx * w / n) * pixStride).toInt() and 0xFF) / 255.0
                                    gray[yy * n + xx] = v
                                    meanV += v
                                }
                            }
                            meanV /= (n * n).toDouble()
                            state.frameCount++
                            val t = System.nanoTime() / 1e9
                            if (state.lastT > 0) {
                                val dt = t - state.lastT
                                if (dt > 0.001 && dt < 1.0) {
                                    state.fpsEst = 0.95 * state.fpsEst + 0.05 / dt
                                }
                            }
                            state.lastT = t

                            when (params.mode) {
                                0 -> {
                                    if (state.frameCount % 2 == 0) {
                                        val mag = Fft.fft2Magnitude(gray, n, n)
                                        fftBitmap = magnitudeToBitmap(mag, n, n, params.accentArgb)
                                    }
                                    state.times.addLast(t)
                                    state.bright.addLast(meanV)
                                    while (state.bright.size > 512) {
                                        state.bright.removeFirst()
                                        state.times.removeFirst()
                                    }
                                    if (state.bright.size >= 64 && state.frameCount % 5 == 0) {
                                        var m = 64
                                        while (m * 2 <= state.bright.size && m < 256) m *= 2
                                        val bl = state.bright.toDoubleArray()
                                        val tl = state.times.toDoubleArray()
                                        var s = 0.0
                                        for (i in bl.size - m until bl.size) s += bl[i]
                                        val meanB = s / m
                                        val arr = DoubleArray(m) { bl[bl.size - m + it] - meanB }
                                        val dt = tl[tl.size - 1] - tl[tl.size - m]
                                        val fps = if (dt > 0) (m - 1) / dt else 30.0
                                        val mags = Fft.magnitudeSpectrum(arr)
                                        var total = 0.0
                                        for (i in 1 until mags.size) total += mags[i] * mags[i]
                                        val binHz = fps / m
                                        val peaks = Fft.findPeaks(mags, binHz, 3, minFreq = 0.3)
                                        temporal = TemporalSpec(mags, binHz, total, peaks.firstOrNull()?.freq, fps)
                                    }
                                }
                                1 -> {
                                    val key = "${params.filterKind}/${(params.cutoffFrac * 100).toInt()}"
                                    if (state.mask == null || state.maskKey != key) {
                                        val maxR = n / 2.0
                                        val c = (params.cutoffFrac * maxR).toDouble().coerceAtLeast(1.0)
                                        state.mask = if (params.filterKind == 0)
                                            ImageMath.lowpassMask(n, c, 0.02 * maxR)
                                        else ImageMath.highpassMask(n, c, 0.02 * maxR)
                                        state.maskKey = key
                                    }
                                    val mask = state.mask
                                    if (mask != null) {
                                        val re = gray.copyOf()
                                        val im = DoubleArray(n * n)
                                        Fft.fft2(re, im, n, n)
                                        for (i in re.indices) { re[i] *= mask[i]; im[i] *= mask[i] }
                                        Fft.ifft2(re, im, n, n)
                                        val hp = params.filterKind == 1
                                        val px = IntArray(n * n)
                                        for (i in px.indices) {
                                            val v = if (hp) (128.0 + re[i] * 510.0) else re[i] * 255.0
                                            val g = v.toInt().coerceIn(0, 255)
                                            px[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
                                        }
                                        val bmp = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
                                        displayBmp = bmp
                                        if (recorder.active) recorder.encodeFrame(bmp)
                                    }
                                }
                                2 -> {
                                    val key = "${(params.ampFreq * 10).toInt()}/${state.fpsEst.toInt()}"
                                    if (state.coeffs == null || state.coeffKey != key) {
                                        state.coeffs = Biquad.coeffs(
                                            Biquad.TYPE_BANDPASS,
                                            params.ampFreq.toDouble(),
                                            1.0,
                                            state.fpsEst.coerceIn(10.0, 60.0)
                                        )
                                        state.coeffKey = key
                                    }
                                    if (state.mx1 == null) {
                                        state.mx1 = FloatArray(n * n)
                                        state.mx2 = FloatArray(n * n)
                                        state.my1 = FloatArray(n * n)
                                        state.my2 = FloatArray(n * n)
                                    }
                                    val co = state.coeffs!!
                                    val b0 = co[0]; val b1 = co[1]; val b2 = co[2]
                                    val a1 = co[3]; val a2 = co[4]
                                    val x1 = state.mx1!!; val x2 = state.mx2!!
                                    val y1 = state.my1!!; val y2 = state.my2!!
                                    val gain = params.ampGain.toDouble()
                                    val px = IntArray(n * n)
                                    for (i in px.indices) {
                                        val x = gray[i]
                                        val y = b0 * x + b1 * x1[i] + b2 * x2[i] - a1 * y1[i] - a2 * y2[i]
                                        x2[i] = x1[i]; x1[i] = x.toFloat()
                                        y2[i] = y1[i]; y1[i] = y.toFloat()
                                        val out = ((x + gain * y) * 255.0).toInt().coerceIn(0, 255)
                                        px[i] = (0xFF shl 24) or (out shl 16) or (out shl 8) or out
                                    }
                                    val bmp = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
                                    displayBmp = bmp
                                    if (recorder.active) recorder.encodeFrame(bmp)
                                }
                            }
                        } catch (t: Throwable) {
                            // skip frame
                        } finally {
                            proxy.close()
                        }
                    }
                    provider?.unbindAll()
                    provider?.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                    )
                } catch (t: Throwable) {
                    // camera unavailable
                }
            }, ContextCompat.getMainExecutor(context))
        }
        onDispose { provider?.unbindAll() }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (recorder.active) recorder.stop()
            executor.shutdown()
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
        if (showChrome) {
            Text("Video Spectrum", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("Analyze", "Filter", "Motion amp", "File").forEachIndexed { i, label ->
                    ChoiceChip(label, mode == i, accent) {
                        if (mode != i && recorder.active) {
                            savedVideo = recorder.stop()
                            isRecording = false
                        }
                        mode = i
                    }
                }
            }
        }

        if (mode < 3 && !hasPermission) {
            SectionCard {
                Text("Multitool needs camera access for live video analysis.")
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Grant camera access")
                }
            }
            return@Column
        }

        if (mode < 3) {
            SectionCard("Camera") {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            }
        }

        when (mode) {
            0 -> {
                SectionCard("Live 2D spectrum") {
                    val bmp = fftBitmap
                    if (bmp != null) {
                        Image(
                            bmp.asImageBitmap(), null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.FillBounds
                        )
                    } else {
                        SmallNote("Waiting for frames…")
                    }
                }
                val ts = temporal
                SectionCard("Motion rhythm · brightness over time") {
                    SpectrumPlot(
                        mags = ts?.mags,
                        binHz = ts?.binHz ?: 0.0,
                        logAxis = false,
                        showGrid = settings.showGrid.value,
                        accent = accent,
                        minFreq = 0.2,
                        maxFreq = ((ts?.fps ?: 30.0) / 2.0).coerceAtLeast(1.0),
                        probeFreq = probeText.toDoubleOrNull(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                    )
                    val dom = ts?.dominantHz
                    Text(
                        if (dom != null)
                            String.format("Dominant rhythm: %.1f Hz (%.0f BPM)", dom, dom * 60)
                        else "Wave your hand or flash a light rhythmically…",
                        fontWeight = FontWeight.SemiBold
                    )
                }
                run {
                    var participation: Double? = null
                    var detail: String? = null
                    val probe = probeText.toDoubleOrNull()
                    if (probe != null && ts != null && ts.totalPower > 0 && ts.binHz > 0) {
                        val bin = (probe / ts.binHz).roundToInt()
                        if (bin in 1 until ts.mags.size) {
                            var power = 0.0
                            for (b in (bin - 1)..(bin + 1)) {
                                if (b in 1 until ts.mags.size) power += ts.mags[b] * ts.mags[b]
                            }
                            participation = (power / ts.totalPower).coerceIn(0.0, 1.0)
                            detail = String.format(
                                "%.1f dB at %.2f Hz (sampled at %.1f fps)",
                                20.0 * log10(ts.mags[bin] + 1e-12), bin * ts.binHz, ts.fps
                            )
                        }
                    }
                    ProbeCard(
                        title = "Rhythm probe",
                        unitLabel = "Frequency (Hz)",
                        text = probeText,
                        onTextChange = { probeText = it },
                        participation = participation,
                        detail = detail,
                        accent = accent
                    )
                }
            }
            1, 2 -> {
                if (mode == 1) {
                    SectionCard("Spatial filter") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Low-pass (blur vision)", "High-pass (edge vision)").forEachIndexed { i, label ->
                                ChoiceChip(label, filterKind == i, accent) { filterKind = i }
                            }
                        }
                        Text(
                            String.format("Cutoff: %.0f%% of detail range", cutoffFrac * 100),
                            fontWeight = FontWeight.SemiBold
                        )
                        Slider(value = cutoffFrac, onValueChange = { cutoffFrac = it }, valueRange = 0.03f..1f)
                    }
                } else {
                    SectionCard("Motion amplifier") {
                        Text(
                            String.format("Target rhythm: %.1f Hz", ampFreq),
                            fontWeight = FontWeight.SemiBold
                        )
                        Slider(value = ampFreq, onValueChange = { ampFreq = it }, valueRange = 0.3f..8f)
                        Text(String.format("Amplification: ×%.0f", ampGain))
                        Slider(value = ampGain, onValueChange = { ampGain = it }, valueRange = 1f..30f)
                        SmallNote("Motion at the target rhythm gets exaggerated. Try pointing at someone breathing, a pulse, or a vibrating machine. Hold the phone very still.")
                    }
                }
                SectionCard("Live result") {
                    val bmp = displayBmp
                    if (bmp != null) {
                        Image(
                            bmp.asImageBitmap(), null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.FillBounds
                        )
                    } else {
                        SmallNote("Waiting for frames…")
                    }
                    Button(onClick = {
                        if (recorder.active) {
                            savedVideo = recorder.stop()
                            isRecording = false
                        } else {
                            savedVideo = null
                            try {
                                recorder.start()
                                isRecording = true
                            } catch (t: Throwable) {
                                savedVideo = "Recording failed to start"
                            }
                        }
                    }) {
                        Text(if (isRecording) "Stop recording" else "Record this view")
                    }
                    if (isRecording) SmallNote("Recording… (video only, no sound)")
                    val sv = savedVideo
                    if (sv != null) SmallNote("Saved: $sv")
                }
            }
            else -> {
                FileTransformSection(settings)
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun FileTransformSection(settings: SettingsStore) {
    val context = LocalContext.current
    val accent = com.jonathan.multitool.ui.theme.LocalAccent.current
    val scope = rememberCoroutineScope()
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var tMode by rememberSaveable { mutableStateOf(VideoTransformer.MODE_LOWPASS) }
    var cutoffFrac by rememberSaveable { mutableStateOf(0.25f) }
    var flickerHz by rememberSaveable { mutableStateOf(2f) }
    var processing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var resultMsg by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
        if (it != null) { videoUri = it; resultMsg = null }
    }

    SectionCard("Transform a video file") {
        Button(
            onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            },
            enabled = !processing
        ) {
            Text(if (videoUri == null) "Pick a video" else "Pick another video")
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                VideoTransformer.MODE_LOWPASS to "Low-pass",
                VideoTransformer.MODE_HIGHPASS to "High-pass",
                VideoTransformer.MODE_DEFLICKER to "De-flicker"
            ).forEach { (m, label) ->
                ChoiceChip(label, tMode == m, accent) { tMode = m }
            }
        }
        if (tMode != VideoTransformer.MODE_DEFLICKER) {
            Text(
                String.format("Cutoff: %.0f%% of detail range", cutoffFrac * 100),
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = cutoffFrac, onValueChange = { cutoffFrac = it },
                valueRange = 0.03f..1f, enabled = !processing
            )
        } else {
            Text(String.format("Flicker frequency: %.1f Hz", flickerHz), fontWeight = FontWeight.SemiBold)
            Slider(
                value = flickerHz, onValueChange = { flickerHz = it },
                valueRange = 0.5f..15f, enabled = !processing
            )
            SmallNote("Removes rhythmic brightness flicker (rolling bands from lights). Find the frequency first in Analyze mode.")
        }
        val u = videoUri
        Button(
            onClick = {
                if (u == null) return@Button
                processing = true
                progress = 0f
                resultMsg = null
                scope.launch {
                    val msg = try {
                        val loc = withContext(Dispatchers.Default) {
                            VideoTransformer.transform(
                                context, u, tMode, cutoffFrac.toDouble(), flickerHz.toDouble()
                            ) { p -> progress = p }
                        }
                        "Saved: $loc"
                    } catch (t: Throwable) {
                        "Transform failed: ${t.message ?: "unknown error"}"
                    }
                    resultMsg = msg
                    processing = false
                }
            },
            enabled = u != null && !processing
        ) {
            Text(if (processing) "Processing…" else "Process video")
        }
        if (processing) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = accent
            )
            SmallNote(String.format("%.0f%% — this can take a while for long videos", progress * 100))
        }
        val r = resultMsg
        if (r != null) SmallNote(r)
    }
}
