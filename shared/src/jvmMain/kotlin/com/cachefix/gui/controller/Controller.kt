package com.cachefix.gui.controller

import com.cachefix.gui.health.HealthKind
import com.cachefix.gui.health.HealthResult
import com.cachefix.gui.health.parseCacheFixHealth
import com.cachefix.gui.io.loadSettings
import com.cachefix.gui.io.resolvePaths
import com.cachefix.gui.io.unwireClaudeSettings
import com.cachefix.gui.io.wireClaudeSettings
import com.cachefix.gui.proxy.COMPATIBLE_RANGE
import com.cachefix.gui.proxy.ProxyCandidate
import com.cachefix.gui.proxy.selectProxy
import com.cachefix.gui.proxy.satisfiesCompatible
import com.cachefix.gui.settings.DEFAULT_PORT
import com.cachefix.gui.settings.KEY_ANTHROPIC_BASE_URL
import com.cachefix.gui.settings.ProxyMode
import com.cachefix.gui.settings.computeExpectedEnv
import com.cachefix.gui.spawn.buildProxySpawnEnv
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Proxy lifecycle + Claude wire state machine (I/O).
 * JVM implementation of the Node controller semantics for status / wire / spawn-env.
 */
object Controller {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val appDir: Path
        get() = Path.of(System.getProperty("user.home"), ".cache-fix-gui")
    private val stateFile: Path
        get() = appDir.resolve("state.json")
    private val logFile: Path
        get() = appDir.resolve("proxy.log")

    @Volatile
    private var phase: String = "Stopped"

    @Volatile
    private var lastError: String = ""

    @Volatile
    private var managedChild: Boolean = false

    @Volatile
    private var childPid: Long? = null

    @Volatile
    private var childProcess: Process? = null

    /** Set by stopProxy so a concurrent start loop can abort and tear down. */
    @Volatile
    private var stopRequested: Boolean = false

    @Volatile
    private var launchInfo: Map<String, Any?>? = null

    private val logBuffer = ConcurrentLinkedQueue<String>()
    private const val MAX_LOG = 500

    data class AppState(
        val port: Int = DEFAULT_PORT,
        val mode: ProxyMode = ProxyMode.REVERSE,
        val configDirOverride: String? = null,
        val explicitProxyPath: String? = null,
        val expectedEnv: Map<String, String>? = null,
        val anthropicBaseUrlBackup: String? = null,
        val anthropicFoundryBaseUrlBackup: String? = null,
        val claudeWired: Boolean = false,
        val quitStopsProxy: Boolean = true,
        val proxyEnv: Map<String, String> = emptyMap(),
    )

    private fun ensureAppDir() {
        Files.createDirectories(appDir)
    }

    fun loadAppState(): AppState {
        ensureAppDir()
        if (!Files.exists(stateFile)) return AppState()
        return try {
            val raw = Files.readString(stateFile, StandardCharsets.UTF_8)
            val obj = json.parseToJsonElement(raw) as? JsonObject ?: return AppState()
            AppState(
                port = (obj["port"] as? JsonPrimitive)?.content?.toIntOrNull() ?: DEFAULT_PORT,
                mode = ProxyMode.from(
                    (obj["mode"] as? JsonPrimitive)?.contentOrNull ?: "reverse",
                ),
                configDirOverride = (obj["configDirOverride"] as? JsonPrimitive)?.contentOrNull,
                explicitProxyPath = (obj["explicitProxyPath"] as? JsonPrimitive)?.contentOrNull,
                expectedEnv = (obj["expectedEnv"] as? JsonObject)?.mapValues {
                    (it.value as? JsonPrimitive)?.contentOrNull ?: it.value.toString()
                },
                anthropicBaseUrlBackup = (obj["anthropicBaseUrlBackup"] as? JsonPrimitive)?.contentOrNull,
                anthropicFoundryBaseUrlBackup =
                    (obj["anthropicFoundryBaseUrlBackup"] as? JsonPrimitive)?.contentOrNull,
                claudeWired = (obj["claudeWired"] as? JsonPrimitive)?.contentOrNull == "true",
                quitStopsProxy = (obj["quitStopsProxy"] as? JsonPrimitive)?.contentOrNull != "false",
                proxyEnv = (obj["proxyEnv"] as? JsonObject)?.mapValues {
                    (it.value as? JsonPrimitive)?.contentOrNull ?: ""
                }?.filterValues { it.isNotEmpty() } ?: emptyMap(),
            )
        } catch (_: Exception) {
            AppState()
        }
    }

    fun saveAppState(patch: Map<String, Any?>): AppState {
        val cur = loadAppState()
        val next = AppState(
            port = (patch["port"] as? Number)?.toInt()
                ?: (patch["port"] as? String)?.toIntOrNull()
                ?: cur.port,
            mode = when (val m = patch["mode"]) {
                is ProxyMode -> m
                is String -> ProxyMode.from(m)
                else -> cur.mode
            },
            configDirOverride = if ("configDirOverride" in patch) {
                patch["configDirOverride"] as? String
            } else cur.configDirOverride,
            explicitProxyPath = if ("explicitProxyPath" in patch) {
                patch["explicitProxyPath"] as? String
            } else cur.explicitProxyPath,
            expectedEnv = if ("expectedEnv" in patch) {
                @Suppress("UNCHECKED_CAST")
                patch["expectedEnv"] as? Map<String, String>
            } else cur.expectedEnv,
            anthropicBaseUrlBackup = if ("anthropicBaseUrlBackup" in patch) {
                patch["anthropicBaseUrlBackup"] as? String
            } else cur.anthropicBaseUrlBackup,
            anthropicFoundryBaseUrlBackup = if ("anthropicFoundryBaseUrlBackup" in patch) {
                patch["anthropicFoundryBaseUrlBackup"] as? String
            } else cur.anthropicFoundryBaseUrlBackup,
            claudeWired = (patch["claudeWired"] as? Boolean) ?: cur.claudeWired,
            quitStopsProxy = (patch["quitStopsProxy"] as? Boolean) ?: cur.quitStopsProxy,
            proxyEnv = if ("proxyEnv" in patch) {
                @Suppress("UNCHECKED_CAST")
                (patch["proxyEnv"] as? Map<String, String>) ?: emptyMap()
            } else cur.proxyEnv,
        )
        ensureAppDir()
        val obj = buildJsonObject {
            put("port", next.port)
            put("mode", next.mode.wireName())
            put("configDirOverride", next.configDirOverride)
            put("explicitProxyPath", next.explicitProxyPath)
            put("claudeWired", next.claudeWired)
            put("quitStopsProxy", next.quitStopsProxy)
            put("anthropicBaseUrlBackup", next.anthropicBaseUrlBackup)
            put("anthropicFoundryBaseUrlBackup", next.anthropicFoundryBaseUrlBackup)
            putJsonObject("proxyEnv") {
                for ((k, v) in next.proxyEnv) put(k, v)
            }
            if (next.expectedEnv != null) {
                putJsonObject("expectedEnv") {
                    for ((k, v) in next.expectedEnv) put(k, v)
                }
            }
        }
        Files.writeString(stateFile, json.encodeToString(JsonObject.serializer(), obj) + "\n")
        return next
    }

    private fun appendLog(line: String) {
        val row = "[${java.time.Instant.now()}] $line"
        logBuffer.add(row)
        while (logBuffer.size > MAX_LOG) logBuffer.poll()
        try {
            ensureAppDir()
            Files.writeString(
                logFile,
                "$row\n",
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND,
            )
        } catch (_: Exception) {
            /* ignore */
        }
    }

    fun fetchHealth(port: Int): Pair<Int?, String?> {
        return try {
            val url = URI("http://127.0.0.1:$port/health").toURL()
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 2000
                readTimeout = 2000
                requestMethod = "GET"
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.readText()
            conn.disconnect()
            code to body
        } catch (_: Exception) {
            null to null
        }
    }

    fun probeHealth(port: Int = loadAppState().port): HealthResult {
        val (status, body) = fetchHealth(port)
        return parseCacheFixHealth(status, body)
    }

    fun getStatus(): Map<String, Any?> {
        val state = loadAppState()
        val health = probeHealth(state.port)
        when (health.kind) {
            HealthKind.OK -> {
                phase = if (managedChild) "Running" else "Attached"
                lastError = ""
            }
            HealthKind.DEGRADED -> phase = "Degraded"
            HealthKind.FOREIGN -> {
                if (!managedChild || childProcess?.isAlive != true) {
                    phase = "Error"
                    lastError = "Port ${state.port} has a non-cache-fix listener"
                }
            }
            HealthKind.UNREACHABLE -> {
                if (phase != "Starting" && (!managedChild || childProcess?.isAlive != true)) {
                    if (phase != "Error") phase = "Stopped"
                }
            }
        }

        val paths = resolvePaths(
            System.getenv(),
            System.getProperty("user.home"),
            state.configDirOverride,
        )
        val suggestedUpstream = resolveSuggestedUpstream(state, paths)

        return linkedMapOf(
            "phase" to phase,
            "lastError" to lastError,
            "port" to state.port,
            "mode" to state.mode.wireName(),
            "claudeWired" to state.claudeWired,
            "quitStopsProxy" to state.quitStopsProxy,
            "proxyEnv" to state.proxyEnv,
            "anthropicBaseUrlBackup" to state.anthropicBaseUrlBackup,
            "suggestedUpstream" to suggestedUpstream,
            "managedChild" to managedChild,
            "pid" to childPid,
            "health" to linkedMapOf(
                "kind" to health.kind.wireName(),
                "version" to health.version,
                "forwardProxy" to health.forwardProxy,
                "httpStatus" to health.httpStatus,
                "hint" to health.hint,
            ),
            "launch" to launchInfo,
            "compatibleRange" to COMPATIBLE_RANGE,
            "paths" to linkedMapOf(
                "configRoot" to paths.configRoot,
                "settingsFile" to paths.settingsFile,
                "caPem" to paths.caPem,
                "appState" to stateFile.toString(),
                "logFile" to logFile.toString(),
            ),
            "logTail" to logBuffer.toList().takeLast(80),
            "engine" to "kmp-jvm",
        )
    }

    /**
     * Prefer explicit CACHE_FIX_PROXY_UPSTREAM; else Claude ANTHROPIC_BASE_URL backup / live
     * settings when that value is not the local reverse-proxy URL.
     */
    private fun resolveSuggestedUpstream(state: AppState, paths: com.cachefix.gui.io.ClaudePaths): String? {
        state.proxyEnv["CACHE_FIX_PROXY_UPSTREAM"]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        state.anthropicBaseUrlBackup?.trim()?.takeIf { it.isNotEmpty() && !isLocalProxyUrl(it, state.port) }
            ?.let { return it }
        return try {
            val settings = loadSettings(paths.settingsFile)
            @Suppress("UNCHECKED_CAST")
            val env = settings["env"] as? Map<*, *>
            val base = env?.get(KEY_ANTHROPIC_BASE_URL)?.toString()?.trim().orEmpty()
            if (base.isNotEmpty() && !isLocalProxyUrl(base, state.port)) base else null
        } catch (_: Exception) {
            null
        }
    }

    private fun isLocalProxyUrl(url: String, port: Int): Boolean {
        val p = port.toString()
        val normalized = url.trim().trimEnd('/')
        return normalized in setOf(
            "http://127.0.0.1:$p",
            "http://localhost:$p",
            "https://127.0.0.1:$p",
            "https://localhost:$p",
        )
    }

    fun discover(): Map<String, Any?>? {
        phase = "Discovering"
        val state = loadAppState()
        val candidates = mutableListOf<ProxyCandidate>()
        val env = System.getenv()
        val tried = mutableListOf<String>()

        // 1) Explicit path (settings)
        val explicit = state.explicitProxyPath
        if (!explicit.isNullOrBlank()) {
            tried.add("explicit=$explicit")
            pushPackageOrBinary(candidates, "explicit", explicit, forceCompatible = true)
        }

        // 1b) Env package root
        env["CACHE_FIX_GUI_PROXY_ROOT"]?.takeIf { it.isNotBlank() }?.let { root ->
            tried.add("CACHE_FIX_GUI_PROXY_ROOT=$root")
            pushPackageOrBinary(candidates, "explicit", root, forceCompatible = true)
        }

        // 2) cache-fix-proxy / claude-via-proxy on PATH
        for (bin in listOf("cache-fix-proxy", "cache-fix-proxy.cmd", "claude-via-proxy")) {
            val hit = which(bin)
            tried.add("PATH $bin → ${hit ?: "miss"}")
            if (hit != null) {
                val ver = npmGlobalPackageRoot()?.let { readPackageVersion(it) }
                candidates.add(
                    ProxyCandidate(
                        source = "path",
                        path = hit,
                        version = ver,
                        compatible = ver?.let { satisfiesCompatible(it) } ?: true,
                    ),
                )
            }
        }

        // 3) npm global package roots (cmd /c npm on Windows)
        for (root in npmGlobalRoots()) {
            tried.add("npm-root=$root")
            pushPackageOrBinary(
                candidates,
                "npm-global",
                root.resolve("claude-code-cache-fix").toString(),
                forceCompatible = false,
            )
        }

        // 3b) Heuristic scan: nvmd / nvm / AppData npm global folders
        for (root in heuristicPackageRoots()) {
            tried.add("scan=$root")
            pushPackageOrBinary(candidates, "npm-global", root.toString(), forceCompatible = false)
        }

        // 4) Sidecars
        val sidecarCandidates = buildList {
            env["CACHE_FIX_SIDECAR"]?.let { add(it) }
            env["CACHE_FIX_GUI_ROOT"]?.let { root ->
                safePath(root)?.resolve("sidecar")?.resolve("claude-code-cache-fix")
                    ?.toString()
                    ?.let { add(it) }
            }
            val cwd = safeUserDir()
            if (cwd != null) {
                add(cwd.resolve("sidecar").resolve("claude-code-cache-fix").toString())
            }
            // Next to extracted jar / LOCALAPPDATA app dir
            localAppDataDir()?.let {
                add(it.resolve("sidecar").resolve("claude-code-cache-fix").toString())
            }
        }
        for (root in sidecarCandidates) {
            tried.add("sidecar=$root")
            pushPackageOrBinary(candidates, "sidecar", root, forceCompatible = true)
        }

        appendLog("Discover tried ${tried.size} probes, candidates=${candidates.size}")
        for (c in candidates) {
            appendLog("  candidate source=${c.source} path=${c.path} ver=${c.version} ok=${c.compatible}")
        }

        val selected = selectProxy(candidates)
        if (selected == null) {
            lastError =
                "No compatible cache-fix proxy found.\n" +
                    "Install: npm i -g claude-code-cache-fix@^4.3.0\n" +
                    "Or set 设置 → 显式 proxy 路径 to the package folder\n" +
                    "(must contain proxy/server.mjs).\n" +
                    "Also try: set env CACHE_FIX_GUI_PROXY_ROOT.\n" +
                    "Probes: ${tried.takeLast(8).joinToString(" | ")}"
            phase = "Error"
            launchInfo = null
            appendLog(lastError.replace('\n', ' '))
            return null
        }

        lastError = ""
        phase = "Stopped"
        val info = linkedMapOf<String, Any?>(
            "source" to selected.source,
            "path" to selected.path,
            "version" to selected.version,
        )
        launchInfo = info
        appendLog("Discovered proxy source=${selected.source} version=${selected.version ?: "?"} path=${selected.path}")
        return info
    }

    /** True if path is a cache-fix package root we can launch. */
    private fun isPackageRoot(p: Path): Boolean =
        Files.exists(p.resolve("proxy").resolve("server.mjs")) ||
            Files.exists(p.resolve("bin").resolve("claude-via-proxy.mjs"))

    private fun pushPackageOrBinary(
        candidates: MutableList<ProxyCandidate>,
        source: String,
        pathStr: String,
        forceCompatible: Boolean,
    ) {
        val p = safePath(pathStr) ?: return
        if (!Files.exists(p)) return

        val packageRoot = when {
            isPackageRoot(p) -> p
            Files.isRegularFile(p) -> p.parent // bin next to package?
            else -> p
        } ?: return

        // If user pointed at a file, still accept as path candidate
        if (Files.isRegularFile(p) && !isPackageRoot(packageRoot)) {
            candidates.add(
                ProxyCandidate(
                    source = source,
                    path = p.toString(),
                    version = readPackageVersion(p.parent ?: p),
                    compatible = true,
                ),
            )
            return
        }

        val root = if (isPackageRoot(p)) p else if (isPackageRoot(packageRoot)) packageRoot else return
        val ver = readPackageVersion(root)
        val compatible = when {
            forceCompatible || source == "sidecar" -> true
            ver != null -> satisfiesCompatible(ver)
            source == "explicit" -> true
            else -> false
        }
        candidates.add(
            ProxyCandidate(
                source = source,
                path = root.toString(),
                version = ver,
                compatible = compatible,
            ),
        )
    }

    /** npm global node_modules directories (may be multiple under nvm/nvmd). */
    private fun npmGlobalRoots(): List<Path> {
        val roots = linkedSetOf<Path>()
        // Windows: must use cmd /c for npm.cmd
        for (args in listOf(
            listOf("root", "-g"),
            listOf("prefix", "-g"),
        )) {
            val out = runCapture("npm", args)
            for (line in out.lineSequence()) {
                val t = line.trim()
                if (!looksLikeFsPath(t)) continue
                val p = safePath(t) ?: continue
                val nodeModules = when {
                    Files.isDirectory(p) && p.fileName.toString() == "node_modules" -> p
                    Files.isDirectory(p.resolve("node_modules")) -> p.resolve("node_modules")
                    Files.isDirectory(p) -> p // already node_modules or prefix
                    else -> null
                } ?: continue
                // prefix -g returns prefix; root -g returns .../node_modules
                val root = if (nodeModules.fileName.toString() == "node_modules") {
                    nodeModules
                } else if (Files.isDirectory(nodeModules.resolve("node_modules"))) {
                    nodeModules.resolve("node_modules")
                } else {
                    nodeModules
                }
                if (Files.isDirectory(root)) roots.add(root.toAbsolutePath().normalize())
            }
        }
        return roots.toList()
    }

    private fun npmGlobalPackageRoot(): Path? =
        npmGlobalRoots().firstNotNullOfOrNull { root ->
            val pkg = root.resolve("claude-code-cache-fix")
            if (isPackageRoot(pkg)) pkg else null
        }

    /**
     * Walk common Windows install locations for nvmd / nvm / AppData npm.
     * User error showed: %USERPROFILE%\.nvmd\versions\...\node_modules\claude-code-cache-fix
     */
    private fun heuristicPackageRoots(): List<Path> {
        val found = linkedSetOf<Path>()
        val homes = listOfNotNull(
            System.getenv("USERPROFILE"),
            System.getenv("HOME"),
            System.getProperty("user.home"),
            System.getenv("APPDATA"),
            System.getenv("LOCALAPPDATA"),
        ).mapNotNull { safePath(it) }.distinct()

        val relCandidates = listOf(
            // npm default global (Windows)
            listOf("npm", "node_modules", "claude-code-cache-fix"),
            listOf("Roaming", "npm", "node_modules", "claude-code-cache-fix"),
            // nvmd
            listOf(".nvmd", "versions"),
            // nvm-windows
            listOf("AppData", "Roaming", "nvm"),
            listOf(".nvm", "versions", "node"),
        )

        for (home in homes) {
            // Direct npm global
            val npmPkg = home.resolve("AppData").resolve("Roaming").resolve("npm")
                .resolve("node_modules").resolve("claude-code-cache-fix")
            if (isPackageRoot(npmPkg)) found.add(npmPkg)

            val npmPkg2 = home.resolve("npm").resolve("node_modules").resolve("claude-code-cache-fix")
            if (isPackageRoot(npmPkg2)) found.add(npmPkg2)

            // nvmd: ~/.nvmd/versions/<ver>/node_modules/claude-code-cache-fix
            // or ~/.nvmd/versions/node/<ver>/...
            val nvmdVersions = home.resolve(".nvmd").resolve("versions")
            if (Files.isDirectory(nvmdVersions)) {
                try {
                    Files.walk(nvmdVersions, 4).use { stream ->
                        stream.filter { it.fileName.toString() == "claude-code-cache-fix" }
                            .filter { isPackageRoot(it) }
                            .limit(8)
                            .forEach { found.add(it) }
                    }
                } catch (_: Exception) {
                    /* ignore walk errors */
                }
            }

            // nvm-windows versions
            val nvm = System.getenv("NVM_HOME")?.let { safePath(it) }
                ?: home.resolve("AppData").resolve("Roaming").resolve("nvm")
            if (Files.isDirectory(nvm)) {
                try {
                    Files.walk(nvm, 5).use { stream ->
                        stream.filter { it.fileName.toString() == "claude-code-cache-fix" }
                            .filter { isPackageRoot(it) }
                            .limit(8)
                            .forEach { found.add(it) }
                    }
                } catch (_: Exception) {
                    /* ignore */
                }
            }
        }
        return found.toList()
    }

    fun startProxy(overrides: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        stopRequested = false
        val state = if (overrides.isEmpty()) loadAppState() else saveAppState(overrides)
        val port = state.port
        val mode = state.mode
        val paths = resolvePaths(
            System.getenv(),
            System.getProperty("user.home"),
            state.configDirOverride,
        )
        val caPemPath = Path.of(paths.caPem)

        val existing = probeHealth(port)
        if (existing.kind == HealthKind.OK || existing.kind == HealthKind.DEGRADED) {
            if (healthModeMatches(existing, mode, caPemPath)) {
                managedChild = false
                phase = if (existing.kind == HealthKind.DEGRADED) "Degraded" else "Attached"
                lastError = ""
                appendLog(
                    "Attached to existing proxy on :$port " +
                        "(${existing.kind.wireName()}, forward_proxy=${existing.forwardProxy})",
                )
                autoWireClaudeOrThrow()
                return getStatus()
            }
            // Mode mismatch (or forward without CA): unwire old mode, tear down, start clean.
            appendLog(
                "Port $port mode mismatch (want ${mode.wireName()}, " +
                    "health forward_proxy=${existing.forwardProxy}) — restarting",
            )
            phase = "Starting"
            lastError = "端口模式不匹配，正在按 ${mode.wireName()} 重启…"
            // Drop previous mode's Claude env before applying the new one.
            autoUnwireClaudeOrNote()
            killManagedAndPort(port)
            waitPortFree(port, timeoutMs = 5_000)
        } else if (existing.kind == HealthKind.FOREIGN) {
            phase = "Error"
            lastError = "Port $port occupied by non-cache-fix service"
            throw IllegalStateException(lastError)
        }

        var launch = discover()
        if (launch == null) {
            appendLog("Proxy not found — attempting npm install -g claude-code-cache-fix@^4.3.0")
            phase = "Starting"
            lastError = "正在自动安装 claude-code-cache-fix…"
            try {
                installProxyPackage()
            } catch (e: Exception) {
                phase = "Error"
                lastError = "自动安装失败: ${e.message}\n请手动: npm i -g claude-code-cache-fix@^4.3.0"
                throw IllegalStateException(lastError)
            }
            launch = discover()
                ?: throw IllegalStateException(
                    lastError.ifEmpty {
                        "proxy not found after install — set 设置 → 显式 proxy 路径"
                    },
                )
        }
        if (stopRequested) {
            phase = "Stopped"
            return getStatus()
        }
        // Forward mode needs openssl to mint the MITM CA; without it the proxy
        // never sets forward_proxy=true and start would silently time out (25s).
        if (mode == ProxyMode.FORWARD && !Files.isRegularFile(caPemPath)) {
            preflightOpensslOrThrow()
        }
        val spawnEnv = buildProxySpawnEnv(
            port = port,
            mode = mode,
            effectiveConfigRoot = paths.configRoot,
            caDir = paths.caDir,
            baseEnv = System.getenv(),
            extraEnv = state.proxyEnv,
        )

        phase = "Starting"
        val command = resolveLaunchCommand(launch)
        appendLog(
            "Starting ${command.joinToString(" ")} mode=${mode.wireName()} port=$port " +
                "FORWARD=${spawnEnv["CACHE_FIX_FORWARD_PROXY"] ?: "off"} " +
                "CA_DIR=${spawnEnv["CACHE_FIX_CA_DIR"]}",
        )

        val workDir = appHomeDir()?.toFile() ?: appDir.toFile()
        val pb = ProcessBuilder(command)
            .redirectErrorStream(true)
            .directory(workDir)
        // Do not clear() env on Windows — can drop required system vars if map keys differ by case.
        val envMap = pb.environment()
        for ((k, v) in spawnEnv) {
            envMap[k] = v
        }
        // spawnEnv omits CACHE_FIX_FORWARD_PROXY in reverse; still must remove inherited parent value
        // or reverse mode would silently start as forward.
        if (mode == ProxyMode.REVERSE) {
            envMap.remove("CACHE_FIX_FORWARD_PROXY")
        } else {
            envMap["CACHE_FIX_FORWARD_PROXY"] = "on"
        }
        val proc = pb.start()
        childProcess = proc
        childPid = proc.pid()
        managedChild = true

        Thread {
            try {
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { appendLog("[out] $it") }
                }
            } catch (_: Exception) {
                /* process closed */
            }
        }.apply { isDaemon = true; start() }

        // Forward needs openssl CA mint + health.forward_proxy=true; allow more time.
        val waitMs = if (mode == ProxyMode.FORWARD) 25_000L else 15_000L
        val deadline = System.currentTimeMillis() + waitMs
        var lastHealth: HealthResult? = null
        while (System.currentTimeMillis() < deadline) {
            if (stopRequested) {
                appendLog("Start aborted by stop request")
                killManagedAndPort(port)
                phase = "Stopped"
                lastError = ""
                return getStatus()
            }
            Thread.sleep(200)
            val h = probeHealth(port)
            lastHealth = h
            if (healthModeMatches(h, mode, caPemPath)) {
                phase = if (h.kind == HealthKind.DEGRADED) "Degraded" else "Running"
                lastError = ""
                appendLog(
                    "Proxy running on :$port pid=$childPid mode=${mode.wireName()} " +
                        "forward_proxy=${h.forwardProxy}",
                )
                autoWireClaudeOrThrow()
                return getStatus()
            }
            // Healthy but wrong mode → keep waiting only briefly; usually a bug or leftover.
            if (!proc.isAlive) {
                phase = "Error"
                lastError = "Proxy exited before ready (code ${runCatching { proc.exitValue() }.getOrNull()})"
                managedChild = false
                childProcess = null
                childPid = null
                throw IllegalStateException(lastError)
            }
        }
        killManagedAndPort(port)
        phase = "Error"
        lastError = buildStartTimeoutMessage(mode, lastHealth, caPemPath, waitMs)
        throw IllegalStateException(lastError)
    }

    /**
     * True when /health matches the GUI-selected mode and (for forward) CA is on disk.
     * Upstream reports forward_proxy only after attachForwardProxy() succeeds (openssl CA ready).
     */
    private fun healthModeMatches(h: HealthResult, mode: ProxyMode, caPemPath: Path): Boolean {
        if (h.kind != HealthKind.OK && h.kind != HealthKind.DEGRADED) return false
        return when (mode) {
            ProxyMode.FORWARD -> h.forwardProxy == true && Files.isRegularFile(caPemPath)
            ProxyMode.REVERSE -> h.forwardProxy != true // null (old proxy) or false
        }
    }

    private fun waitPortFree(port: Int, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val h = probeHealth(port)
            if (h.kind == HealthKind.UNREACHABLE) return
            Thread.sleep(150)
        }
        // Last-resort kill again
        killProcessesOnPort(port)
        Thread.sleep(200)
    }

    private fun buildStartTimeoutMessage(
        mode: ProxyMode,
        lastHealth: HealthResult?,
        caPemPath: Path,
        waitMs: Long,
    ): String {
        val secs = waitMs / 1000
        if (mode == ProxyMode.FORWARD) {
            val fp = lastHealth?.forwardProxy
            val caOk = Files.isRegularFile(caPemPath)
            return buildString {
                append("正向代理在 ${secs}s 内未就绪")
                append(" (health=${lastHealth?.kind?.wireName() ?: "none"}")
                append(", forward_proxy=$fp, ca.pem=${if (caOk) "ok" else "missing"})")
                append("。请确认: 1) PATH 有 openssl  2) 端口未被占用")
                append("  3) 日志中无 forward-proxy FAILED")
            }
        }
        return "Proxy failed to become healthy within ${secs}s"
    }

    /**
     * Stop proxy. Always tries to free the listen port (Windows needs process-tree kill).
     * Works for both GUI-managed children and "Attached" listeners on our port.
     */
    fun stopProxy(force: Boolean = false): Map<String, Any?> {
        stopRequested = true
        val port = loadAppState().port
        appendLog("Stop requested (force=$force) port=$port managed=$managedChild pid=$childPid")

        // Restore Claude settings first (even if process kill is messy)
        autoUnwireClaudeOrNote()

        killManagedAndPort(port)

        // Confirm port is free; retry once if still healthy
        Thread.sleep(250)
        var health = probeHealth(port)
        if (health.kind == HealthKind.OK || health.kind == HealthKind.DEGRADED) {
            appendLog("Port $port still alive after first kill — retry")
            killProcessesOnPort(port)
            Thread.sleep(400)
            health = probeHealth(port)
        }

        childProcess = null
        managedChild = false
        childPid = null
        phase = "Stopped"
        if (health.kind == HealthKind.OK || health.kind == HealthKind.DEGRADED) {
            lastError = "停止后端口 $port 仍有 cache-fix 在监听，请检查任务管理器中的 node 进程"
            appendLog(lastError)
        } else {
            if (lastError.isBlank() || lastError.contains("自动恢复") || lastError.contains("自动写入")) {
                // keep wire/unwire notes if any; otherwise clear
            }
            if (!lastError.contains("自动")) {
                lastError = ""
            }
            appendLog("Proxy stopped (port $port clear)")
        }
        // Return snapshot without getStatus() re-promoting phase to Attached/Running
        return statusSnapshotStopped(port)
    }

    private fun killManagedAndPort(port: Int) {
        val proc = childProcess
        val pid = childPid ?: try {
            proc?.pid()
        } catch (_: Exception) {
            null
        }
        if (proc != null || pid != null) {
            appendLog("Killing managed process tree pid=$pid")
            killProcessTree(pid, proc)
        }
        val killed = killProcessesOnPort(port)
        if (killed.isNotEmpty()) {
            appendLog("Killed port $port PIDs: ${killed.joinToString(",")}")
        }
        childProcess = null
        managedChild = false
        childPid = null
    }

    private fun killProcessTree(pid: Long?, proc: Process?) {
        if (isWindows() && pid != null && pid > 0) {
            // /T = kill child processes (node tree); /F = force
            runCapture("taskkill", listOf("/PID", pid.toString(), "/T", "/F"), timeoutSec = 8)
        }
        try {
            proc?.destroy()
        } catch (_: Exception) {
            /* ignore */
        }
        try {
            if (proc != null && !proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            }
        } catch (_: Exception) {
            try {
                proc?.destroyForcibly()
            } catch (_: Exception) {
                /* ignore */
            }
        }
        if (!isWindows() && pid != null && pid > 0) {
            try {
                ProcessBuilder("kill", "-TERM", pid.toString()).start().waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                ProcessBuilder("kill", "-KILL", pid.toString()).start().waitFor(1, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: Exception) {
                /* ignore */
            }
        }
    }

    /**
     * Find LISTENING PIDs on TCP port and force-kill (Windows taskkill /T).
     */
    fun killProcessesOnPort(port: Int): List<Int> {
        val pids = linkedSetOf<Int>()
        try {
            if (isWindows()) {
                val out = runCapture("netstat", listOf("-ano", "-p", "TCP"), timeoutSec = 8)
                val portToken = ":$port"
                for (line in out.lineSequence()) {
                    val t = line.trim()
                    if (!t.contains("LISTENING", ignoreCase = true)) continue
                    if (!t.contains(portToken)) continue
                    // TCP    127.0.0.1:9801    0.0.0.0:0    LISTENING    12345
                    val parts = t.split(Regex("\\s+"))
                    if (parts.size < 5) continue
                    val local = parts[1]
                    if (!local.endsWith(portToken)) continue
                    val pid = parts.last().toIntOrNull() ?: continue
                    if (pid > 0) pids.add(pid)
                }
                if (pids.isEmpty()) {
                    val ps = runCapture(
                        "powershell",
                        listOf(
                            "-NoProfile",
                            "-Command",
                            "(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue).OwningProcess",
                        ),
                        timeoutSec = 10,
                    )
                    for (line in ps.lineSequence()) {
                        val pid = line.trim().toIntOrNull() ?: continue
                        if (pid > 0) pids.add(pid)
                    }
                }
                for (pid in pids) {
                    appendLog("taskkill /T /F PID=$pid (port $port)")
                    runCapture("taskkill", listOf("/PID", pid.toString(), "/T", "/F"), timeoutSec = 8)
                }
            } else {
                val out = runCapture(
                    "sh",
                    listOf("-c", "lsof -tiTCP:$port -sTCP:LISTEN 2>/dev/null || true"),
                    timeoutSec = 5,
                )
                for (line in out.lineSequence()) {
                    val pid = line.trim().toIntOrNull() ?: continue
                    if (pid > 0) {
                        pids.add(pid)
                        runCapture("kill", listOf("-TERM", pid.toString()), timeoutSec = 3)
                        runCapture("kill", listOf("-KILL", pid.toString()), timeoutSec = 2)
                    }
                }
            }
        } catch (e: Exception) {
            appendLog("killProcessesOnPort error: ${e.message}")
        }
        return pids.toList()
    }

    private fun statusSnapshotStopped(port: Int): Map<String, Any?> {
        val state = loadAppState()
        val health = probeHealth(port)
        val paths = resolvePaths(
            System.getenv(),
            System.getProperty("user.home"),
            state.configDirOverride,
        )
        return linkedMapOf(
            "phase" to "Stopped",
            "lastError" to lastError,
            "port" to state.port,
            "mode" to state.mode.wireName(),
            "claudeWired" to state.claudeWired,
            "quitStopsProxy" to state.quitStopsProxy,
            "proxyEnv" to state.proxyEnv,
            "managedChild" to false,
            "pid" to null,
            "health" to linkedMapOf(
                "kind" to health.kind.wireName(),
                "version" to health.version,
                "forwardProxy" to health.forwardProxy,
                "httpStatus" to health.httpStatus,
                "hint" to health.hint,
            ),
            "launch" to launchInfo,
            "compatibleRange" to COMPATIBLE_RANGE,
            "paths" to linkedMapOf(
                "configRoot" to paths.configRoot,
                "settingsFile" to paths.settingsFile,
                "caPem" to paths.caPem,
                "appState" to stateFile.toString(),
                "logFile" to logFile.toString(),
            ),
            "logTail" to logBuffer.toList().takeLast(80),
            "engine" to "kmp-jvm",
        )
    }

    fun restartProxy(): Map<String, Any?> {
        stopProxy()
        Thread.sleep(300)
        return startProxy()
    }

    fun shutdown() {
        val state = loadAppState()
        // Always restore Claude config on quit; stop proxy per preference.
        if (state.quitStopsProxy) {
            stopProxy(force = true) // includes auto-unwire
        } else {
            autoUnwireClaudeOrNote()
        }
    }

    /**
     * After proxy is healthy in the selected mode: write Claude settings.
     * Throws so the UI does not claim "已写入" when wire failed.
     */
    private fun autoWireClaudeOrThrow() {
        try {
            wireClaude()
            appendLog("Auto-wired Claude settings after start")
            if (
                lastError.contains("自动安装") ||
                lastError.contains("正在") ||
                lastError.contains("端口模式不匹配")
            ) {
                lastError = ""
            }
        } catch (e: Exception) {
            val msg = "代理已就绪，但自动写入 Claude 配置失败: ${e.message}"
            lastError = msg
            appendLog(msg)
            throw IllegalStateException(msg, e)
        }
    }

    /** On stop/quit: remove GUI-managed env keys and restore ANTHROPIC_BASE_URL backup. */
    private fun autoUnwireClaudeOrNote() {
        try {
            // Always attempt restore — even if claudeWired flag was lost after crash
            unwireClaude()
            appendLog("Auto-unwired Claude settings after stop/quit")
        } catch (e: Exception) {
            val msg = "自动恢复 Claude 配置失败: ${e.message}"
            lastError = msg
            appendLog(msg)
        }
    }

    fun wireClaude(): Map<String, Any?> {
        val state = loadAppState()
        val paths = resolvePaths(
            System.getenv(),
            System.getProperty("user.home"),
            state.configDirOverride,
        )
        if (state.mode == ProxyMode.FORWARD) {
            val h = probeHealth(state.port)
            if (h.kind == HealthKind.UNREACHABLE || h.kind == HealthKind.FOREIGN) {
                throw IllegalStateException(
                    "请先启动正向代理（需要 /health 与 ca.pem），当前 health=${h.kind.wireName()}",
                )
            }
            if (h.forwardProxy != true) {
                throw IllegalStateException(
                    "代理 health 报告 forward_proxy=${h.forwardProxy}，不是正向模式。" +
                        "请停止后以「正向」重新启动（需 openssl 生成 CA）。",
                )
            }
            if (!Files.isRegularFile(Path.of(paths.caPem))) {
                throw IllegalStateException(
                    "正向模式需要 CA 文件: ${paths.caPem}。请确认代理已以 CACHE_FIX_FORWARD_PROXY=on 启动。",
                )
            }
        }
        val result = wireClaudeSettings(
            mode = state.mode,
            port = state.port,
            configDirOverride = state.configDirOverride,
            anthropicBaseUrlBackup = state.anthropicBaseUrlBackup,
            anthropicFoundryBaseUrlBackup = state.anthropicFoundryBaseUrlBackup,
        )
        saveAppState(
            mapOf(
                "claudeWired" to true,
                "expectedEnv" to result.expectedEnv,
                "anthropicBaseUrlBackup" to result.anthropicBaseUrlBackup,
                "anthropicFoundryBaseUrlBackup" to result.anthropicFoundryBaseUrlBackup,
            ),
        )
        val foundryNote =
            if (result.expectedEnv.containsKey("ANTHROPIC_FOUNDRY_BASE_URL")) " +FOUNDRY" else ""
        val keysNote = result.expectedEnv.keys.joinToString(",")
        appendLog(
            "Wired Claude settings at ${result.settingsFile} " +
                "mode=${state.mode.wireName()}$foundryNote keys=[$keysNote]",
        )
        return mapOf(
            "settingsFile" to result.settingsFile,
            "expectedEnv" to result.expectedEnv,
            "anthropicBaseUrlBackup" to result.anthropicBaseUrlBackup,
            "anthropicFoundryBaseUrlBackup" to result.anthropicFoundryBaseUrlBackup,
        )
    }

    fun unwireClaude(): Map<String, Any?> {
        val state = loadAppState()
        val expected = state.expectedEnv ?: run {
            val paths = resolvePaths(
                System.getenv(),
                System.getProperty("user.home"),
                state.configDirOverride,
            )
            if (state.mode == ProxyMode.FORWARD) {
                computeExpectedEnv(
                    ProxyMode.FORWARD,
                    state.port,
                    paths.caPem,
                )
            } else {
                // Best-effort: include FOUNDRY if backup or current settings had it
                val includeFoundry = !state.anthropicFoundryBaseUrlBackup.isNullOrBlank()
                computeExpectedEnv(
                    ProxyMode.REVERSE,
                    state.port,
                    includeFoundry = includeFoundry,
                )
            }
        }
        val result = unwireClaudeSettings(
            expectedEnv = expected,
            configDirOverride = state.configDirOverride,
            anthropicBaseUrlBackup = state.anthropicBaseUrlBackup,
            anthropicFoundryBaseUrlBackup = state.anthropicFoundryBaseUrlBackup,
        )
        saveAppState(
            mapOf(
                "claudeWired" to false,
                "expectedEnv" to null,
                "anthropicBaseUrlBackup" to result.anthropicBaseUrlBackup,
                "anthropicFoundryBaseUrlBackup" to result.anthropicFoundryBaseUrlBackup,
            ),
        )
        appendLog("Unwired Claude skipped=${result.skipped.joinToString(",").ifEmpty { "none" }}")
        return mapOf(
            "skipped" to result.skipped,
            "settingsFile" to result.settingsFile,
            "anthropicBaseUrlBackup" to result.anthropicBaseUrlBackup,
            "anthropicFoundryBaseUrlBackup" to result.anthropicFoundryBaseUrlBackup,
        )
    }

    fun previewWireEnv(): Map<String, String> {
        val state = loadAppState()
        val paths = resolvePaths(
            System.getenv(),
            System.getProperty("user.home"),
            state.configDirOverride,
        )
        return if (state.mode == ProxyMode.FORWARD) {
            computeExpectedEnv(ProxyMode.FORWARD, state.port, paths.caPem)
        } else {
            computeExpectedEnv(ProxyMode.REVERSE, state.port)
        }
    }

    fun getLogTail(n: Int = 100): List<String> = logBuffer.toList().takeLast(n)

    fun statusJson(): String = toJson(getStatus())

    fun toJson(value: Any?): String = encodeAny(value)

    private fun encodeAny(value: Any?): String = when (value) {
        null -> "null"
        is String -> json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(value))
        is Boolean -> if (value) "true" else "false"
        is Number -> value.toString()
        is Map<*, *> -> {
            val entries = value.entries.joinToString(",") { (k, v) ->
                "\"${k.toString().escapeJson()}\":${encodeAny(v)}"
            }
            "{$entries}"
        }
        is List<*> -> value.joinToString(",", "[", "]") { encodeAny(it) }
        else -> json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(value.toString()))
    }

    private fun String.escapeJson(): String =
        replace("\\", "\\\\").replace("\"", "\\\"")

    private fun readPackageVersion(packageRoot: Path): String? {
        val pkgFile = if (Files.isDirectory(packageRoot)) {
            packageRoot.resolve("package.json")
        } else {
            packageRoot.parent?.resolve("package.json")
        } ?: return null
        if (!Files.exists(pkgFile)) return null
        return try {
            val obj = json.parseToJsonElement(Files.readString(pkgFile)) as? JsonObject
            (obj?.get("version") as? JsonPrimitive)?.contentOrNull
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Install upstream package globally via npm.
     * @return install log tail
     */
    fun installProxyPackage(): String {
        appendLog("npm install -g claude-code-cache-fix@^4.3.0 …")
        val result = runCaptureDetailed(
            "npm",
            listOf("install", "-g", "claude-code-cache-fix@^4.3.0"),
            timeoutSec = 180,
        )
        appendLog("npm install exit=${result.exitCode}")
        if (result.output.isNotBlank()) {
            result.output.lineSequence().toList().takeLast(20).forEach { appendLog("[npm] $it") }
        }
        if (result.exitCode != 0) {
            throw IllegalStateException(
                "npm install failed (exit ${result.exitCode}).\n" +
                    "Ensure Node.js/npm is on PATH for this user.\n" +
                    result.output.takeLast(500),
            )
        }
        // Re-probe and remember path in app state when found
        val found = discover()
        if (found != null) {
            val path = found["path"]?.toString()
            if (!path.isNullOrBlank()) {
                saveAppState(mapOf("explicitProxyPath" to path))
            }
        }
        return result.output
    }

    /**
     * Forward preflight: fail fast with an actionable message when openssl is
     * absent, instead of letting the 25s health wait expire on a missing ca.pem.
     */
    private fun preflightOpensslOrThrow() {
        if (which("openssl") != null) return
        val hint = if (isWindows()) {
            "请安装 openssl 并加入 PATH（如 winget install ShiningLight.OpenSSL 或 Git for Windows 自带的 openssl.exe），或改用「反向」模式。"
        } else {
            "请安装 openssl（macOS: brew install openssl；Linux: apt/dnf install openssl），或改用「反向」模式。"
        }
        val msg = "正向模式需要 openssl 生成 CA，但 PATH 未找到 openssl。$hint"
        phase = "Error"
        lastError = msg
        appendLog(msg)
        throw IllegalStateException(msg)
    }

    private fun which(cmd: String): String? {
        // Windows: `where` via cmd.exe; never treat INFO: lines as paths.
        val out = if (isWindows()) {
            runCapture("where", listOf(cmd))
        } else {
            runCapture("which", listOf(cmd))
        }
        return firstExistingPathLine(out)
    }

    /**
     * Run an external command and capture stdout.
     * On Windows, wraps with `cmd.exe /c` so `.cmd` shims (npm.cmd, where) work.
     */
    private fun runCapture(cmd: String, args: List<String>, timeoutSec: Long = 12): String =
        runCaptureDetailed(cmd, args, timeoutSec).output

    private data class CmdResult(val exitCode: Int, val output: String)

    private fun runCaptureDetailed(
        cmd: String,
        args: List<String>,
        timeoutSec: Long = 12,
    ): CmdResult {
        return try {
            val command = if (isWindows()) {
                // cmd /c npm … — required for npm.cmd / node PATH shims (nvmd)
                listOf("cmd.exe", "/c", cmd) + args
            } else {
                listOf(cmd) + args
            }
            val pb = ProcessBuilder(command)
            pb.redirectErrorStream(true)
            val cwd = safeUserDir()
            if (cwd != null) pb.directory(cwd.toFile())
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            val finished = p.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                p.destroyForcibly()
                return CmdResult(-1, out.trim() + "\n(timeout after ${timeoutSec}s)")
            }
            CmdResult(p.exitValue(), out.trim())
        } catch (e: Exception) {
            CmdResult(-1, e.message ?: e.toString())
        }
    }

    private fun resolveLaunchCommand(launch: Map<String, Any?>): List<String> {
        val path = launch["path"]?.toString() ?: throw IllegalStateException("no launch path")
        val p = safePath(path) ?: throw IllegalStateException("invalid launch path: $path")
        val server = p.resolve("proxy").resolve("server.mjs")
        val bin = p.resolve("bin").resolve("claude-via-proxy.mjs")
        val node = which("node")
            ?: which("node.exe")
            ?: "node"
        return when {
            Files.isDirectory(p) && Files.exists(server) -> listOf(node, server.toString())
            Files.isDirectory(p) && Files.exists(bin) -> listOf(node, bin.toString(), "server")
            Files.isRegularFile(p) && (path.endsWith(".mjs") || path.endsWith(".js")) ->
                listOf(node, path)
            Files.isRegularFile(p) -> listOf(p.toString(), "server")
            else -> listOf(node, path)
        }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("win")

    /**
     * First line that looks like a real existing path.
     * Rejects Windows `where` / cmd noise such as:
     *   "INFO: Could not find files for the given pattern(s)."
     */
    fun firstExistingPathLine(output: String): String? {
        for (raw in output.lineSequence()) {
            val line = raw.trim().trim('"')
            if (line.isEmpty()) continue
            if (line.startsWith("INFO:", ignoreCase = true)) continue
            if (line.startsWith("ERROR:", ignoreCase = true)) continue
            if (line.startsWith("WARNING:", ignoreCase = true)) continue
            if (line.startsWith("Could not", ignoreCase = true)) continue
            if (!looksLikeFsPath(line)) continue
            val p = try {
                Path.of(line)
            } catch (_: Exception) {
                continue
            }
            if (Files.exists(p)) return p.toString()
        }
        return null
    }

    /** Windows drive path, UNC, or POSIX absolute/relative with separators. */
    fun looksLikeFsPath(s: String): Boolean {
        if (s.isEmpty() || s.length > 1024) return false
        // Windows: C:\... or C:/...
        if (s.length >= 3 && s[0].isLetter() && s[1] == ':' && (s[2] == '\\' || s[2] == '/')) {
            return true
        }
        // UNC \\server\share
        if (s.startsWith("\\\\") || s.startsWith("//")) return true
        // POSIX absolute
        if (s.startsWith("/")) return true
        // relative with path separator
        if (s.contains('\\') || s.contains('/')) return true
        // bare filename only if no colon (rejects "INFO: ...")
        return !s.contains(':') && !s.contains(' ')
    }

    private fun safePath(first: String, vararg more: String): Path? {
        return try {
            if (more.isEmpty()) Path.of(first) else Path.of(first, *more)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Stable working directory for child processes (npm/node).
     * Never use the process launch directory — it may be WSL UNC or arbitrary
     * and is not under app control.
     */
    private fun safeUserDir(): Path? = appHomeDir()

    /**
     * App-controlled home: -Dcache.fix.gui.home / CACHE_FIX_GUI_HOME /
     * %LOCALAPPDATA%\cache-fix-gui-kmp / temp.
     */
    private fun appHomeDir(): Path? {
        val candidates = listOfNotNull(
            System.getProperty("cache.fix.gui.home"),
            System.getenv("CACHE_FIX_GUI_HOME"),
            System.getenv("LOCALAPPDATA")?.let { Path.of(it, "cache-fix-gui-kmp").toString() },
            System.getenv("TEMP")?.let { Path.of(it, "cache-fix-gui-kmp").toString() },
            System.getProperty("java.io.tmpdir")?.let { Path.of(it, "cache-fix-gui-kmp").toString() },
        )
        for (c in candidates) {
            if (c.startsWith("\\\\") || c.startsWith("//")) continue
            try {
                val p = Path.of(c).toAbsolutePath().normalize()
                Files.createDirectories(p)
                return p
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    private fun localAppDataDir(): Path? = appHomeDir()
}
