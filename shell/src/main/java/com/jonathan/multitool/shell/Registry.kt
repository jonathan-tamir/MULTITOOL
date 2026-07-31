package com.jonathan.multitool.shell

import androidx.compose.runtime.Composable
import com.jonathan.multitool.core.audio.AudioEngine
import com.jonathan.multitool.core.data.SettingsStore
import com.jonathan.multitool.feature.audio.AudioScreen
import com.jonathan.multitool.feature.comms.CommsScreen
import com.jonathan.multitool.feature.comms.Mode
import com.jonathan.multitool.feature.drone.DroneScreen
import com.jonathan.multitool.feature.image.ImageScreen
import com.jonathan.multitool.feature.video.VideoScreen
import com.jonathan.multitool.ui.Motif

/** Everything a tool needs from the shell. */
class ToolHost(
    val settings: SettingsStore,
    val audio: AudioEngine
)

/**
 * One utility. `chips` are the status pills shown in the utility header, so an image tool
 * never claims "FS 48 kHz" the way the design comp's placeholder did.
 */
class Tool(
    val name: String,
    val tag: String,
    val meta: String,
    val chips: List<String>,
    val render: @Composable (ToolHost) -> Unit
)

class Category(
    val key: String,
    val name: String,
    val code: String,
    val desc: String,
    val hue: Float,
    val motif: Motif,
    val tools: List<Tool>
) {
    val count: Int get() = tools.size          // derived, so it can never drift
}

/**
 * Single source of truth for the whole app. Adding a utility is a data edit here — the home
 * grid, subspace list, counts, recents and accents all follow.
 */
object Registry {

    val categories: List<Category> = listOf(
        Category(
            key = "sound", name = "Sound processing", code = "ACOUSTIC", hue = 195f, motif = Motif.Spectrum,
            desc = "Microphone in, frequency domain out. Capture, analyse and generate audio in the field.",
            tools = listOf(
                Tool("Spectrogram", "STFT", "live · log scale", listOf("FS 48 kHz", "16 BIT")) {
                    AudioScreen(it.settings, it.audio, startMode = 0, showChrome = false)
                },
                Tool("Filter bench", "FILT", "low / high / band / notch", listOf("BIQUAD", "MONITOR")) {
                    AudioScreen(it.settings, it.audio, startMode = 1, showChrome = false)
                },
                Tool("Record & clean", "REC", "WAV export · de-hum", listOf("WAV", "16 BIT")) {
                    AudioScreen(it.settings, it.audio, startMode = 2, showChrome = false)
                },
                Tool("Tone & tuner", "GEN", "sine · 20 Hz – 20 kHz", listOf("SINE", "CENTS")) {
                    AudioScreen(it.settings, it.audio, startMode = 3, showChrome = false)
                }
            )
        ),
        Category(
            key = "image", name = "Image processing", code = "OPTICAL", hue = 300f, motif = Motif.Grid,
            desc = "Camera as a sensor. Spatial filters and 2D transforms computed on still frames.",
            tools = listOf(
                Tool("Real-time FFT2", "FFT2", "magnitude spectrum", listOf("GRAY 8", "LOG MAG")) {
                    ImageScreen(it.settings, startMode = 0, showChrome = false)
                },
                Tool("Filter lab", "FILT", "LP · HP · band-stop", listOf("FFT2", "PREVIEW")) {
                    ImageScreen(it.settings, startMode = 1, showChrome = false)
                },
                Tool("Spectrum eraser", "ERAS", "tap peaks to zap", listOf("FFT2", "MIRRORED")) {
                    ImageScreen(it.settings, startMode = 2, showChrome = false)
                },
                Tool("Hybrid images", "HYB", "low A + high B", listOf("2 INPUTS", "FFT2")) {
                    ImageScreen(it.settings, startMode = 3, showChrome = false)
                }
            )
        ),
        Category(
            key = "video", name = "Video processing", code = "MOTION", hue = 25f, motif = Motif.Scan,
            desc = "The frame stream as a signal. Spatial filters live, and temporal ones over time.",
            tools = listOf(
                Tool("Live FFT2", "FFT2", "camera → spectrum", listOf("CAM", "LIVE")) {
                    VideoScreen(it.settings, startMode = 0, showChrome = false)
                },
                Tool("Filtered view", "FILT", "blur / edge · record", listOf("CAM", "MP4")) {
                    VideoScreen(it.settings, startMode = 1, showChrome = false)
                },
                Tool("Motion amplifier", "AMP", "Eulerian · presets", listOf("CAM", "TEMPORAL")) {
                    VideoScreen(it.settings, startMode = 2, showChrome = false)
                },
                Tool("File transform", "FILE", "LP · HP · de-flicker", listOf("MP4 IN", "MP4 OUT")) {
                    VideoScreen(it.settings, startMode = 3, showChrome = false)
                }
            )
        ),
        Category(
            key = "comms", name = "Communication", code = "LINK", hue = 250f, motif = Motif.Pulse,
            desc = "Talk to another phone with light. Torch out, camera in, both at the same time.",
            tools = listOf(
                Tool("Morse light", "MRS", "send + decode \u00b7 human readable", listOf("TORCH", "ADAPTIVE")) {
                    CommsScreen(it.settings, Mode.MORSE)
                },
                Tool("ASCII link", "UART", "8-N-1 over light \u00b7 any text", listOf("TORCH", "8-N-1")) {
                    CommsScreen(it.settings, Mode.UART)
                },
                Tool("Fast link", "HUFF", "English only \u00b7 2.25x faster", listOf("TORCH", "HUFFMAN")) {
                    CommsScreen(it.settings, Mode.FAST)
                }
            )
        ),
        Category(
            key = "inertial", name = "Inertial devices", code = "INERTIAL", hue = 75f, motif = Motif.Axes,
            desc = "Accelerometer, gyroscope and magnetometer streams with the statistics worth watching.",
            tools = emptyList()
        ),
        Category(
            key = "misc", name = "Misc.", code = "MISC", hue = 150f, motif = Motif.Radar,
            desc = "Experiments that do not belong anywhere else yet.",
            tools = listOf(
                Tool("Drone detector", "DRN", "rotor harmonics · alert", listOf("FS 16 kHz", "3-OF-4")) {
                    DroneScreen(it.settings)
                }
            )
        )
    )

    fun category(key: String): Category = categories.firstOrNull { it.key == key } ?: categories[0]

    fun tool(catKey: String, name: String): Tool? =
        category(catKey).tools.firstOrNull { it.name == name }

    val toolCount: Int get() = categories.sumOf { it.tools.size }
}
