package com.jonathan.multitool.shell

import com.jonathan.multitool.ui.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jonathan.multitool.core.data.SettingsStore

@Composable
fun SettingsScreen(settings: SettingsStore) {
    val accent = settings.accentColor()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

        SectionCard("Appearance") {
            Text("Theme", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (key, label) ->
                    ChoiceChip(label, settings.themeMode.value == key, accent) {
                        settings.setThemeMode(key)
                    }
                }
            }
            Text("Accent color", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsStore.ACCENTS.forEach { (key, color) ->
                    val selected = settings.accent.value == key
                    androidx.compose.foundation.layout.Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(
                                width = if (selected) 3.dp else 0.dp,
                                color = if (selected) MaterialTheme.colorScheme.onBackground else Color.Transparent,
                                shape = CircleShape
                            )
                            .clickable { settings.setAccent(key) }
                    )
                }
            }
        }

        SectionCard("Spectrum display") {
            SettingRow("Logarithmic frequency axis") {
                Switch(
                    checked = settings.logFreqAxis.value,
                    onCheckedChange = { settings.setLogFreqAxis(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = accent)
                )
            }
            SettingRow("Show grid and labels") {
                Switch(
                    checked = settings.showGrid.value,
                    onCheckedChange = { settings.setShowGrid(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = accent)
                )
            }
            Text(
                String.format("Smoothing: %.0f%%", settings.smoothing.value * 100),
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = settings.smoothing.value,
                onValueChange = { settings.setSmoothing(it) },
                valueRange = 0f..0.95f
            )
            Text(
                "Peaks shown: ${settings.peakCount.value}",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = settings.peakCount.value.toFloat(),
                onValueChange = { settings.setPeakCount(it.toInt().coerceIn(3, 8)) },
                valueRange = 3f..8f,
                steps = 4
            )
        }

        SectionCard("Image analysis") {
            Text("FFT resolution", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(128, 256, 512).forEach { n ->
                    ChoiceChip("$n×$n", settings.imageFftSize.value == n, accent) {
                        settings.setImageFftSize(n)
                    }
                }
            }
        }

        SectionCard("About") {
            Text("Jonathan's Spectrum Analyzer", fontWeight = FontWeight.SemiBold)
            Text(
                "JSA v1.0 — real-time FFT analysis of sound, images and live video.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingRow(label: String, trailing: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        trailing()
    }
}
