package com.cachefix.gui.ui

import java.util.prefs.Preferences

/** UI-only preferences (close-to-tray etc.), persisted via Java Preferences. */
object UiPrefs {
    private val node: Preferences = Preferences.userRoot().node("com.cachefix.gui")

    var closeToTray: Boolean
        get() = node.getBoolean("closeToTray", true)
        set(value) {
            node.putBoolean("closeToTray", value)
        }

    var startInTray: Boolean
        get() = node.getBoolean("startInTray", false)
        set(value) {
            node.putBoolean("startInTray", value)
        }
}
