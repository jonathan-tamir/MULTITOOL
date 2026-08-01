package com.jonathan.multitool.core.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color

class SettingsStore(context: Context) {
    // null when running outside a real device (e.g. JVM screenshot rendering)
    private val prefs: SharedPreferences? =
        runCatching { context.getSharedPreferences("multitool_settings", Context.MODE_PRIVATE) }.getOrNull()

    val themeMode = mutableStateOf(prefs?.getString("themeMode", "system") ?: "system")
    val accent = mutableStateOf(prefs?.getString("accent", "cyan") ?: "cyan")
    val logFreqAxis = mutableStateOf((prefs?.getBoolean("logFreqAxis", true) ?: true))
    val showGrid = mutableStateOf((prefs?.getBoolean("showGrid", true) ?: true))
    val smoothing = mutableStateOf((prefs?.getFloat("smoothing", 0.5f) ?: 0.5f))
    val peakCount = mutableStateOf((prefs?.getInt("peakCount", 5) ?: 5))
    val imageFftSize = mutableStateOf((prefs?.getInt("imageFftSize", 256) ?: 256))

    // shell quick settings
    val keepAwake = mutableStateOf((prefs?.getBoolean("keepAwake", true) ?: true))
    val haptics = mutableStateOf((prefs?.getBoolean("haptics", true) ?: true))
    val autoLog = mutableStateOf((prefs?.getBoolean("autoLog", false) ?: false))
    /** Symbol period for the light link, shared by every communication tool. */
    val linkSymbolMs = mutableStateOf((prefs?.getInt("linkSymbolMs", 150) ?: 150))

    fun setThemeMode(v: String) { themeMode.value = v; prefs?.edit()?.putString("themeMode", v)?.apply() }
    fun setAccent(v: String) { accent.value = v; prefs?.edit()?.putString("accent", v)?.apply() }
    fun setLogFreqAxis(v: Boolean) { logFreqAxis.value = v; prefs?.edit()?.putBoolean("logFreqAxis", v)?.apply() }
    fun setShowGrid(v: Boolean) { showGrid.value = v; prefs?.edit()?.putBoolean("showGrid", v)?.apply() }
    fun setSmoothing(v: Float) { smoothing.value = v; prefs?.edit()?.putFloat("smoothing", v)?.apply() }
    fun setPeakCount(v: Int) { peakCount.value = v; prefs?.edit()?.putInt("peakCount", v)?.apply() }
    fun setImageFftSize(v: Int) { imageFftSize.value = v; prefs?.edit()?.putInt("imageFftSize", v)?.apply() }

    fun setKeepAwake(v: Boolean) { keepAwake.value = v; prefs?.edit()?.putBoolean("keepAwake", v)?.apply() }
    fun setHaptics(v: Boolean) { haptics.value = v; prefs?.edit()?.putBoolean("haptics", v)?.apply() }
    fun setAutoLog(v: Boolean) { autoLog.value = v; prefs?.edit()?.putBoolean("autoLog", v)?.apply() }

    fun setLinkSymbolMs(v: Int) { linkSymbolMs.value = v; prefs?.edit()?.putInt("linkSymbolMs", v)?.apply() }

    fun accentColor(): Color = ACCENTS[accent.value] ?: Color(0xFF22D3EE)

    companion object {
        val ACCENTS: Map<String, Color> = linkedMapOf(
            "cyan" to Color(0xFF22D3EE),
            "violet" to Color(0xFFA78BFA),
            "emerald" to Color(0xFF34D399),
            "amber" to Color(0xFFFBBF24)
        )
    }
}
