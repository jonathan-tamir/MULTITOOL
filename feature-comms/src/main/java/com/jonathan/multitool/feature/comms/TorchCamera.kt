package com.jonathan.multitool.feature.comms

import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

/**
 * Binds the camera for the light link and feeds the physical layer.
 *
 * The torch is driven through this same capture session (`CameraControl.enableTorch`), which is
 * why transmit and receive can run at once: the frames keep flowing while the light is on.
 * Exposure is pinned as low as the device allows, because auto-exposure would hunt every time
 * either torch fires and erase the very signal we're trying to read.
 */
@Composable
fun BindTorchCamera(link: TorchLink, previewView: PreviewView, enabled: Boolean) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(enabled) {
        val executor = Executors.newSingleThreadExecutor()
        var provider: ProcessCameraProvider? = null
        if (enabled) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    provider = future.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    @Suppress("DEPRECATION")
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
                            val r1 = minOf(w, h) / 12
                            val r2 = minOf(w, h) / 5
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
                    val range = cam?.cameraInfo?.exposureState?.exposureCompensationRange
                    if (range != null) cam.cameraControl.setExposureCompensationIndex(range.lower)
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
            executor.shutdown()
        }
    }
}
