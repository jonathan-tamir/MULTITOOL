package com.jonathan.multitool

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.jonathan.multitool.core.audio.AudioEngine
import com.jonathan.multitool.core.data.SettingsStore
import com.jonathan.multitool.shell.Registry
import com.jonathan.multitool.shell.Shell
import com.jonathan.multitool.shell.ShellState
import com.jonathan.multitool.ui.LocalHaptics
import com.jonathan.multitool.ui.rememberHaptics
import com.jonathan.multitool.ui.theme.MultitoolTheme
import com.jonathan.multitool.ui.theme.accentFor

class MainActivity : ComponentActivity() {

    /** One shared capture engine for every audio tool — the mic has a single owner. */
    private val audio = AudioEngine()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings = remember { SettingsStore(applicationContext) }
            val state = remember { ShellState() }

            // Accent follows the category you're inside, per the design brief.
            val dark = settings.themeMode.value != "light"
            val accent = accentFor(Registry.category(state.catKey).hue, dark)

            LaunchedEffect(settings.keepAwake.value) {
                if (settings.keepAwake.value) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            val haptics = rememberHaptics { settings.haptics.value }
            MultitoolTheme(settings.themeMode.value, accent) {
                CompositionLocalProvider(LocalHaptics provides haptics) {
                    Shell(settings, audio, state)
                }
            }
        }
    }

    override fun onDestroy() {
        audio.stop()
        super.onDestroy()
    }
}
