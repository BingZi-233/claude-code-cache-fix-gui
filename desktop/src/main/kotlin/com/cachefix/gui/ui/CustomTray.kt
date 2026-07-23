package com.cachefix.gui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Point
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import kotlin.concurrent.thread

private val MenuBg = Color(0xFFFFFFFF)
private val MenuBorder = Color(0xFFD0D5DD)
private val MenuHover = Color(0xFFE8F1FB)
private val MenuText = Color(0xFF0F172A)
private val MenuMutedLabel = Color(0xFF94A3B8)

private data class TrayMenuPlacement(
    val open: Boolean = false,
    val anchorX: Int = 0,
    val anchorY: Int = 0,
    val seq: Long = 0L,
)

/**
 * AWT tray icon + Compose popup.
 * Anchor = real cursor position via [MouseInfo] (TrayIcon mouse coords are unreliable on Windows).
 * Final placement = [java.awt.Window.setLocation] in **device pixels**.
 */
@Composable
fun CustomSystemTray(
    tooltip: String,
    onShowMain: () -> Unit,
    onHideMain: () -> Unit,
    onQuit: () -> Unit,
) {
    var placement by remember { mutableStateOf(TrayMenuPlacement()) }
    val alive = remember { AtomicBoolean(true) }
    val seq = remember { java.util.concurrent.atomic.AtomicLong(0) }

    DisposableEffect(tooltip) {
        alive.set(true)
        if (!SystemTray.isSupported()) {
            System.err.println("cache-fix: SystemTray not supported")
            onDispose { alive.set(false) }
        } else {
            val tray = SystemTray.getSystemTray()
            val image = loadTrayBufferedImage()
            val icon = TrayIcon(image, tooltip).apply { isImageAutoSize = true }

            fun cursorAnchor(): Point {
                // TrayIcon's e.xOnScreen is often wrong on Windows; MouseInfo is stable.
                return try {
                    MouseInfo.getPointerInfo()?.location ?: Point(0, 0)
                } catch (_: Exception) {
                    Point(0, 0)
                }
            }

            fun openMenuAtCursor() {
                val p = cursorAnchor()
                val id = seq.incrementAndGet()
                EventQueue.invokeLater {
                    if (!alive.get()) return@invokeLater
                    placement = TrayMenuPlacement(
                        open = true,
                        anchorX = p.x,
                        anchorY = p.y,
                        seq = id,
                    )
                }
            }

            val mouse = object : MouseAdapter() {
                override fun mouseReleased(e: MouseEvent) {
                    if (e.isPopupTrigger || e.button == MouseEvent.BUTTON3 || e.button == MouseEvent.BUTTON2) {
                        openMenuAtCursor()
                    }
                }

                override fun mousePressed(e: MouseEvent) {
                    if (e.isPopupTrigger || e.button == MouseEvent.BUTTON3) {
                        openMenuAtCursor()
                    }
                }

                override fun mouseClicked(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON1) {
                        if (e.clickCount >= 2) {
                            EventQueue.invokeLater {
                                placement = placement.copy(open = false)
                                onShowMain()
                            }
                        } else {
                            openMenuAtCursor()
                        }
                    }
                }
            }
            icon.addMouseListener(mouse)
            icon.addActionListener {
                EventQueue.invokeLater {
                    placement = placement.copy(open = false)
                    onShowMain()
                }
            }

            try {
                for (existing in tray.trayIcons.toList()) {
                    if (existing.toolTip == tooltip) {
                        try {
                            tray.remove(existing)
                        } catch (_: Exception) {
                            /* ignore */
                        }
                    }
                }
                tray.add(icon)
            } catch (e: Exception) {
                System.err.println("cache-fix: tray add failed: ${e.message}")
            }

            onDispose {
                alive.set(false)
                try {
                    tray.remove(icon)
                } catch (_: Exception) {
                    /* ignore */
                }
            }
        }
    }

    if (placement.open) {
        TrayPopupMenu(
            anchorX = placement.anchorX,
            anchorY = placement.anchorY,
            seq = placement.seq,
            onDismiss = { placement = placement.copy(open = false) },
            onShowMain = {
                placement = placement.copy(open = false)
                onShowMain()
            },
            onHideMain = {
                placement = placement.copy(open = false)
                onHideMain()
            },
            onQuit = {
                placement = placement.copy(open = false)
                onQuit()
            },
        )
    }
}

@Composable
private fun TrayPopupMenu(
    anchorX: Int,
    anchorY: Int,
    seq: Long,
    onDismiss: () -> Unit,
    onShowMain: () -> Unit,
    onHideMain: () -> Unit,
    onQuit: () -> Unit,
) {
    val menuWidthDp = 220.dp
    val menuHeightDp = 168.dp

    // PlatformDefault — we never rely on Absolute Dp; only setLocation(pixels)
    val state = rememberWindowState(
        position = WindowPosition.PlatformDefault,
        size = DpSize(menuWidthDp, menuHeightDp),
    )

    val allowDismiss = remember { AtomicBoolean(false) }
    val targetAnchor = remember { AtomicReference(Point(anchorX, anchorY)) }
    targetAnchor.set(Point(anchorX, anchorY))

    LaunchedEffect(seq) {
        allowDismiss.set(false)
        kotlinx.coroutines.delay(450)
        allowDismiss.set(true)
    }

    Window(
        onCloseRequest = onDismiss,
        state = state,
        title = "cache-fix-tray-menu",
        undecorated = true,
        resizable = false,
        alwaysOnTop = true,
        transparent = false,
        focusable = true,
        onPreviewKeyEvent = {
            if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                onDismiss()
                true
            } else {
                false
            }
        },
    ) {
        fun placeNearAnchor() {
            try {
                val w = window
                val anchor = targetAnchor.get() ?: return
                val width = w.width.coerceAtLeast(180)
                val height = w.height.coerceAtLeast(120)
                val screen = screenBoundsContaining(anchor.x, anchor.y)

                // Align menu so its bottom-right is near the cursor (typical bottom tray).
                // If tray is top, still clamps correctly.
                var x = anchor.x - width + 8
                var y = anchor.y - height - 4

                if (y < screen.y + 4) {
                    y = anchor.y + 4
                }
                if (x < screen.x + 4) {
                    x = anchor.x + 4
                }
                if (x + width > screen.x + screen.width - 4) {
                    x = screen.x + screen.width - width - 4
                }
                if (y + height > screen.y + screen.height - 4) {
                    y = screen.y + screen.height - height - 4
                }

                w.setLocation(x, y)
                w.isAlwaysOnTop = true
            } catch (e: Exception) {
                System.err.println("cache-fix: placeNearAnchor: ${e.message}")
            }
        }

        // Re-place on show, after layout, and if Compose resizes the window
        LaunchedEffect(seq, anchorX, anchorY) {
            targetAnchor.set(Point(anchorX, anchorY))
            repeat(8) {
                placeNearAnchor()
                kotlinx.coroutines.delay(20)
            }
            try {
                window.toFront()
                window.requestFocus()
            } catch (_: Exception) {
                /* ignore */
            }
        }

        DisposableEffect(seq) {
            val w = window
            val resizeListener = object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent?) {
                    placeNearAnchor()
                }

                override fun componentShown(e: ComponentEvent?) {
                    placeNearAnchor()
                }
            }
            w.addComponentListener(resizeListener)

            val focusListener = object : java.awt.event.WindowAdapter() {
                override fun windowDeactivated(e: java.awt.event.WindowEvent?) {
                    if (!allowDismiss.get()) return
                    thread(name = "tray-menu-dismiss") {
                        Thread.sleep(120)
                        if (allowDismiss.get()) {
                            EventQueue.invokeLater { onDismiss() }
                        }
                    }
                }
            }
            w.addWindowListener(focusListener)

            onDispose {
                w.removeComponentListener(resizeListener)
                w.removeWindowListener(focusListener)
            }
        }

        Box(
            Modifier
                .width(menuWidthDp)
                .shadow(12.dp, RoundedCornerShape(10.dp))
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, MenuBorder, RoundedCornerShape(10.dp))
                .background(MenuBg)
                .padding(vertical = 8.dp),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = "cache-fix 菜单",
                    color = MenuMutedLabel,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                TrayMenuItem(label = "显示主窗口", onClick = onShowMain)
                TrayMenuItem(label = "隐藏到托盘", onClick = onHideMain)
                Box(
                    Modifier
                        .padding(vertical = 6.dp, horizontal = 12.dp)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MenuBorder),
                )
                TrayMenuItem(label = "退出", danger = true, onClick = onQuit)
            }
        }
    }
}

private fun screenBoundsContaining(x: Int, y: Int): java.awt.Rectangle {
    val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
    for (device in ge.screenDevices) {
        val b = device.defaultConfiguration.bounds
        // Also account for scale? bounds are already in device pixels on Windows for AWT.
        if (b.contains(x, y)) return java.awt.Rectangle(b)
    }
    // Fallback: virtual bounds of all screens
    return java.awt.Rectangle(ge.maximumWindowBounds)
}

@Composable
private fun TrayMenuItem(
    label: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (hovered) MenuHover else Color.Transparent)
            .hoverable(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = if (danger) Color(0xFFC62828) else MenuText,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun loadTrayBufferedImage(): BufferedImage {
    val cl = Thread.currentThread().contextClassLoader
        ?: CustomSystemTrayHolder::class.java.classLoader
    for (path in listOf("tray-icon.png", "app-icon.png")) {
        val stream = cl?.getResourceAsStream(path)
            ?: CustomSystemTrayHolder::class.java.getResourceAsStream("/$path")
            ?: continue
        try {
            val img = stream.use { ImageIO.read(it) }
            if (img != null) return ensureArgb(img)
        } catch (_: Exception) {
            /* next */
        }
    }
    return placeholderIcon()
}

private object CustomSystemTrayHolder

private fun ensureArgb(src: BufferedImage): BufferedImage {
    if (src.type == BufferedImage.TYPE_INT_ARGB) return src
    val dst = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB)
    val g = dst.createGraphics()
    g.drawImage(src, 0, 0, null)
    g.dispose()
    return dst
}

private fun placeholderIcon(): BufferedImage {
    val img = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.color = java.awt.Color(0x0B, 0x6B, 0xCB)
    g.fillRoundRect(2, 2, 28, 28, 8, 8)
    g.color = java.awt.Color.WHITE
    g.fillRect(14, 8, 4, 16)
    g.fillRect(10, 18, 12, 4)
    g.dispose()
    return img
}
