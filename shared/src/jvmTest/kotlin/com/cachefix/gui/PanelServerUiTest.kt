package com.cachefix.gui

import com.cachefix.gui.controller.PanelServer
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises PanelServer JSON API and optional static UI serving.
 */
class PanelServerUiTest {
    @Test
    fun servesApiAndOptionalStaticUi() {
        val uiRoot = Files.createTempDirectory("cache-fix-panel-ui")
        try {
            Files.writeString(
                uiRoot.resolve("index.html"),
                """<!doctype html><html><body id="root"><script src="assets/app.js"></script></body></html>""",
            )
            val assets = Files.createDirectories(uiRoot.resolve("assets"))
            Files.writeString(assets.resolve("app.js"), "/* panel */")

            val panel = PanelServer(preferredPort = 19811, uiDirOverride = uiRoot).start()
            try {
                assertUiAndApi(panel.port)
            } finally {
                panel.stop()
            }
        } finally {
            uiRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun servesFallbackHtmlWhenNoUiDir() {
        // Non-existent override skips auto-resolve and static assets.
        val panel = PanelServer(
            preferredPort = 19812,
            uiDirOverride = Path.of("/nonexistent-cache-fix-ui-dir"),
        ).start()
        try {
            val index = httpGet(panel.port, "/")
            assertEquals(200, index.first)
            assertTrue(index.second.contains("cache-fix-gui"), "fallback page: ${index.second.take(200)}")

            val status = httpGet(panel.port, "/api/status")
            assertEquals(200, status.first)
            assertTrue(status.second.contains("\"phase\""))
        } finally {
            panel.stop()
        }
    }

    private fun assertUiAndApi(port: Int) {
        val index = httpGet(port, "/")
        assertEquals(200, index.first)
        assertTrue(
            index.second.contains("id=\"root\"") || index.second.contains("cache-fix"),
            "index shell, got: ${index.second.take(200)}",
        )
        assertTrue(index.second.contains("assets/") || index.second.contains("script"))

        val asset = httpGet(port, "/assets/app.js")
        assertEquals(200, asset.first)
        assertTrue(asset.second.isNotEmpty())

        val status = httpGet(port, "/api/status")
        assertEquals(200, status.first)
        assertTrue(status.second.contains("\"phase\""))
        assertTrue(status.second.contains("\"port\""))
        assertTrue(status.second.contains("\"mode\""))

        val preview = httpGet(port, "/api/preview-env")
        assertEquals(200, preview.first)
        assertTrue(preview.second.contains("\"env\""))

        val config = httpGet(port, "/api/config")
        assertEquals(200, config.first)
        assertTrue(config.second.contains("\"proxyEnv\""))
    }

    private fun httpGet(port: Int, path: String): Triple<Int, String, Int> {
        val url = URI("http://127.0.0.1:$port$path").toURL()
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 3000
            readTimeout = 3000
            requestMethod = "GET"
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.readText() ?: ""
        val len = body.length
        conn.disconnect()
        return Triple(code, body, len)
    }
}
