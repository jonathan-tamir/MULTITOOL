package com.jonathan.multitool.screens

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityOptionsCompat
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.jonathan.multitool.core.audio.AudioEngine
import com.jonathan.multitool.core.data.SettingsStore
import com.jonathan.multitool.shell.Overlay
import com.jonathan.multitool.shell.Registry
import com.jonathan.multitool.shell.Shell
import com.jonathan.multitool.shell.ShellState
import com.jonathan.multitool.shell.View
import com.jonathan.multitool.ui.theme.MultitoolTheme
import com.jonathan.multitool.ui.theme.accentFor
import org.junit.Rule
import org.junit.Test

/**
 * Renders the shell off-device so the UI can be reviewed without a phone or an emulator:
 *
 *   ENABLE_SCREENSHOTS=1 gradle :screenshots:recordPaparazziDebug
 *
 * Tool screens register activity-result launchers (permissions, file pickers), so a no-op
 * registry owner is provided — without it they throw the moment they compose.
 */
class ShellScreenshots {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 1236, screenHeight = 2676, xdpi = 440, ydpi = 440,
            nightMode = NightMode.NIGHT
        )
    )

    private val noopRegistryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?
            ) = Unit
        }
    }

    private fun shot(name: String, dark: Boolean = true, configure: (ShellState) -> Unit = {}) {
        paparazzi.snapshot(name = name) {
            val settings = SettingsStore(LocalContext.current)
            settings.themeMode.value = if (dark) "dark" else "light"
            val state = ShellState()
            state.overlay = null
            configure(state)
            val accent = accentFor(Registry.category(state.catKey).hue, dark)
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides noopRegistryOwner) {
                MultitoolTheme(if (dark) "dark" else "light", accent) {
                    Shell(settings, AudioEngine(), state)
                }
            }
        }
    }

    // Screens that bind CameraX (video tools, the light link) can't render off-device — they need
    // a real camera provider, so they're verified on hardware instead.
    private fun utility(name: String, catKey: String, tool: String) =
        shot(name) { it.catKey = catKey; it.toolName = tool; it.view = View.Utility }

    @Test fun home_dark() = shot("home-dark")
    @Test fun home_light() = shot("home-light", dark = false)

    @Test fun category_sound() = shot("category-sound") { it.catKey = "sound"; it.view = View.Category }
    @Test fun category_video() = shot("category-video") { it.catKey = "video"; it.view = View.Category }
    @Test fun category_image_light() = shot("category-image-light", dark = false) {
        it.catKey = "image"; it.view = View.Category
    }
    @Test fun category_inertial_empty() = shot("category-inertial") {
        it.catKey = "inertial"; it.view = View.Category
    }

    @Test fun utility_spectrogram() = utility("utility-spectrogram", "sound", "Spectrogram")
    @Test fun utility_tone() = utility("utility-tone", "sound", "Tone & tuner")
    @Test fun utility_fft2() = utility("utility-fft2", "image", "Real-time FFT2")
    @Test fun utility_drone() = utility("utility-drone", "misc", "Drone detector")

    @Test fun category_comms() = shot("category-comms") { it.catKey = "comms"; it.view = View.Category }

    @Test fun drawer_open() = shot("drawer") { it.drawerOpen = true }
    @Test fun settings() = shot("settings") { it.view = View.Settings }

    // frozen mid-frame of each takeover
    @Test fun overlay_cat_zoom() = shot("overlay-catzoom") {
        it.catKey = "image"; it.view = View.Category; it.overlay = Overlay.CatZoom("image")
    }
    @Test fun overlay_signal() = shot("overlay-signal") {
        it.catKey = "sound"; it.toolName = "Spectrogram"; it.view = View.Utility
        it.overlay = Overlay.Signal("Spectrogram")
    }
}
