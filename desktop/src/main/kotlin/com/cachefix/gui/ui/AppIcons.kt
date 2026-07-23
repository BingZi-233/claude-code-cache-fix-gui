package com.cachefix.gui.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.loadImageBitmap
import java.io.InputStream

/**
 * Brand icons packaged under desktop/src/main/resources/.
 */
object AppIcons {
    private fun open(path: String): InputStream? =
        AppIcons::class.java.classLoader?.getResourceAsStream(path)
            ?: AppIcons::class.java.getResourceAsStream("/$path")

    fun loadBitmap(path: String): ImageBitmap {
        val stream = open(path)
            ?: error("Missing icon resource: $path (expected under desktop/src/main/resources/)")
        return stream.use { loadImageBitmap(it) }
    }

    fun painter(path: String): Painter = BitmapPainter(loadBitmap(path))
}

@Composable
fun rememberAppIconPainter(): Painter = remember {
    AppIcons.painter("app-icon.png")
}

@Composable
fun rememberTrayIconPainter(): Painter = remember {
    // Prefer dedicated tray size; fall back to full brand icon.
    try {
        AppIcons.painter("tray-icon.png")
    } catch (_: Exception) {
        AppIcons.painter("app-icon.png")
    }
}
