package com.jonathan.multitool.screens

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.jonathan.multitool.core.audio.AudioEngine
import com.jonathan.multitool.core.data.SettingsStore
import com.jonathan.multitool.shell.Registry
import com.jonathan.multitool.shell.Shell
import com.jonathan.multitool.shell.ShellState
import com.jonathan.multitool.shell.View
import com.jonathan.multitool.ui.theme.MultitoolTheme
import com.jonathan.multitool.ui.theme.accentFor
import org.junit.Rule
import org.junit.Test

/**
 * Renders the shell off-device so the UI can be reviewed without a phone or an emulator.
 * `gradle :screenshots:recordPaparazziDebug` writes PNGs that CI commits back to the repo.
 */
class ShellScreenshots {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 1236, screenHeight = 2676, xdpi = 440, ydpi = 440,
            nightMode = NightMode.NIGHT
        ),
        maxPercentDifference = 0.0
    )

    private fun shot(name: String, dark: Boolean, configure: (ShellState) -> Unit) {
        paparazzi.snapshot(name = name) {
            val settings = SettingsStore(androidx.compose.ui.platform.LocalContext.current)
            settings.themeMode.value = if (dark) "dark" else "light"
            val state = ShellState()
            configure(state)
            state.overlay = null
            val accent = accentFor(Registry.category(state.catKey).hue, dark)
            MultitoolTheme(if (dark) "dark" else "light", accent) {
                Shell(settings, AudioEngine(), state)
            }
        }
    }

    @Test fun home_dark() = shot("home-dark", true) { }
    @Test fun home_light() = shot("home-light", false) { }

    @Test fun category_sound() = shot("category-sound", true) {
        it.catKey = "sound"; it.view = View.Category
    }

    @Test fun category_video() = shot("category-video", true) {
        it.catKey = "video"; it.view = View.Category
    }

    @Test fun category_inertial_empty() = shot("category-inertial", true) {
        it.catKey = "inertial"; it.view = View.Category
    }

    @Test fun utility_drone() = shot("utility-drone", true) {
        it.catKey = "misc"; it.toolName = "Drone detector"; it.view = View.Utility
    }

    @Test fun drawer_open() = shot("drawer", true) {
        it.drawerOpen = true
    }
}
