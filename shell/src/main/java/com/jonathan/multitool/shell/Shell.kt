package com.jonathan.multitool.shell

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.jonathan.multitool.core.audio.AudioEngine
import com.jonathan.multitool.core.data.SettingsStore
import com.jonathan.multitool.ui.CAT_ZOOM_MS
import com.jonathan.multitool.ui.LocalHaptics
import com.jonathan.multitool.ui.CatZoomOverlay
import com.jonathan.multitool.ui.SIGNAL_MS
import com.jonathan.multitool.ui.SignalOverlay
import com.jonathan.multitool.ui.motif
import com.jonathan.multitool.ui.theme.LocalShell
import com.jonathan.multitool.ui.theme.Mono
import com.jonathan.multitool.ui.theme.accentFor
import kotlinx.coroutines.delay

/** Width of the edge affordance; every screen keeps its content clear of it. */
private val EDGE = 30.dp
private val GUTTER = 42.dp

@Composable
fun Shell(settings: SettingsStore, audio: AudioEngine, state: ShellState) {
    val t = LocalShell.current
    val cat = Registry.category(state.catKey)
    val accent = accentFor(cat.hue, t.dark)
    val host = remember(settings, audio) { ToolHost(settings, audio) }
    val haptics = LocalHaptics.current

    fun launch(catKey: String, name: String, fromRecent: Boolean) {
        haptics.launch()
        state.toTool(catKey, name, fromRecent)
    }

    // Guarded: off-device screenshot rendering has no back-press dispatcher.
    if (androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current != null) {
        BackHandler(enabled = state.view != View.Home || state.drawerOpen) { haptics.back(); state.back() }
    }

    Box(Modifier.fillMaxSize().background(t.bg)) {
        when (state.view) {
            View.Home -> HomeScreen(settings, state)
            View.Category -> CategoryScreen(cat, accent, state) { name -> launch(cat.key, name, false) }
            View.Utility -> UtilityScreen(cat, accent, state, host)
            View.Settings -> Column(Modifier.fillMaxSize().padding(start = GUTTER)) {
                BackRow("INSTRUMENTS") { state.view = View.Home }
                SettingsScreen(settings)
            }
        }

        if (state.view != View.Settings) EdgeToolbar { haptics.tap(); state.drawerOpen = true }

        if (state.drawerOpen) {
            Drawer(
                settings = settings,
                state = state,
                onOpenSettings = { haptics.select(); state.view = View.Settings; state.drawerOpen = false },
                onRecent = { r -> launch(r.catKey, r.toolName, true) }
            )
        }

        // ---- takeover overlays: mounted for their duration, then cleared ----
        val ov = state.overlay
        if (ov != null) {
            LaunchedEffect(ov) {
                delay(if (ov is Overlay.Signal) SIGNAL_MS.toLong() else CAT_ZOOM_MS.toLong())
                if (state.overlay === ov) state.overlay = null
            }
            Box(Modifier.fillMaxSize()) {
                when (ov) {
                    is Overlay.CatZoom -> {
                        val c = Registry.category(ov.catKey)
                        CatZoomOverlay(c.motif, accentFor(c.hue, t.dark), c.code, state.staticRender)
                    }
                    is Overlay.Signal -> SignalOverlay(accent, ov.toolName, state.staticRender)
                }
            }
        }
    }
}

// ─────────────────────────────── home ───────────────────────────────

@Composable
private fun HomeScreen(settings: SettingsStore, state: ShellState) {
    val t = LocalShell.current
    val context = LocalContext.current
    val micReady = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = GUTTER, top = 18.dp, end = 18.dp, bottom = 18.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("MULTITOOL", style = Mono.eyebrow, color = t.fg40)
                Spacer(Modifier.height(7.dp))
                Text("Instruments", style = androidx.compose.material3.MaterialTheme.typography.displaySmall, color = t.fg)
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Text(if (micReady) "MIC · READY" else "MIC · ASK", style = Mono.labelMedium, color = t.fg50)
                Text(if (settings.keepAwake.value) "AWAKE" else "AUTO-SLEEP", style = Mono.labelMedium, color = t.fg50)
                Text(
                    (if (settings.autoLog.value) "LOGGING" else "IDLE") + " · " +
                        (if (settings.imageFftSize.value >= 512) "HI-RES" else "STD"),
                    style = Mono.labelMedium, color = t.fg50
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        val cats = Registry.categories
        val rows = (cats.size + 1 + 1) / 2   // +1 for the "add category" tile
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                for (c in 0 until 2) {
                    val i = r * 2 + c
                    when {
                        i < cats.size -> CategoryCard(cats[i], Modifier.weight(1f)) { state.toCategory(cats[i].key) }
                        i == cats.size -> AddCategoryTile(Modifier.weight(1f))
                        else -> Spacer(Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(11.dp))
        }
    }
}

@Composable
private fun CategoryCard(cat: Category, modifier: Modifier, onClick: () -> Unit) {
    val t = LocalShell.current
    val haptics = LocalHaptics.current
    val accent = accentFor(cat.hue, t.dark)
    Box(
        modifier
            .height(132.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(t.card)
            .border(1.dp, t.line, RoundedCornerShape(14.dp))
            .motif(cat.motif, if (t.dark) Color(1f, 1f, 1f, 0.10f) else Color(0f, 0f, 0f, 0.13f), alpha = 0.5f)
            .clickable { haptics.select(); onClick() }
            .padding(13.dp)
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .border(1.5.dp, accent, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(accent))
        }
        Column(Modifier.align(Alignment.BottomStart)) {
            Text(
                cat.name,
                style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                color = t.fg,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(7.dp))
            Text(
                if (cat.count == 1) "1 tool" else "${cat.count} tools",
                style = Mono.label, color = t.fg50
            )
        }
    }
}

@Composable
private fun AddCategoryTile(modifier: Modifier) {
    val t = LocalShell.current
    Box(
        modifier
            .height(132.dp)
            .clip(RoundedCornerShape(14.dp))
            .dashedBorder(t.line2)
            ,
        contentAlignment = Alignment.Center
    ) {
        Text("+ CATEGORY", style = Mono.labelMedium, color = t.fg30)
    }
}

private fun Modifier.dashedBorder(color: Color) = this.drawBehind {
    val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
        floatArrayOf(6f * density, 5f * density), 0f
    )
    drawRoundRect(
        color = color,
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f * density, pathEffect = dash),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(14f * density)
    )
}

// ───────────────────────────── category ─────────────────────────────

@Composable
private fun CategoryScreen(cat: Category, accent: Color, state: ShellState, onTool: (String) -> Unit) {
    val t = LocalShell.current
    Box(
        Modifier
            .fillMaxSize()
            .motif(cat.motif, if (t.dark) Color(1f, 1f, 1f, 0.10f) else Color(0f, 0f, 0f, 0.13f), alpha = 0.42f)
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(start = GUTTER, top = 16.dp, end = 18.dp)) {
                BackRow("INSTRUMENTS") { state.home() }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Text(
                        cat.name,
                        style = androidx.compose.material3.MaterialTheme.typography.headlineMedium,
                        color = t.fg,
                        modifier = Modifier.weight(1f)
                    )
                    Text(cat.code, style = Mono.labelMedium, color = accent, modifier = Modifier.padding(bottom = 4.dp))
                }
                Spacer(Modifier.height(9.dp))
                Text(cat.desc, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = t.fg50)
                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Brush.horizontalGradient(listOf(accent, t.soft)))
                )
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = GUTTER, top = 14.dp, end = 18.dp, bottom = 22.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                cat.tools.forEach { tool -> ToolRow(tool, accent) { onTool(tool.name) } }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .dashedBorder(t.line2)
                        .padding(13.dp)
                ) {
                    Text("MORE TOOLS SLOT IN HERE", style = Mono.label, color = t.fg30)
                }
            }
        }
    }
}

@Composable
private fun ToolRow(tool: Tool, accent: Color, onClick: () -> Unit) {
    val t = LocalShell.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(t.card)
            .border(1.dp, t.line, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(t.soft),
            contentAlignment = Alignment.Center
        ) { Text(tool.tag, style = Mono.tag, color = accent) }
        Column(Modifier.weight(1f)) {
            Text(tool.name, style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = t.fg)
            Spacer(Modifier.height(6.dp))
            Text(tool.meta, style = Mono.label, color = t.fg40)
        }
        Text("›", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, color = t.fg30)
    }
}

// ───────────────────────────── utility ─────────────────────────────

@Composable
private fun UtilityScreen(cat: Category, accent: Color, state: ShellState, host: ToolHost) {
    val t = LocalShell.current
    val tool = state.toolName?.let { Registry.tool(cat.key, it) }
    Column(Modifier.fillMaxSize().padding(start = EDGE)) {
        Column(Modifier.padding(start = 12.dp, top = 16.dp, end = 18.dp)) {
            BackRow(if (state.fromRecent) "INSTRUMENTS" else cat.code) { state.back() }
            Spacer(Modifier.height(15.dp))
            Text(
                tool?.name ?: "—",
                style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                color = t.fg
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Chip("ARMED", accent)
                tool?.chips?.forEach { Chip(it, t.fg50) }
            }
        }
        Box(Modifier.fillMaxSize()) {
            if (tool != null) tool.render(host)
            else Text("Tool not found", color = t.fg50, modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun Chip(label: String, color: Color) {
    val t = LocalShell.current
    Box(
        Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(t.soft)
            .padding(horizontal = 9.dp, vertical = 6.dp)
    ) { Text(label, style = Mono.labelMedium, color = color) }
}

@Composable
private fun BackRow(label: String, onClick: () -> Unit) {
    val t = LocalShell.current
    val haptics = LocalHaptics.current
    Row(
        Modifier.clickable { haptics.back(); onClick() }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text("←", style = androidx.compose.material3.MaterialTheme.typography.bodyLarge, color = t.fg50)
        Text(label, style = Mono.labelMedium, color = t.fg50)
    }
}

// ──────────────────────── edge toolbar + drawer ────────────────────────

/**
 * The design's left-edge affordance. On a real phone the left edge is the system back gesture,
 * so the strip is drawn full height but only the middle band takes taps, and that band asks the
 * system to exclude itself from the back gesture.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun EdgeToolbar(onOpen: () -> Unit) {
    val t = LocalShell.current
    Box(
        Modifier
            .fillMaxHeight()
            .width(EDGE)
            .background(Brush.horizontalGradient(listOf(t.line, t.soft, Color.Transparent)))
            .drawBehind {
                drawLine(t.line, androidx.compose.ui.geometry.Offset(size.width, 0f),
                    androidx.compose.ui.geometry.Offset(size.width, size.height), 1f * density)
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                .systemGestureExclusion()
                .clickable(onClick = onOpen),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(Modifier.width(12.dp).height(1.5.dp).background(t.fg60))
            Spacer(Modifier.height(5.dp))
            Box(Modifier.width(12.dp).height(1.5.dp).background(t.fg60))
            Spacer(Modifier.height(5.dp))
            Box(Modifier.width(8.dp).height(1.5.dp).background(t.fg40))
            Spacer(Modifier.height(16.dp))
            Box(Modifier.height(84.dp).width(12.dp), contentAlignment = Alignment.Center) {
                Text(
                    "TOOLBAR",
                    style = Mono.eyebrow,
                    color = t.fg40,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.requiredWidth(84.dp).rotate(90f)
                )
            }
        }
    }
}

@Composable
private fun Drawer(
    settings: SettingsStore,
    state: ShellState,
    onOpenSettings: () -> Unit,
    onRecent: (Recent) -> Unit
) {
    val t = LocalShell.current
    val haptics = LocalHaptics.current
    // Rendered off-device (screenshots / previews) animations never run, so start open there.
    val inspecting = LocalInspectionMode.current || state.staticRender != null
    val slide = remember { Animatable(if (inspecting) 0f else 1f) }
    LaunchedEffect(Unit) { slide.animateTo(0f, tween(260)) }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(t.scrim)
                .clickable { haptics.back(); state.drawerOpen = false }
        )
        Column(
            Modifier
                .width(274.dp)
                .fillMaxHeight()
                .graphicsLayer { translationX = -slide.value * 274.dp.toPx() }
                .background(t.drawerBg)
        ) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 18.dp, end = 16.dp, bottom = 14.dp)) {
                Text("SESSION", style = Mono.eyebrow, color = t.fg40)
                Spacer(Modifier.height(8.dp))
                Text("Multitool", style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = t.fg)
                Spacer(Modifier.height(6.dp))
                Text(
                    "${Registry.categories.size} categories · ${Registry.toolCount} tools",
                    style = Mono.label, color = t.fg40
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 20.dp)
            ) {
                Text("QUICK SETTINGS", style = Mono.eyebrow, color = t.fg40)
                Spacer(Modifier.height(10.dp))
                val quick = quickSettings(settings)
                for (r in quick.indices step 2) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (c in 0 until 2) {
                            val q = quick.getOrNull(r + c)
                            if (q != null) QuickTile(q, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Spacer(Modifier.height(14.dp))
                Text("RECENT ACTIONS", style = Mono.eyebrow, color = t.fg40)
                Spacer(Modifier.height(10.dp))
                if (state.recents.isEmpty()) {
                    Text("Nothing yet.", style = Mono.label, color = t.fg30)
                } else {
                    state.recents.forEach { r ->
                        val accent = accentFor(Registry.category(r.catKey).hue, t.dark)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onRecent(r) }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(Modifier.size(5.dp).clip(RoundedCornerShape(3.dp)).background(accent))
                            Text(
                                r.toolName,
                                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                color = t.fg80,
                                modifier = Modifier.weight(1f)
                            )
                            Text(ago(r.at), style = Mono.label, color = t.fg30)
                        }
                    }
                }

                Spacer(Modifier.height(22.dp))
                Text("SETTINGS", style = Mono.eyebrow, color = t.fg40)
                Spacer(Modifier.height(10.dp))
                DrawerRow("All settings", "THEME · DSP", onOpenSettings)
                DrawerRow("Storage & exports", "Pictures/JSA · Movies/JSA") {}
                DrawerRow("About", "v0.1") {}
            }
        }
    }
}

@Composable
private fun DrawerRow(label: String, value: String, onClick: () -> Unit) {
    val t = LocalShell.current
    val haptics = LocalHaptics.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { haptics.tap(); onClick() }
            .padding(horizontal = 8.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = t.fg80,
            modifier = Modifier.weight(1f)
        )
        Text(value, style = Mono.label, color = t.fg40)
    }
}

private class Quick(val label: String, val on: Boolean, val toggle: () -> Unit)

@Composable
private fun quickSettings(s: SettingsStore): List<Quick> = listOf(
    Quick("Dark theme", s.themeMode.value != "light") {
        s.setThemeMode(if (s.themeMode.value == "light") "dark" else "light")
    },
    Quick("Keep awake", s.keepAwake.value) { s.setKeepAwake(!s.keepAwake.value) },
    Quick("Haptics", s.haptics.value) { s.setHaptics(!s.haptics.value) },
    Quick("Grid overlay", s.showGrid.value) { s.setShowGrid(!s.showGrid.value) },
    Quick("Auto-log data", s.autoLog.value) { s.setAutoLog(!s.autoLog.value) },
    Quick("Hi-res render", s.imageFftSize.value >= 512) {
        s.setImageFftSize(if (s.imageFftSize.value >= 512) 256 else 512)
    }
)

@Composable
private fun QuickTile(q: Quick, modifier: Modifier) {
    val t = LocalShell.current
    val haptics = LocalHaptics.current
    val accent = accentFor(195f, t.dark)
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (q.on) t.soft else Color.Transparent)
            .border(1.dp, if (q.on) t.line3 else t.line, RoundedCornerShape(10.dp))
            .clickable { haptics.toggle(!q.on); q.toggle() }
            .padding(horizontal = 10.dp, vertical = 11.dp)
    ) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (q.on) accent else t.fg20)
                )
                Text(if (q.on) "ON" else "OFF", style = Mono.eyebrow, color = if (q.on) accent else t.fg30)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                q.label,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = if (q.on) t.fg else t.fg50
            )
        }
    }
}
