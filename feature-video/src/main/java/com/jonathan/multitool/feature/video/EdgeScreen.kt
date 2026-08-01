package com.jonathan.multitool.feature.video

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.jonathan.multitool.core.data.SettingsStore
import com.jonathan.multitool.ui.ChoiceChip
import com.jonathan.multitool.ui.theme.LocalAccent
import com.jonathan.multitool.ui.theme.LocalShell
import com.jonathan.multitool.ui.theme.Mono
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.math.sqrt

private class EdgeParams {
    @Volatile var gain = 3.0f
    @Volatile var mode = 0        // 0 = colour edges, 1 = edges over image, 2 = magnitude only
    @Volatile var floor = 0.06f
}

/**
 * Live edge detection, its own tool.
 *
 * Three things the old high-pass-in-the-FFT version got wrong: it ran on a 128×128 square so the
 * picture was low-res and the wrong shape, it discarded colour, and keeping the DC term made
 * everything wash out white. This is a plain Sobel on the luma plane at full analysis resolution,
 * with the source colour carried through and a gain that actually attenuates.
 */
@Composable
fun EdgeScreen(settings: SettingsStore) {
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

    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var gain by rememberSaveable { mutableStateOf(3.0f) }
    var mode by rememberSaveable { mutableStateOf(0) }
    var fps by remember { mutableStateOf(0.0) }
    val params = remember { EdgeParams() }
    params.gain = gain
    params.mode = mode

    val executor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(hasPermission) {
        var provider: ProcessCameraProvider? = null
        if (hasPermission) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    provider = future.get()
                    @Suppress("DEPRECATION")
                    val analysis = ImageAnalysis.Builder()
                        .setTargetResolution(Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    var last = 0L
                    var out: IntArray? = null
                    var outBmp: Bitmap? = null
                    analysis.setAnalyzer(executor) { proxy ->
                        try {
                            val w = proxy.width
                            val h = proxy.height
                            val rot = proxy.imageInfo.rotationDegrees
                            val swap = rot == 90 || rot == 270
                            val dw = if (swap) h else w
                            val dh = if (swap) w else h

                            if (out == null || out!!.size != dw * dh) {
                                out = IntArray(dw * dh)
                                outBmp = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888)
                            }
                            val dst = out!!

                            val yP = proxy.planes[0]
                            val uP = proxy.planes[1]
                            val vP = proxy.planes[2]
                            val yBuf = yP.buffer
                            val uBuf = uP.buffer
                            val vBuf = vP.buffer
                            val yRs = yP.rowStride
                            val yPs = yP.pixelStride
                            val uRs = uP.rowStride
                            val uPs = uP.pixelStride
                            val vRs = vP.rowStride
                            val vPs = vP.pixelStride

                            val g = params.gain
                            val floorV = params.floor
                            val m = params.mode

                            fun lum(x: Int, y: Int): Int =
                                yBuf.get(y * yRs + x * yPs).toInt() and 0xFF

                            for (y in 0 until h) {
                                val y0 = if (y > 0) y - 1 else 0
                                val y1 = if (y < h - 1) y + 1 else h - 1
                                for (x in 0 until w) {
                                    val x0 = if (x > 0) x - 1 else 0
                                    val x1 = if (x < w - 1) x + 1 else w - 1

                                    val tl = lum(x0, y0); val tc = lum(x, y0); val tr = lum(x1, y0)
                                    val ml = lum(x0, y);  val mc = lum(x, y);  val mr = lum(x1, y)
                                    val bl = lum(x0, y1); val bc = lum(x, y1); val br = lum(x1, y1)

                                    val gx = (tr + 2 * mr + br) - (tl + 2 * ml + bl)
                                    val gy = (bl + 2 * bc + br) - (tl + 2 * tc + tr)
                                    // 1020 is the Sobel maximum for 8-bit input
                                    var e = sqrt((gx * gx + gy * gy).toDouble()).toFloat() / 1020f
                                    e = ((e - floorV) / (1f - floorV)).coerceAtLeast(0f) * g
                                    if (e > 1f) e = 1f

                                    val argb = when (m) {
                                        2 -> {
                                            val k = (e * 255).toInt().coerceIn(0, 255)
                                            (0xFF shl 24) or (k shl 16) or (k shl 8) or k
                                        }
                                        else -> {
                                            // source colour, scaled by edge strength
                                            val uvRow = (y shr 1) * uRs
                                            val uvCol = (x shr 1) * uPs
                                            val u = (uBuf.get(uvRow + uvCol).toInt() and 0xFF) - 128
                                            val vv = (vBuf.get((y shr 1) * vRs + (x shr 1) * vPs).toInt() and 0xFF) - 128
                                            val yy = mc.toFloat()
                                            var r = yy + 1.402f * vv
                                            var gg = yy - 0.344f * u - 0.714f * vv
                                            var b = yy + 1.772f * u
                                            val k = if (m == 0) e else (0.25f + 0.75f * e)
                                            r *= k; gg *= k; b *= k
                                            val ri = r.toInt().coerceIn(0, 255)
                                            val gi = gg.toInt().coerceIn(0, 255)
                                            val bi = b.toInt().coerceIn(0, 255)
                                            (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
                                        }
                                    }

                                    // write straight into the rotated destination — no second pass
                                    val di = when (rot) {
                                        90 -> x * dw + (dw - 1 - y)
                                        180 -> (dh - 1 - y) * dw + (dw - 1 - x)
                                        270 -> (dh - 1 - x) * dw + y
                                        else -> y * dw + x
                                    }
                                    if (di in dst.indices) dst[di] = argb
                                }
                            }

                            outBmp?.let { b ->
                                b.setPixels(dst, 0, dw, 0, 0, dw, dh)
                                bmp = b.copy(Bitmap.Config.ARGB_8888, false)
                            }
                            val now = System.nanoTime()
                            if (last != 0L) {
                                val dt = (now - last) / 1e9
                                if (dt > 0.001) fps = if (fps == 0.0) 1 / dt else 0.9 * fps + 0.1 / dt
                            }
                            last = now
                        } catch (e: Throwable) {
                            // frame dropped
                        } finally {
                            proxy.close()
                        }
                    }
                    provider?.unbindAll()
                    provider?.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, analysis
                    )
                } catch (e: Throwable) {
                    // camera unavailable
                }
            }, ContextCompat.getMainExecutor(context))
        }
        onDispose { provider?.unbindAll() }
    }

    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    if (!hasPermission) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("Multitool needs camera access for live edge detection.")
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                Text("Grant camera access")
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(start = 4.dp, end = 12.dp, bottom = 8.dp)) {
        // the picture gets everything that's left
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            val b = bmp
            if (b != null) {
                Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = "Edge view",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                Text("starting camera…", style = Mono.label, color = t.fg40)
            }
            Text(
                "%.0f fps".format(fps),
                style = Mono.label,
                color = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
            )
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("Colour edges", "Over image", "Magnitude").forEachIndexed { i, label ->
                ChoiceChip(label, mode == i, accent) { mode = i }
            }
        }

        Text("Edge gain %.1f×".format(gain), style = Mono.label, color = t.fg50)
        Slider(value = gain, onValueChange = { gain = it }, valueRange = 0.5f..8f)
    }
}
