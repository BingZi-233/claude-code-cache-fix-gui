package com.cachefix.gui.ui

import java.awt.Font
import java.awt.GraphicsEnvironment
import javax.swing.UIManager

/**
 * Windows AWT tray PopupMenu often renders CJK as □ (tofu) because the default
 * menu font has no Chinese glyphs. Pin a CJK-capable UI font before any window/tray.
 */
fun configureAwtFontsForTray() {
    try {
        val available = GraphicsEnvironment.getLocalGraphicsEnvironment()
            .availableFontFamilyNames
            .toSet()
        val family = listOf(
            "Microsoft YaHei UI",
            "Microsoft YaHei",
            "微软雅黑",
            "PingFang SC",
            "Noto Sans CJK SC",
            "Source Han Sans SC",
            "SimHei",
            "Dialog",
        ).firstOrNull { it in available } ?: Font.DIALOG

        val plain = Font(family, Font.PLAIN, 13)
        val keys = listOf(
            "Label.font",
            "Button.font",
            "ToggleButton.font",
            "Menu.font",
            "MenuItem.font",
            "CheckBoxMenuItem.font",
            "RadioButtonMenuItem.font",
            "PopupMenu.font",
            "ToolTip.font",
            "TextField.font",
            "TextArea.font",
            "ComboBox.font",
            "List.font",
            "Table.font",
            "Tree.font",
        )
        for (k in keys) {
            UIManager.put(k, plain)
        }
        // Also set default for new components
        UIManager.put("defaultFont", plain)
    } catch (_: Exception) {
        /* headless or missing GE — ignore */
    }
}
