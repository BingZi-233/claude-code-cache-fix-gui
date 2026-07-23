package com.cachefix.gui.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cachefix.gui.controller.Controller
import com.cachefix.gui.settings.ProxyMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI state + actions. All visible fields use Compose [mutableStateOf]
 * so buttons / phase update immediately after start/stop.
 */
class AppModel(
    private val scope: CoroutineScope,
) {
    var form: ProxyForm by mutableStateOf(ProxyForm())
        private set
    var status: Map<String, Any?> by mutableStateOf(emptyMap())
        private set
    var envPreview: String by mutableStateOf("{}")
        private set
    var busy: Boolean by mutableStateOf(false)
        private set
    var busyLabel: String by mutableStateOf("")
        private set
    var message: String? by mutableStateOf(null)
        private set
    var error: String? by mutableStateOf(null)
        private set
    var formDirty: Boolean by mutableStateOf(false)
        private set

    var configDirOverride: String by mutableStateOf("")
        private set
    var explicitProxyPath: String by mutableStateOf("")
        private set
    var quitStopsProxy: Boolean by mutableStateOf(true)
        private set

    var closeToTray: Boolean by mutableStateOf(UiPrefs.closeToTray)
        private set
    var startInTray: Boolean by mutableStateOf(UiPrefs.startInTray)
        private set

    var settingsDirty: Boolean by mutableStateOf(false)
        private set

    private var pollJob: Job? = null

    fun start() {
        scope.launch { refresh(silent = true) }
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(2000)
                // Always refresh phase/health so buttons track Running/Stopped even while busy settles
                refresh(silent = true)
            }
        }
    }

    fun dispose() {
        pollJob?.cancel()
    }

    fun updateForm(block: ProxyForm.() -> ProxyForm) {
        form = form.block()
        formDirty = true
    }

    fun updateSettings(
        configDirOverride: String? = null,
        explicitProxyPath: String? = null,
        quitStopsProxy: Boolean? = null,
        closeToTray: Boolean? = null,
        startInTray: Boolean? = null,
    ) {
        if (configDirOverride != null) this.configDirOverride = configDirOverride
        if (explicitProxyPath != null) this.explicitProxyPath = explicitProxyPath
        if (quitStopsProxy != null) this.quitStopsProxy = quitStopsProxy
        if (closeToTray != null) {
            this.closeToTray = closeToTray
            UiPrefs.closeToTray = closeToTray
        }
        if (startInTray != null) {
            this.startInTray = startInTray
            UiPrefs.startInTray = startInTray
        }
        settingsDirty = true
    }

    fun clearToast() {
        message = null
        error = null
    }

    private suspend fun refresh(silent: Boolean = false) {
        try {
            val s = withContext(Dispatchers.IO) { Controller.getStatus() }
            status = s
            if (!formDirty) {
                val port = (s["port"] as? Number)?.toInt() ?: form.port
                val mode = s["mode"] as? String ?: form.mode
                @Suppress("UNCHECKED_CAST")
                val pe = s["proxyEnv"] as? Map<String, String> ?: emptyMap()
                var next = form.copy(port = port, mode = mode).mergedFromProxyEnv(pe)
                // Seed Upstream from Claude ANTHROPIC_BASE_URL when proxyEnv has no explicit upstream
                if (pe["CACHE_FIX_PROXY_UPSTREAM"].isNullOrBlank()) {
                    val suggested = s["suggestedUpstream"] as? String
                    if (!suggested.isNullOrBlank()) {
                        next = next.copy(upstream = suggested)
                    }
                }
                form = next
            }
            if (!settingsDirty) {
                val st = withContext(Dispatchers.IO) { Controller.loadAppState() }
                configDirOverride = st.configDirOverride.orEmpty()
                explicitProxyPath = st.explicitProxyPath.orEmpty()
                quitStopsProxy = st.quitStopsProxy
            }
            if (!silent) {
                error = null
            } else {
                val last = s["lastError"] as? String
                // Don't sticky-overwrite a fresher action error while busy
                if (!busy && !last.isNullOrBlank()) error = last
            }
        } catch (e: Exception) {
            if (!silent) error = e.message ?: e.toString()
        }
    }

    fun refreshPreview() = runAction("预览已刷新", "刷新预览…") {
        val env = withContext(Dispatchers.IO) { Controller.previewWireEnv() }
        envPreview = env.entries.joinToString(",\n", "{\n", "\n}") { (k, v) ->
            "  \"$k\": \"$v\""
        }
    }

    fun saveConfig() = runAction("配置已保存", "保存配置…") {
        persistConfig()
        persistSettings()
    }

    fun saveSettings() = runAction("设置已保存", "保存设置…") {
        persistSettings()
        persistConfig()
    }

    fun startProxy() = runAction(ok = null, label = "启动代理并写入配置…") {
        persistConfig()
        val result = withContext(Dispatchers.IO) {
            try {
                Controller.startProxy()
            } catch (e: Exception) {
                val msg = e.message.orEmpty()
                if (msg.contains("proxy not found", ignoreCase = true) ||
                    msg.contains("No compatible", ignoreCase = true)
                ) {
                    // Auto-install then retry once
                    Controller.installProxyPackage()
                    Controller.startProxy()
                } else {
                    throw e
                }
            }
        }
        status = result
        val wired = result["claudeWired"] as? Boolean == true
        val modeZh = if (form.mode == "forward") "正向" else "反向"
        message = if (wired) {
            "代理已启动，并已写入 Claude 配置（$modeZh）"
        } else {
            "代理已启动，但 Claude 配置未接线 — 请点「写入 Claude」"
        }
    }

    fun stopProxy() {
        // Dedicated path: never blocked by start's busy flag; force-kills port listeners.
        scope.launch {
            busy = true
            busyLabel = "停止代理…"
            message = null
            error = null
            try {
                val result = withContext(Dispatchers.IO) {
                    Controller.stopProxy(force = true)
                }
                status = result
                formDirty = false
                val stillUp = (result["health"] as? Map<*, *>)?.get("kind")?.toString()
                val note = result["lastError"] as? String
                if (stillUp == "ok" || stillUp == "degraded") {
                    error = note ?: "停止后端口仍被占用"
                } else {
                    message = "已停止，Claude 配置已恢复"
                    // surface unwire warnings if any
                    if (!note.isNullOrBlank() && note.contains("自动恢复")) {
                        error = note
                    } else {
                        error = null
                    }
                }
            } catch (e: Exception) {
                error = e.message ?: e.toString()
                try {
                    refresh(silent = true)
                } catch (_: Exception) {
                    /* ignore */
                }
            } finally {
                busy = false
                busyLabel = ""
                // Do NOT call getStatus refresh immediately — it can re-promote phase to
                // Attached if probe races before the port is fully closed. Use stop result.
            }
        }
    }

    fun restartProxy() = runAction("已重启", "重启代理…") {
        persistConfig()
        withContext(Dispatchers.IO) { Controller.restartProxy() }
    }

    fun discover() = runAction("已重新发现代理", "发现代理…") {
        withContext(Dispatchers.IO) { Controller.discover() }
    }

    fun installProxy() = runAction("代理包已安装", "正在 npm 安装 claude-code-cache-fix…") {
        withContext(Dispatchers.IO) {
            Controller.installProxyPackage()
            Controller.discover()
        }
    }

    fun wire() = runAction("已写入 Claude 配置", "写入 Claude…") {
        persistConfig()
        withContext(Dispatchers.IO) { Controller.wireClaude() }
    }

    fun unwire() = runAction("已从 Claude 移除", "移除接线…") {
        withContext(Dispatchers.IO) { Controller.unwireClaude() }
    }

    private suspend fun persistConfig() {
        withContext(Dispatchers.IO) {
            Controller.saveAppState(
                mapOf(
                    "port" to form.port,
                    "mode" to ProxyMode.from(form.mode),
                    "proxyEnv" to form.toProxyEnv(),
                ),
            )
        }
        formDirty = false
    }

    private suspend fun persistSettings() {
        withContext(Dispatchers.IO) {
            Controller.saveAppState(
                mapOf(
                    "configDirOverride" to configDirOverride.trim().ifEmpty { null },
                    "explicitProxyPath" to explicitProxyPath.trim().ifEmpty { null },
                    "quitStopsProxy" to quitStopsProxy,
                ),
            )
        }
        UiPrefs.closeToTray = closeToTray
        UiPrefs.startInTray = startInTray
        settingsDirty = false
    }

    /**
     * @param ok success toast; null means [block] sets [message] itself
     * @param allowWhileBusy stop must work even if start is in progress
     */
    private fun runAction(
        ok: String?,
        label: String,
        allowWhileBusy: Boolean = false,
        block: suspend () -> Unit,
    ) {
        if (busy && !allowWhileBusy) return
        scope.launch {
            busy = true
            busyLabel = label
            message = null
            error = null
            try {
                block()
                formDirty = false
                refresh(silent = false)
                try {
                    val env = withContext(Dispatchers.IO) { Controller.previewWireEnv() }
                    envPreview = env.entries.joinToString(",\n", "{\n", "\n}") { (k, v) ->
                        "  \"$k\": \"$v\""
                    }
                } catch (_: Exception) {
                    /* ignore */
                }
                if (ok != null) message = ok
            } catch (e: Exception) {
                error = e.message ?: e.toString()
                try {
                    refresh(silent = true)
                } catch (_: Exception) {
                    /* ignore */
                }
            } finally {
                busy = false
                busyLabel = ""
                // Final status pull so phase/buttons settle
                try {
                    refresh(silent = true)
                } catch (_: Exception) {
                    /* ignore */
                }
            }
        }
    }

    val phase: String get() = status["phase"] as? String ?: "Stopped"
    val claudeWired: Boolean get() = status["claudeWired"] as? Boolean ?: false

    @Suppress("UNCHECKED_CAST")
    val health: Map<String, Any?>
        get() = status["health"] as? Map<String, Any?> ?: emptyMap()

    @Suppress("UNCHECKED_CAST")
    val launch: Map<String, Any?>?
        get() = status["launch"] as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    val paths: Map<String, Any?>
        get() = status["paths"] as? Map<String, Any?> ?: emptyMap()

    @Suppress("UNCHECKED_CAST")
    val logTail: List<String>
        get() = (status["logTail"] as? List<*>)?.map { it.toString() } ?: emptyList()

    /** Start enabled when not running and not mid-start (unless install). */
    val canStart: Boolean
        get() {
            val p = phase.lowercase()
            return !busy && p !in setOf("running", "attached", "starting")
        }

    /**
     * Stop is available whenever we are not already clearly stopped,
     * including mid-start and attached proxies (force-kills the port).
     */
    val canStop: Boolean
        get() {
            val p = phase.lowercase()
            if (p == "stopped" && !busy) {
                // Still allow stop if health says something is listening
                val hk = (health["kind"] as? String)?.lowercase()
                return hk == "ok" || hk == "degraded"
            }
            return p != "stopped" || busyLabel.contains("启动")
        }

    val canRestart: Boolean
        get() = !busy && phase.lowercase() in setOf("running", "attached", "degraded", "stopped", "error")
}
