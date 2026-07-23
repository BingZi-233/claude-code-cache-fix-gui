package com.cachefix.gui.controller

import com.cachefix.gui.settings.ProxyMode
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors

/**
 * Local control-panel HTTP server (127.0.0.1 only).
 * Serves the JSON API and optional static files from `ui/` (if present).
 * Primary UI is Compose Desktop; this panel is for CLI `serve` / debugging.
 */
class PanelServer(
    private val preferredPort: Int = 19801,
    private val host: String = "127.0.0.1",
    uiDirOverride: Path? = null,
) {
    private var server: HttpServer? = null
    var port: Int = preferredPort
        private set
    val url: String
        get() = "http://$host:$port/"

    val uiDir: Path? = uiDirOverride ?: resolveUiDir()

    private val json = Json { ignoreUnknownKeys = true }

    fun start(): PanelServer {
        val s = HttpServer.create(InetSocketAddress(host, preferredPort), 0)
        s.executor = Executors.newCachedThreadPool { r ->
            Thread(r, "cache-fix-panel").apply { isDaemon = true }
        }
        // Single catch-all context — route API vs static inside.
        s.createContext("/") { ex ->
            try {
                applyCors(ex)
                if (ex.requestMethod.equals("OPTIONS", ignoreCase = true)) {
                    ex.sendResponseHeaders(204, -1)
                    ex.close()
                    return@createContext
                }
                handle(ex)
            } catch (e: Exception) {
                json(ex, 500, Controller.toJson(mapOf("error" to (e.message ?: e.toString()))))
            }
        }
        s.start()
        server = s
        port = s.address.port
        return this
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    private fun handle(ex: HttpExchange) {
        val rawPath = ex.requestURI.path ?: "/"
        val path = URLDecoder.decode(rawPath, StandardCharsets.UTF_8)

        if (path.startsWith("/api/")) {
            handleApi(ex, path)
            return
        }

        serveStatic(ex, path)
    }

    private fun handleApi(ex: HttpExchange, path: String) {
        val method = ex.requestMethod.uppercase()
        when {
            path == "/api/status" && method == "GET" -> {
                json(ex, 200, Controller.statusJson())
            }
            path == "/api/config" && method == "GET" -> {
                val st = Controller.loadAppState()
                json(
                    ex,
                    200,
                    Controller.toJson(
                        mapOf(
                            "proxyEnv" to st.proxyEnv,
                            "port" to st.port,
                            "mode" to st.mode.wireName(),
                            "configDirOverride" to st.configDirOverride,
                        ),
                    ),
                )
            }
            path == "/api/preview-env" && method == "GET" -> {
                json(ex, 200, Controller.toJson(mapOf("env" to Controller.previewWireEnv())))
            }
            path == "/api/logs" && method == "GET" -> {
                json(ex, 200, Controller.toJson(mapOf("lines" to Controller.getLogTail(200))))
            }
            path == "/api/discover" && method == "POST" -> {
                val launch = Controller.discover()
                json(
                    ex,
                    200,
                    Controller.toJson(
                        mapOf(
                            "launch" to launch,
                            "status" to Controller.getStatus(),
                        ),
                    ),
                )
            }
            path == "/api/start" && method == "POST" -> {
                val body = readJsonBody(ex)
                val status = Controller.startProxy(body)
                json(ex, 200, Controller.toJson(status))
            }
            path == "/api/stop" && method == "POST" -> {
                json(ex, 200, Controller.toJson(Controller.stopProxy()))
            }
            path == "/api/restart" && method == "POST" -> {
                json(ex, 200, Controller.toJson(Controller.restartProxy()))
            }
            path == "/api/wire" && method == "POST" -> {
                val result = Controller.wireClaude()
                json(
                    ex,
                    200,
                    Controller.toJson(
                        mapOf(
                            "result" to result,
                            "status" to Controller.getStatus(),
                        ),
                    ),
                )
            }
            path == "/api/unwire" && method == "POST" -> {
                val result = Controller.unwireClaude()
                json(
                    ex,
                    200,
                    Controller.toJson(
                        mapOf(
                            "result" to result,
                            "status" to Controller.getStatus(),
                        ),
                    ),
                )
            }
            path == "/api/config" && method == "POST" -> {
                val body = readJsonBody(ex)
                val allowed = mutableMapOf<String, Any?>()
                body["port"]?.let {
                    when (it) {
                        is Number -> allowed["port"] = it.toInt()
                        is String -> allowed["port"] = it.toIntOrNull()
                    }
                }
                val mode = body["mode"] as? String
                if (mode == "reverse" || mode == "forward") {
                    allowed["mode"] = ProxyMode.from(mode)
                }
                if ("configDirOverride" in body) {
                    allowed["configDirOverride"] = body["configDirOverride"] as? String
                }
                if ("explicitProxyPath" in body) {
                    allowed["explicitProxyPath"] = body["explicitProxyPath"] as? String
                }
                if (body["quitStopsProxy"] is Boolean) {
                    allowed["quitStopsProxy"] = body["quitStopsProxy"] as Boolean
                }
                @Suppress("UNCHECKED_CAST")
                val proxyEnv = body["proxyEnv"] as? Map<String, Any?>
                if (proxyEnv != null) {
                    val cleaned = linkedMapOf<String, String>()
                    for ((k, v) in proxyEnv) {
                        if (v == null || v.toString().isEmpty()) continue
                        cleaned[k] = v.toString()
                    }
                    allowed["proxyEnv"] = cleaned
                }
                val state = Controller.saveAppState(allowed)
                json(
                    ex,
                    200,
                    Controller.toJson(
                        mapOf(
                            "state" to mapOf(
                                "port" to state.port,
                                "mode" to state.mode.wireName(),
                                "proxyEnv" to state.proxyEnv,
                                "configDirOverride" to state.configDirOverride,
                                "claudeWired" to state.claudeWired,
                                "quitStopsProxy" to state.quitStopsProxy,
                            ),
                            "status" to Controller.getStatus(),
                        ),
                    ),
                )
            }
            path == "/api/shutdown" && method == "POST" -> {
                Controller.shutdown()
                json(ex, 200, """{"ok":true}""")
                // Panel process exit is owned by CLI; hook may stop server.
                Thread {
                    Thread.sleep(100)
                    stop()
                }.start()
            }
            else -> json(ex, 404, """{"error":"unknown api route"}""")
        }
    }

    private fun serveStatic(ex: HttpExchange, path: String) {
        val dir = uiDir
        if (dir == null || !Files.isDirectory(dir)) {
            // Fallback minimal page if UI assets missing
            if (path == "/" || path == "/index.html") {
                val html = FALLBACK_HTML
                val bytes = html.toByteArray(StandardCharsets.UTF_8)
                ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
                return
            }
            text(ex, 500, "UI not found — missing ui/index.html (set CACHE_FIX_GUI_UI_DIR or package ui/ next to the app)")
            return
        }

        val relative = when {
            path == "/" || path.isEmpty() -> "index.html"
            path.startsWith("/") -> path.removePrefix("/")
            else -> path
        }
        val file = dir.resolve(relative).normalize()
        if (!file.startsWith(dir.normalize())) {
            text(ex, 403, "forbidden")
            return
        }
        if (!Files.isRegularFile(file)) {
            // SPA-ish: unknown paths → 404 (API already handled)
            text(ex, 404, "not found")
            return
        }
        val bytes = Files.readAllBytes(file)
        val ext = file.fileName.toString().substringAfterLast('.', "")
        ex.responseHeaders.add("Content-Type", mime(ext))
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun readJsonBody(ex: HttpExchange): Map<String, Any?> {
        val raw = ex.requestBody.bufferedReader(StandardCharsets.UTF_8).readText()
        if (raw.isBlank()) return emptyMap()
        val element = try {
            json.parseToJsonElement(raw)
        } catch (e: Exception) {
            throw IllegalArgumentException("invalid JSON body: ${e.message}")
        }
        if (element !is JsonObject) return emptyMap()
        return jsonObjectToMap(element)
    }

    private fun jsonObjectToMap(obj: JsonObject): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        for ((k, v) in obj) {
            out[k] = when (v) {
                is JsonPrimitive -> {
                    when {
                        v.isString -> v.content
                        v.booleanOrNull != null -> v.booleanOrNull
                        v.content.toIntOrNull() != null -> v.content.toInt()
                        v.doubleOrNull != null -> v.doubleOrNull
                        else -> v.contentOrNull
                    }
                }
                is JsonObject -> jsonObjectToMap(v)
                else -> v.toString()
            }
        }
        return out
    }

    private fun applyCors(ex: HttpExchange) {
        val origin = ex.requestHeaders.getFirst("Origin")
        if (origin == null || isAllowedOrigin(origin)) {
            ex.responseHeaders.add("Access-Control-Allow-Origin", origin ?: "*")
            ex.responseHeaders.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            ex.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type")
            ex.responseHeaders.add("Access-Control-Max-Age", "600")
            ex.responseHeaders.add("Vary", "Origin")
        }
    }

    private fun isAllowedOrigin(origin: String): Boolean {
        if (origin == "null") return true
        return try {
            val u = java.net.URI(origin)
            val host = (u.host ?: "").lowercase()
            host == "127.0.0.1" || host == "localhost" || host == "::1" ||
                host.endsWith(".localhost")
        } catch (_: Exception) {
            false
        }
    }

    private fun json(ex: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.responseHeaders.add("Cache-Control", "no-store")
        if (ex.responseHeaders.getFirst("Access-Control-Allow-Origin") == null) {
            ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
        }
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun text(ex: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    companion object {
        private val FALLBACK_HTML = """
            <!doctype html>
            <html><head><meta charset="utf-8"><title>cache-fix-gui (KMP)</title></head>
            <body>
              <h1>cache-fix-gui (KMP)</h1>
              <p>UI assets not found. Place built <code>ui/</code> next to the app or set <code>CACHE_FIX_GUI_UI_DIR</code>.</p>
              <p><a href="/api/status">/api/status</a></p>
            </body></html>
        """.trimIndent()

        private fun mime(ext: String): String = when (ext.lowercase()) {
            "html", "htm" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js", "mjs" -> "text/javascript; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "ico" -> "image/x-icon"
            "json" -> "application/json"
            "map" -> "application/json"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            else -> "application/octet-stream"
        }

        /**
         * Resolve optional static UI directory containing index.html.
         * Order: env → next to jar/exe → cwd/ui → walk parents.
         */
        fun resolveUiDir(): Path? {
            val env = System.getenv("CACHE_FIX_GUI_UI_DIR")
            if (!env.isNullOrBlank()) {
                val p = Path.of(env).toAbsolutePath().normalize()
                if (Files.isRegularFile(p.resolve("index.html"))) return p
            }

            val candidates = mutableListOf<Path>()

            // Directory of the running fat jar / class location
            try {
                val codeSource = PanelServer::class.java.protectionDomain?.codeSource?.location
                if (codeSource != null) {
                    val loc = Path.of(codeSource.toURI())
                    val base = if (Files.isRegularFile(loc)) loc.parent else loc
                    if (base != null) {
                        candidates.add(base.resolve("ui"))
                        base.parent?.resolve("ui")?.let { candidates.add(it) }
                    }
                }
            } catch (_: Exception) {
                /* ignore */
            }

            val cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
            candidates.add(cwd.resolve("ui"))
            // Walk up (portable zip / monorepo)
            var walk: Path? = cwd
            repeat(5) {
                val w = walk ?: return@repeat
                candidates.add(w.resolve("ui"))
                walk = w.parent
            }

            System.getenv("CACHE_FIX_GUI_ROOT")?.let {
                candidates.add(Path.of(it).resolve("ui"))
            }

            val seen = mutableSetOf<String>()
            for (c in candidates) {
                val key = c.toAbsolutePath().normalize().toString()
                if (!seen.add(key)) continue
                if (Files.isRegularFile(c.resolve("index.html"))) {
                    return c.toAbsolutePath().normalize()
                }
            }
            return null
        }
    }
}
