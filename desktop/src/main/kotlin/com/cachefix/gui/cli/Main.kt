package com.cachefix.gui.cli

import com.cachefix.gui.controller.Controller
import com.cachefix.gui.controller.PanelServer
import com.cachefix.gui.ui.configureAwtFontsForTray
import com.cachefix.gui.ui.launchComposeGui
import java.nio.file.Files
import java.nio.file.Path

/**
 * KMP desktop entry.
 *
 * Default / `gui` / `panel` → Compose Desktop window.
 * `serve` / `web` → HTTP panel (React ui/ if present).
 * Other commands → CLI JSON.
 *
 * Working directory is NOT trusted (WSL UNC / arbitrary double-click paths).
 * All state lives under LOCALAPPDATA\cache-fix-gui-kmp (or CACHE_FIX_GUI_HOME).
 */
fun main(args: Array<String>) {
    pinAppHomeDirectory()
    configureAwtFontsForTray()

    val cmd = args.firstOrNull() ?: "gui"
    val code = try {
        when (cmd) {
            "help", "-h", "--help" -> {
                println(
                    """
                    Usage: cache-fix-gui-kmp [gui|panel|serve|status|start|stop|restart|wire|unwire|discover|help]

                      gui / panel (default)  Compose Desktop 控制台
                      serve / web            HTTP 面板 (React ui/，可选)
                      status                 打印 status JSON
                      start|stop|restart     代理生命周期
                      wire|unwire            Claude settings.env
                      discover               解析 proxy 二进制

                    Env:
                      CACHE_FIX_GUI_PORT           serve 监听端口
                      CACHE_FIX_GUI_UI_DIR         React ui/ 路径
                      CACHE_FIX_GUI_PANEL_ONESHOT  serve 启动后立即退出

                    Engine: Kotlin Multiplatform + Compose Desktop
                    """.trimIndent(),
                )
                0
            }
            "status" -> {
                println(Controller.statusJson())
                0
            }
            "discover" -> {
                val launch = Controller.discover()
                println(
                    Controller.toJson(
                        mapOf("launch" to launch, "status" to Controller.getStatus()),
                    ),
                )
                0
            }
            "start" -> {
                println(Controller.toJson(Controller.startProxy(parseOverrides(args))))
                0
            }
            "stop" -> {
                println(Controller.toJson(Controller.stopProxy()))
                0
            }
            "restart" -> {
                println(Controller.toJson(Controller.restartProxy()))
                0
            }
            "wire" -> {
                println(Controller.toJson(Controller.wireClaude()))
                0
            }
            "unwire" -> {
                println(Controller.toJson(Controller.unwireClaude()))
                0
            }
            "serve", "web" -> {
                val port = System.getenv("CACHE_FIX_GUI_PORT")?.toIntOrNull() ?: 19801
                val uiOverride = System.getenv("CACHE_FIX_GUI_UI_DIR")?.let { Path.of(it) }
                val panel = PanelServer(preferredPort = port, uiDirOverride = uiOverride).start()
                println(
                    Controller.toJson(
                        mapOf(
                            "ok" to true,
                            "url" to panel.url,
                            "port" to panel.port,
                            "uiDir" to (panel.uiDir?.toString() ?: "(none)"),
                            "engine" to "kmp-http-panel",
                            "status" to Controller.getStatus(),
                        ),
                    ),
                )
                if (System.getenv("CACHE_FIX_GUI_PANEL_ONESHOT") == "1") {
                    Thread.sleep(200)
                    panel.stop()
                    0
                } else {
                    Runtime.getRuntime().addShutdownHook(
                        Thread {
                            try {
                                Controller.shutdown()
                            } catch (_: Exception) {
                            }
                            panel.stop()
                        },
                    )
                    Thread.currentThread().join()
                    0
                }
            }
            "gui", "panel" -> {
                // Blocks until window closed
                launchComposeGui()
                0
            }
            else -> {
                System.err.println("Unknown command: $cmd (try help)")
                1
            }
        }
    } catch (e: Exception) {
        System.err.println(
            Controller.toJson(
                mapOf(
                    "error" to (e.message ?: e.toString()),
                    "phase" to "Error",
                ),
            ),
        )
        1
    }
    kotlin.system.exitProcess(code)
}

private fun parseOverrides(args: Array<String>): Map<String, Any?> {
    val patch = mutableMapOf<String, Any?>()
    var i = 1
    while (i < args.size) {
        when (args[i]) {
            "--port" -> if (i + 1 < args.size) {
                patch["port"] = args[++i].toIntOrNull()
            }
            "--mode" -> if (i + 1 < args.size) {
                patch["mode"] = args[++i]
            }
        }
        i++
    }
    return patch
}

/**
 * Decouple from process launch directory (exe may live on WSL UNC or any folder).
 * Sets cache.fix.gui.home + best-effort user.dir to a stable local path.
 */
private fun pinAppHomeDirectory() {
    val fromProp = System.getProperty("cache.fix.gui.home")
    val fromEnv = System.getenv("CACHE_FIX_GUI_HOME")
    val localApp = System.getenv("LOCALAPPDATA")
    val temp = System.getenv("TEMP") ?: System.getProperty("java.io.tmpdir")
    val homeStr = sequenceOf(fromProp, fromEnv, localApp?.let { "$it\\cache-fix-gui-kmp" }, temp?.let { "$it\\cache-fix-gui-kmp" })
        .filterNotNull()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() && !it.startsWith("\\\\") && !it.startsWith("//") }
        ?: return
    try {
        val home = Path.of(homeStr).toAbsolutePath().normalize()
        Files.createDirectories(home)
        System.setProperty("cache.fix.gui.home", home.toString())
        // Best-effort; native cwd may still be the launch folder — Controller ignores it.
        System.setProperty("user.dir", home.toString())
    } catch (_: Exception) {
        /* ignore */
    }
}
