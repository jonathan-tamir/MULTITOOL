package com.jonathan.multitool.feature.image

import com.jonathan.multitool.ui.*
import com.jonathan.multitool.core.util.stamp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.jonathan.multitool.core.data.SettingsStore
import com.jonathan.multitool.core.dsp.Fft
import com.jonathan.multitool.core.image.ImageMath
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal fun loadBitmap(context: Context, uri: Uri): Bitmap {
    return if (Build.VERSION.SDK_INT >= 28) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        @Suppress("DEPRECATION")
        MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
    }
}


@Composable
fun ImageScreen(
    settings: SettingsStore,
    startMode: Int = 0,
    showChrome: Boolean = true
) {
    val context = LocalContext.current
    val accent = com.jonathan.multitool.ui.theme.LocalAccent.current
    var mode by rememberSaveable { mutableStateOf(startMode) }
    var src by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var pickedUri by remember { mutableStateOf<Uri?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
        if (it != null) pickedUri = it
    }
    LaunchedEffect(pickedUri) {
        val u = pickedUri ?: return@LaunchedEffect
        loading = true
        loadError = null
        try {
            src = withContext(Dispatchers.Default) { loadBitmap(context, u) }
        } catch (t: Throwable) {
            loadError = "Couldn't open that image."
        }
        loading = false
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
            Text("Image Spectrum", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("Analyze", "Filter lab", "Eraser", "Hybrid").forEachIndexed { i, label ->
                    ChoiceChip(label, mode == i, accent) { mode = i }
                }
            }
        }

        SectionCard {
            Button(onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) {
                Text(if (src == null) "Pick an image" else "Pick another image")
            }
            if (loading) SmallNote("Loading…")
            val e = loadError
            if (e != null) Text(e, color = MaterialTheme.colorScheme.error)
        }

        val bmp = src
        if (bmp != null) {
            when (mode) {
                0 -> AnalyzeSection(bmp, settings)
                1 -> FilterLabSection(bmp, settings)
                2 -> EraserSection(bmp, settings)
                else -> HybridSection(bmp, settings)
            }
        } else {
            SmallNote("Pick an image to begin.")
        }
        Spacer(Modifier.height(4.dp))
    }
}


// ------------------------------ Analyze ------------------------------

private class AnalyzeResult(
    val spectrum: Bitmap,
    val radialAmp: DoubleArray,
    val totalPower: Double
)

@Composable
private fun AnalyzeSection(src: Bitmap, settings: SettingsStore) {
    val accent = com.jonathan.multitool.ui.theme.LocalAccent.current
    val accentArgb = accent.toArgb()
    val n = settings.imageFftSize.value
    var result by remember { mutableStateOf<AnalyzeResult?>(null) }
    var probeText by rememberSaveable { mutableStateOf("10") }

    LaunchedEffect(src, n, accentArgb) {
        result = withContext(Dispatchers.Default) {
            val ch = ImageMath.channelsFromBitmap(src, n)
            val gray = DoubleArray(n * n) {
                (0.299 * ch.r[it] + 0.587 * ch.g[it] + 0.114 * ch.b[it]) / 255.0
            }
            val mag = Fft.fft2Magnitude(gray, n, n)
            val bmp = magnitudeToBitmap(mag, n, n, accentArgb)
            val maxR = n / 2
            val power = DoubleArray(maxR)
            var total = 0.0
            val c = n / 2
            for (y in 0 until n) {
                for (x in 0 until n) {
                    val dx = (x - c).toDouble()
                    val dy = (y - c).toDouble()
                    val ring = sqrt(dx * dx + dy * dy).roundToInt()
                    if (ring in 1 until maxR) {
                        val p = mag[y * n + x] * mag[y * n + x]
                        power[ring] += p
                        total += p
                    }
                }
            }
            AnalyzeResult(bmp, DoubleArray(maxR) { sqrt(power[it]) }, total)
        }
    }

    val r = result
    if (r == null) {
        SmallNote("Analyzing…")
        return
    }
    SectionCard("Original") {
        Image(
            src.asImageBitmap(), null,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Fit
        )
    }
    SectionCard("2D spectrum · log magnitude, DC centered") {
        Image(
            r.spectrum.asImageBitmap(), null,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.FillBounds
        )
        SmallNote("Center = coarse structure, edges = fine detail.")
    }
    SectionCard("Prominent spatial frequencies") {
        val accent2 = settings.accentColor()
        val peaks = Fft.findPeaks(r.radialAmp, 1.0, settings.peakCount.value, minFreq = 2.0)
        PeakChips(peaks, accent2, showNote = false, unit = "c/img")
    }
    run {
        var participation: Double? = null
        var detail: String? = null
        val probe = probeText.toDoubleOrNull()
        if (probe != null && r.totalPower > 0) {
            val ring = probe.roundToInt()
            if (ring in 1 until r.radialAmp.size) {
                val p = r.radialAmp[ring] * r.radialAmp[ring]
                participation = (p / r.totalPower).coerceIn(0.0, 1.0)
                detail = String.format(
                    "%.1f dB in the ring at %d cycles/image",
                    20.0 * log10(r.radialAmp[ring] + 1e-12), ring
                )
            }
        }
        ProbeCard(
            title = "Spatial frequency probe",
            unitLabel = "Cycles per image",
            text = probeText,
            onTextChange = { probeText = it },
            participation = participation,
            detail = detail,
            accent = settings.accentColor()
        )
    }
}

// ------------------------------ Filter lab ------------------------------

@Composable
private fun FilterLabSection(src: Bitmap, settings: SettingsStore) {
    val context = LocalContext.current
    val accent = com.jonathan.multitool.ui.theme.LocalAccent.current
    val n = settings.imageFftSize.value
    var kind by rememberSaveable { mutableStateOf(0) } // 0 lp, 1 hp, 2 bandstop
    var cutoffFrac by rememberSaveable { mutableStateOf(0.25f) }
    var widthFrac by rememberSaveable { mutableStateOf(0.06f) }
    var resultBmp by remember { mutableStateOf<Bitmap?>(null) }
    var savedTo by remember { mutableStateOf<String?>(null) }
    var fft by remember { mutableStateOf<ImageMath.FftChannels?>(null) }

    LaunchedEffect(src, n) {
        fft = null
        resultBmp = null
        fft = withContext(Dispatchers.Default) {
            ImageMath.forward(ImageMath.channelsFromBitmap(src, n))
        }
    }
    LaunchedEffect(fft, kind, cutoffFrac, widthFrac) {
        val f = fft ?: return@LaunchedEffect
        delay(120) // debounce slider drags
        savedTo = null
        resultBmp = withContext(Dispatchers.Default) {
            val maxR = f.n / 2.0
            val cutoff = (cutoffFrac * maxR).toDouble().coerceAtLeast(1.0)
            val soft = (0.02 * maxR).coerceAtLeast(1.0)
            val width = (widthFrac * maxR).toDouble().coerceAtLeast(0.5)
            val mask = when (kind) {
                0 -> ImageMath.lowpassMask(f.n, cutoff, soft)
                1 -> ImageMath.highpassMask(f.n, cutoff, soft)
                else -> ImageMath.bandstopMask(f.n, cutoff, width)
            }
            ImageMath.applyMask(f, mask)
        }
    }

    SectionCard("Filter") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Low-pass", "High-pass", "Band-stop").forEachIndexed { i, label ->
                ChoiceChip(label, kind == i, accent) { kind = i }
            }
        }
        Text(
            String.format(
                "%s radius: %.0f cycles",
                if (kind == 2) "Ring" else "Cutoff", cutoffFrac * n / 2
            ),
            fontWeight = FontWeight.SemiBold
        )
        Slider(value = cutoffFrac, onValueChange = { cutoffFrac = it }, valueRange = 0.02f..1f)
        if (kind == 2) {
            Text(String.format("Ring width: %.1f cycles", widthFrac * n / 2))
            Slider(value = widthFrac, onValueChange = { widthFrac = it }, valueRange = 0.01f..0.3f)
        }
        SmallNote(
            when (kind) {
                0 -> "Keeps coarse structure, softens detail."
                1 -> "Keeps edges and texture, removes shading."
                else -> "Removes one ring of frequencies — kills moiré, halftone dots and scan lines."
            }
        )
    }
    SectionCard("Result") {
        val rb = resultBmp
        if (rb != null) {
            Image(
                rb.asImageBitmap(), null,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.FillBounds
            )
            Button(onClick = {
                val name = "JSA_filter_${stamp()}.png"
                try {
                    savedTo = ImageMath.saveBitmap(
                        context, rb, name,
                        src.width.coerceAtMost(1024),
                        src.height.coerceAtMost(1024)
                    )
                } catch (t: Throwable) {
                    savedTo = "Save failed"
                }
            }) { Text("Save to gallery") }
            val s = savedTo
            if (s != null) SmallNote("Saved: $s")
        } else {
            SmallNote("Computing…")
        }
    }
}

// ------------------------------ Eraser ------------------------------

@Composable
private fun EraserSection(src: Bitmap, settings: SettingsStore) {
    val context = LocalContext.current
    val accent = com.jonathan.multitool.ui.theme.LocalAccent.current
    val accentArgb = accent.toArgb()
    val n = settings.imageFftSize.value
    var fft by remember { mutableStateOf<ImageMath.FftChannels?>(null) }
    val mask = remember(src, n) { DoubleArray(n * n) { 1.0 } }
    var revision by remember(src, n) { mutableStateOf(0) }
    var specBmp by remember { mutableStateOf<Bitmap?>(null) }
    var reconBmp by remember { mutableStateOf<Bitmap?>(null) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var savedTo by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(src, n) {
        fft = null
        specBmp = null
        reconBmp = null
        fft = withContext(Dispatchers.Default) {
            ImageMath.forward(ImageMath.channelsFromBitmap(src, n))
        }
        revision++
    }
    LaunchedEffect(fft, revision, accentArgb) {
        val f = fft ?: return@LaunchedEffect
        savedTo = null
        val pair = withContext(Dispatchers.Default) {
            val masked = DoubleArray(n * n) { f.shiftedMag[it] * mask[it] }
            val sb = magnitudeToBitmap(masked, n, n, accentArgb)
            val rb = ImageMath.applyShiftedMask(f, mask)
            Pair(sb, rb)
        }
        specBmp = pair.first
        reconBmp = pair.second
    }

    SectionCard("Spectrum — tap bright spots to erase them") {
        val sb = specBmp
        if (sb != null) {
            Image(
                sb.asImageBitmap(), null,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .onSizeChanged { viewSize = it }
                    .pointerInput(fft, n) {
                        detectTapGestures { offset ->
                            if (viewSize.width > 0) {
                                val px = (offset.x / viewSize.width * n).toInt().coerceIn(0, n - 1)
                                val py = (offset.y / viewSize.height * n).toInt().coerceIn(0, n - 1)
                                ImageMath.carveHole(mask, n, px, py, n / 42.0)
                                revision++
                            }
                        }
                    },
                contentScale = ContentScale.FillBounds
            )
            SmallNote("Each tap erases that frequency and its mirror twin. Great for periodic noise.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    for (i in mask.indices) mask[i] = 1.0
                    revision++
                }) { Text("Reset") }
            }
        } else {
            SmallNote("Preparing spectrum…")
        }
    }
    SectionCard("Reconstructed image") {
        val rb = reconBmp
        if (rb != null) {
            Image(
                rb.asImageBitmap(), null,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.FillBounds
            )
            Button(onClick = {
                try {
                    savedTo = ImageMath.saveBitmap(
                        context, rb, "JSA_erased_${stamp()}.png",
                        src.width.coerceAtMost(1024), src.height.coerceAtMost(1024)
                    )
                } catch (t: Throwable) {
                    savedTo = "Save failed"
                }
            }) { Text("Save to gallery") }
            val s = savedTo
            if (s != null) SmallNote("Saved: $s")
        } else {
            SmallNote("Waiting for spectrum…")
        }
    }
}

// ------------------------------ Hybrid ------------------------------

@Composable
private fun HybridSection(srcA: Bitmap, settings: SettingsStore) {
    val context = LocalContext.current
    val accent = com.jonathan.multitool.ui.theme.LocalAccent.current
    val n = settings.imageFftSize.value
    var uriB by remember { mutableStateOf<Uri?>(null) }
    var srcB by remember { mutableStateOf<Bitmap?>(null) }
    var cutoffFrac by rememberSaveable { mutableStateOf(0.12f) }
    var resultBmp by remember { mutableStateOf<Bitmap?>(null) }
    var savedTo by remember { mutableStateOf<String?>(null) }

    val pickerB = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
        if (it != null) uriB = it
    }
    LaunchedEffect(uriB) {
        val u = uriB ?: return@LaunchedEffect
        srcB = try {
            withContext(Dispatchers.Default) { loadBitmap(context, u) }
        } catch (t: Throwable) { null }
    }
    LaunchedEffect(srcA, srcB, n, cutoffFrac) {
        val b = srcB ?: return@LaunchedEffect
        delay(120)
        savedTo = null
        resultBmp = withContext(Dispatchers.Default) {
            val chA = ImageMath.channelsFromBitmap(srcA, n)
            val chB = ImageMath.channelsFromBitmap(b, n)
            val maxR = n / 2.0
            ImageMath.hybrid(chA, chB, cutoffFrac * maxR, 0.02 * maxR)
        }
    }

    SectionCard("Hybrid image") {
        SmallNote("Your picked image supplies the LOW frequencies (what you see from afar). Pick a second image for the HIGH frequencies (what you see up close).")
        Button(onClick = {
            pickerB.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }) {
            Text(if (srcB == null) "Pick detail image" else "Pick another detail image")
        }
        Text(String.format("Crossover: %.0f cycles", cutoffFrac * n / 2), fontWeight = FontWeight.SemiBold)
        Slider(value = cutoffFrac, onValueChange = { cutoffFrac = it }, valueRange = 0.03f..0.5f)
    }
    val rb = resultBmp
    if (rb != null) {
        SectionCard("Result — squint or step back!") {
            Image(
                rb.asImageBitmap(), null,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.FillBounds
            )
            Button(onClick = {
                try {
                    savedTo = ImageMath.saveBitmap(context, rb, "JSA_hybrid_${stamp()}.png")
                } catch (t: Throwable) {
                    savedTo = "Save failed"
                }
            }) { Text("Save to gallery") }
            val s = savedTo
            if (s != null) SmallNote("Saved: $s")
        }
    } else if (srcB != null) {
        SmallNote("Computing hybrid…")
    }
}
