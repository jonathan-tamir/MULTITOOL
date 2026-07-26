package com.jonathan.multitool.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class View { Home, Category, Utility, Settings }

class Recent(val catKey: String, val toolName: String, val at: Long)

/** Navigation + overlay state. Mirrors the design comp's state object 1:1. */
class ShellState {
    var view by mutableStateOf(View.Home)
    var catKey by mutableStateOf("sound")
    var toolName by mutableStateOf<String?>(null)
    var drawerOpen by mutableStateOf(false)
    var fromRecent by mutableStateOf(false)

    /** null = no overlay; otherwise the takeover currently playing. */
    var overlay by mutableStateOf<Overlay?>(null)

    val recents = mutableStateListOf<Recent>()

    fun toCategory(key: String) {
        catKey = key
        view = View.Category
        drawerOpen = false
        fromRecent = false
        overlay = Overlay.CatZoom(key)
    }

    fun toTool(catKey: String, name: String, fromRecent: Boolean = false) {
        this.catKey = catKey
        toolName = name
        view = View.Utility
        drawerOpen = false
        this.fromRecent = fromRecent
        recents.removeAll { it.toolName == name }
        recents.add(0, Recent(catKey, name, System.currentTimeMillis()))
        while (recents.size > 4) recents.removeAt(recents.size - 1)
        overlay = Overlay.Signal(name)
    }

    fun home() {
        view = View.Home
        fromRecent = false
    }

    /** True if the event was consumed (i.e. don't let the system handle back). */
    fun back(): Boolean = when {
        drawerOpen -> { drawerOpen = false; true }
        view == View.Settings -> { view = View.Home; true }
        view == View.Utility -> {
            if (fromRecent) home() else { view = View.Category; overlay = Overlay.CatZoom(catKey) }
            true
        }
        view == View.Category -> { home(); true }
        else -> false
    }
}

sealed interface Overlay {
    class CatZoom(val catKey: String) : Overlay
    class Signal(val toolName: String) : Overlay
}

fun ago(millis: Long): String {
    val s = (System.currentTimeMillis() - millis) / 1000
    return when {
        s < 45 -> "now"
        s < 3600 -> "${s / 60}m"
        s < 86_400 -> "${s / 3600}h"
        else -> "${s / 86_400}d"
    }
}
