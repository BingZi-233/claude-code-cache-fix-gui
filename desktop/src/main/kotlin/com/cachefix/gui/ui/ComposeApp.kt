package com.cachefix.gui.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.cachefix.gui.controller.Controller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.system.exitProcess

private val Accent = Color(0xFF0B6BCB)
private val Bg = Color(0xFFF7F7F8)
private val Panel = Color(0xFFFFFFFF)
private val PanelAlt = Color(0xFFEEF1F4)
private val Muted = Color(0xFF5C6570)
private val Ok = Color(0xFF1B7F4E)
private val Warn = Color(0xFFB86E00)
private val Err = Color(0xFFC62828)
private val Border = Color(0xFFD0D5DD)
private val LogBg = Color(0xFFF3F4F6)
private val LogFg = Color(0xFF1F2937)
private val NavSelected = Color(0xFFE8F1FB)

private enum class AppPage { Console, Settings }

/**
 * Launch Compose Desktop with system tray (blocks until quit).
 *
 * Hide-to-tray must NOT remove [Window] from composition — Compose Desktop exits
 * the [application] when the last window is disposed, which made hide == quit.
 * Keep the window composed and toggle [Window] `visible` instead.
 *
 * Close / 隐藏到托盘 → only hide (proxy + Claude wire stay).
 * 退出 / tray 退出 → [Controller.shutdown] then exit process.
 */
fun launchComposeGui() {
    application {
        val windowState = rememberWindowState(width = 1100.dp, height = 780.dp)
        var windowVisible by remember { mutableStateOf(!UiPrefs.startInTray) }
        var showSeq by remember { mutableStateOf(0) }
        val windowIcon = rememberAppIconPainter()

        fun hideToTray() {
            windowVisible = false
        }

        fun showMain() {
            windowVisible = true
            showSeq++
        }

        fun quitApp() {
            try {
                com.cachefix.gui.controller.Controller.shutdown()
            } catch (_: Exception) {
                /* ignore */
            }
            exitApplication()
            exitProcess(0)
        }

        // Self-drawn Compose popup menu (not AWT PopupMenu — no Chinese □)
        CustomSystemTray(
            tooltip = "cache-fix GUI",
            onShowMain = { showMain() },
            onHideMain = { hideToTray() },
            onQuit = { quitApp() },
        )

        // Always compose the window; hide via visible= so application stays alive with tray.
        Window(
            onCloseRequest = {
                if (UiPrefs.closeToTray) {
                    hideToTray()
                } else {
                    quitApp()
                }
            },
            title = "cache-fix GUI",
            state = windowState,
            icon = windowIcon,
            visible = windowVisible,
        ) {
            // Restore from tray: un-minimize + raise (visible=true alone may not focus).
            LaunchedEffect(showSeq) {
                if (showSeq > 0 && windowVisible) {
                    try {
                        window.isMinimized = false
                        window.toFront()
                        window.requestFocus()
                    } catch (_: Exception) {
                        /* ignore */
                    }
                }
            }
            CacheFixTheme {
                CacheFixApp(
                    onHideToTray = { hideToTray() },
                    onQuit = { quitApp() },
                )
            }
        }
    }
}

@Composable
private fun CacheFixTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Accent,
            onPrimary = Color.White,
            secondary = Color(0xFF475569),
            background = Bg,
            surface = Panel,
            onBackground = Color(0xFF0F172A),
            onSurface = Color(0xFF0F172A),
            surfaceVariant = PanelAlt,
            onSurfaceVariant = Color(0xFF334155),
            outline = Border,
            error = Err,
            onError = Color.White,
        ),
        content = content,
    )
}

@Composable
fun CacheFixApp(
    onHideToTray: () -> Unit = {},
    onQuit: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val model = remember { AppModel(scope) }
    var page by remember { mutableStateOf(AppPage.Console) }
    val snackbar = remember { SnackbarHostState() }

    DisposableEffect(Unit) {
        model.start()
        model.refreshPreview()
        onDispose { model.dispose() }
    }

    // Read mutableState fields so Compose recomposes on start/stop/phase changes
    val phase = model.phase
    val busy = model.busy
    val canStart = model.canStart
    val canStop = model.canStop

    LaunchedEffect(model.message, model.error) {
        model.message?.let {
            snackbar.showSnackbar(it)
            model.clearToast()
        }
        model.error?.let {
            if (it.isNotBlank()) snackbar.showSnackbar(it)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = Bg,
    ) { padding ->
        Row(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // ── Left rail ──
            Surface(
                modifier = Modifier
                    .width(280.dp)
                    .fillMaxHeight(),
                color = Panel,
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    ) {
                        Image(
                            painter = rememberAppIconPainter(),
                            contentDescription = "cache-fix",
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                "cache-fix",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "Compose 控制台",
                                color = Muted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    PhaseCard(model)

                    NavItem(
                        icon = Icons.Default.Dashboard,
                        label = "控制台",
                        selected = page == AppPage.Console,
                        onClick = { page = AppPage.Console },
                    )
                    NavItem(
                        icon = Icons.Default.Settings,
                        label = "设置",
                        selected = page == AppPage.Settings,
                        onClick = { page = AppPage.Settings },
                    )

                    // Always visible — not gated by current page
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (busy && model.busyLabel.isNotBlank()) {
                            Text(
                                model.busyLabel,
                                color = Accent,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                        Button(
                            onClick = { model.startProxy() },
                            enabled = canStart,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (phase.equals("Starting", true)) "启动中…" else "启动代理")
                        }
                        OutlinedButton(
                            onClick = { model.stopProxy() },
                            enabled = canStop,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Stop, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("停止")
                        }
                        FilledTonalButton(
                            onClick = { model.restartProxy() },
                            enabled = model.canRestart,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Sync, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("重启")
                        }
                        OutlinedButton(
                            onClick = { model.installProxy() },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Search, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("安装/修复 proxy")
                        }
                        TextButton(
                            onClick = { model.saveConfig() },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Save, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("保存配置")
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    TextButton(
                        onClick = onHideToTray,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("隐藏到托盘") }
                    TextButton(
                        onClick = {
                            // Always allow quit — stop managed proxy then exit
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        Controller.stopProxy(force = true)
                                        Controller.shutdown()
                                    }
                                } catch (_: Exception) {
                                    /* ignore */
                                }
                                onQuit()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("退出") }

                    Text(
                        "phase=$phase · busy=$busy",
                        color = Muted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            // ── Main content ──
            val scroll = rememberScrollState()
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Bg),
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                        .padding(24.dp)
                        .widthIn(max = 720.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (!model.error.isNullOrBlank()) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Err.copy(alpha = 0.12f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                model.error!!,
                                color = Err,
                                modifier = Modifier.padding(12.dp),
                                fontSize = 12.sp,
                            )
                        }
                    }

                    when (page) {
                        AppPage.Console -> ConsolePage(model)
                        AppPage.Settings -> SettingsPage(model)
                    }

                    Spacer(Modifier.height(24.dp))
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(scroll),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun NavItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) NavSelected else Color.Transparent
    val fg = if (selected) Accent else Muted
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = fg, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun ColumnScope.ConsolePage(model: AppModel) {
    Text(
        "各项配置默认折叠。点开分组标题展开；改完后点左侧「保存配置」或启动/重启代理时会一并写入。",
        color = Muted,
        fontSize = 12.sp,
    )

    FoldSection(
        title = "连接与上游",
        summary = "Upstream · 端口 · 反向/正向模式 · 绑定",
        detail =
            "决定 cache-fix 代理监听哪里、请求转发到哪个 Anthropic 兼容上游。\n" +
                "· Upstream → CACHE_FIX_PROXY_UPSTREAM（多数场景需要改成你的 API 网关）\n" +
                "· 端口 → 代理监听端口，同时写入 Claude 接线 URL（默认 9801）\n" +
                "· 反向模式：Claude 走 ANTHROPIC_BASE_URL=http://127.0.0.1:端口\n" +
                "· 正向模式：Claude 走 HTTPS_PROXY + 本地 CA（Remote Control）\n" +
                "· 绑定 / 超时 → CACHE_FIX_PROXY_BIND / CACHE_FIX_PROXY_TIMEOUT\n" +
                "改完若代理已在运行，请点「重启」使环境变量生效。",
        initiallyExpanded = true,
    ) {
        OutlinedTextField(
            value = model.form.upstream,
            onValueChange = { v -> model.updateForm { copy(upstream = v) } },
            label = { Text("Upstream") },
            placeholder = { Text("https://api.anthropic.com") },
            singleLine = true,
            enabled = !model.busy,
            modifier = Modifier.fillMaxWidth(),
            textStyle = LocalMono,
        )
        FieldHelp(
            "对应 CACHE_FIX_PROXY_UPSTREAM（代理转发目标）。" +
                "未手动设置时会从 Claude settings 的 ANTHROPIC_BASE_URL 预填。",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = model.form.port.toString(),
                onValueChange = { v ->
                    model.updateForm {
                        copy(port = v.toIntOrNull()?.coerceIn(1, 65535) ?: port)
                    }
                },
                label = { Text("端口") },
                singleLine = true,
                enabled = !model.busy,
                modifier = Modifier.weight(1f),
            )
            ModeDropdown(
                value = model.form.mode,
                enabled = !model.busy,
                onChange = { m -> model.updateForm { copy(mode = m) } },
                modifier = Modifier.weight(1f),
            )
        }
        FieldHelp("端口范围 1–65535。模式切换后需重新「接线」Claude 才会改 settings.json。")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = model.form.bind,
                onValueChange = { v -> model.updateForm { copy(bind = v) } },
                label = { Text("绑定地址") },
                singleLine = true,
                enabled = !model.busy,
                modifier = Modifier.weight(1f),
                textStyle = LocalMono,
            )
            OutlinedTextField(
                value = model.form.timeout,
                onValueChange = { v -> model.updateForm { copy(timeout = v) } },
                label = { Text("超时 (ms)") },
                singleLine = true,
                enabled = !model.busy,
                modifier = Modifier.weight(1f),
            )
        }
        FieldHelp("绑定默认 127.0.0.1（仅本机）。超时过短可能导致长对话被掐断。")
        SwitchRow(
            label = "调试日志",
            description = "CACHE_FIX_DEBUG=1，代理输出更详细日志（排障用）",
            checked = model.form.debug,
            enabled = !model.busy,
        ) { model.updateForm { copy(debug = it) } }
        TextButton(onClick = { model.discover() }, enabled = !model.busy) {
            Icon(Icons.Default.Search, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("重新发现代理二进制")
        }
        FieldHelp("发现顺序：显式路径 → PATH 上的 cache-fix-proxy → npm global → 便携 sidecar。兼容版本 ≥4.3.0 <5。")
    }

    FoldSection(
        title = "企业网络",
        summary = "出站 HTTP(S) 代理 · 公司 CA · TLS",
        detail =
            "当本机访问上游还要经过公司代理或自签证书时配置。\n" +
                "这些值会作为启动 cache-fix-proxy 时的子进程环境变量传入。\n" +
                "· HTTPS_PROXY / HTTP_PROXY：出站代理 URL\n" +
                "· NO_PROXY：不走代理的主机列表（逗号分隔）\n" +
                "· CACHE_FIX_PROXY_CA_FILE：额外信任的 CA 证书路径\n" +
                "· 拒绝未授权证书：关闭后允许不信任证书（仅调试，不安全）",
    ) {
        OutlinedTextField(
            value = model.form.httpsProxy,
            onValueChange = { v -> model.updateForm { copy(httpsProxy = v) } },
            label = { Text("HTTPS_PROXY") },
            placeholder = { Text("http://proxy.corp:8080") },
            singleLine = true,
            enabled = !model.busy,
            modifier = Modifier.fillMaxWidth(),
            textStyle = LocalMono,
        )
        OutlinedTextField(
            value = model.form.httpProxy,
            onValueChange = { v -> model.updateForm { copy(httpProxy = v) } },
            label = { Text("HTTP_PROXY") },
            singleLine = true,
            enabled = !model.busy,
            modifier = Modifier.fillMaxWidth(),
            textStyle = LocalMono,
        )
        OutlinedTextField(
            value = model.form.noProxy,
            onValueChange = { v -> model.updateForm { copy(noProxy = v) } },
            label = { Text("NO_PROXY") },
            placeholder = { Text("localhost,127.0.0.1,.corp.example") },
            singleLine = true,
            enabled = !model.busy,
            modifier = Modifier.fillMaxWidth(),
            textStyle = LocalMono,
        )
        FieldHelp("保存时会同步写入小写 https_proxy / http_proxy / no_proxy（与 Node 习惯一致）。")
        OutlinedTextField(
            value = model.form.caFile,
            onValueChange = { v -> model.updateForm { copy(caFile = v) } },
            label = { Text("CACHE_FIX_PROXY_CA_FILE") },
            placeholder = { Text("/path/to/corp-ca.pem") },
            singleLine = true,
            enabled = !model.busy,
            modifier = Modifier.fillMaxWidth(),
            textStyle = LocalMono,
        )
        SwitchRow(
            label = "拒绝未授权证书",
            description = "关闭则设置 CACHE_FIX_PROXY_REJECT_UNAUTHORIZED=0（不安全，仅排障）",
            checked = model.form.rejectUnauthorized,
            enabled = !model.busy,
        ) { model.updateForm { copy(rejectUnauthorized = it) } }
    }

    FoldSection(
        title = "Forward 正向增强",
        summary = "CA 目录 · 下载重写 · OAuth",
        detail =
            "仅在「正向」模式或需要本地 MITM CA 时相关。\n" +
                "· CACHE_FIX_CA_DIR：CA/状态目录，默认 {配置根}/cache-fix-ca\n" +
                "  正向接线前需先启动代理生成 ca.pem\n" +
                "· 下载加速重写 → CACHE_FIX_DOWNLOAD_REWRITE=on\n" +
                "· OAuth 刷新 → CACHE_FIX_OAUTH_REFRESH=on",
    ) {
        OutlinedTextField(
            value = model.form.caDir,
            onValueChange = { v -> model.updateForm { copy(caDir = v) } },
            label = { Text("CACHE_FIX_CA_DIR") },
            placeholder = { Text("默认：配置目录/cache-fix-ca") },
            singleLine = true,
            enabled = !model.busy,
            modifier = Modifier.fillMaxWidth(),
            textStyle = LocalMono,
        )
        FieldHelp("正向接线写入 Claude 的 NODE_EXTRA_CA_CERTS 会指向此目录下的 ca.pem。")
        SwitchRow(
            label = "下载加速重写",
            description = "CACHE_FIX_DOWNLOAD_REWRITE=on",
            checked = model.form.downloadRewrite,
            enabled = !model.busy,
        ) { model.updateForm { copy(downloadRewrite = it) } }
        SwitchRow(
            label = "OAuth 刷新",
            description = "CACHE_FIX_OAUTH_REFRESH=on",
            checked = model.form.oauthRefresh,
            enabled = !model.busy,
        ) { model.updateForm { copy(oauthRefresh = it) } }
    }

    FoldSection(
        title = "扩展行为",
        summary = "Bootstrap · Thinking · Guard · Mirror",
        detail =
            "上游 cache-fix 的可选扩展开关（写入启动 env，不是 Claude settings）。\n" +
                "· Bootstrap：audit / block / allowlist（CACHE_FIX_BOOTSTRAP_MODE）\n" +
                "· Thinking Display：summarized / omitted / disabled\n" +
                "· Thinking Sanitize：on / off / v2\n" +
                "· Image Guard / Session Mirror / Upstream Error Log：见各开关说明\n" +
                "选「default」表示不写入该键，交给上游默认值。",
    ) {
        SimpleDropdown(
            label = "Bootstrap 模式",
            value = model.form.bootstrapMode.ifEmpty { "default" },
            options = listOf("default", "audit", "block", "allowlist"),
            enabled = !model.busy,
            onChange = { v ->
                model.updateForm { copy(bootstrapMode = if (v == "default") "" else v) }
            },
        )
        FieldHelp("CACHE_FIX_BOOTSTRAP_MODE。default = 不覆盖上游默认（通常为 audit）。")
        SimpleDropdown(
            label = "Thinking Display",
            value = model.form.thinkingDisplay.ifEmpty { "default" },
            options = listOf("default", "summarized", "omitted", "disabled"),
            enabled = !model.busy,
            onChange = { v ->
                model.updateForm { copy(thinkingDisplay = if (v == "default") "" else v) }
            },
        )
        SimpleDropdown(
            label = "Thinking Sanitize",
            value = model.form.thinkingSanitize.ifEmpty { "default" },
            options = listOf("default", "on", "off", "v2"),
            enabled = !model.busy,
            onChange = { v ->
                model.updateForm { copy(thinkingSanitize = if (v == "default") "" else v) }
            },
        )
        SwitchRow(
            label = "Image Guard",
            description = "CACHE_FIX_IMAGE_GUARD=1",
            checked = model.form.imageGuard,
            enabled = !model.busy,
        ) { model.updateForm { copy(imageGuard = it) } }
        SwitchRow(
            label = "Session Mirror",
            description = "CACHE_FIX_SESSION_MIRROR=on",
            checked = model.form.sessionMirror,
            enabled = !model.busy,
        ) { model.updateForm { copy(sessionMirror = it) } }
        SwitchRow(
            label = "Upstream Error Log",
            description = "CACHE_FIX_UPSTREAM_ERROR_LOG=on",
            checked = model.form.upstreamErrorLog,
            enabled = !model.busy,
        ) { model.updateForm { copy(upstreamErrorLog = it) } }
    }

    FoldSection(
        title = "高级自定义 env",
        summary = "额外 KEY=value（每行一条）",
        detail =
            "写入启动 proxy 时的附加环境变量。\n" +
                "· 每行格式：KEY=value\n" +
                "· # 开头为注释；空行忽略\n" +
                "· 与上方专用控件同名的键会被忽略（专用控件优先）\n" +
                "例：CACHE_FIX_REQUEST_LOG=/tmp/req.log",
    ) {
        OutlinedTextField(
            value = model.form.extraEnvText,
            onValueChange = { v -> model.updateForm { copy(extraEnvText = v) } },
            label = { Text("自定义环境变量") },
            placeholder = { Text("CACHE_FIX_HOT_RELOAD=on\n# 注释") },
            enabled = !model.busy,
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
            textStyle = LocalMono,
        )
    }

    FoldSection(
        title = "Claude 接线",
        summary = "启动自动写入 · 停止/退出自动恢复",
        detail =
            "默认行为：\n" +
                "· 启动代理成功后 → 自动写入全局 settings.json 的 env\n" +
                "· 停止代理 / 退出 GUI → 自动恢复（移除 GUI 写入的键并还原备份）\n" +
                "下方按钮仍可手动写入/移除。\n" +
                "· 反向：ANTHROPIC_BASE_URL=http://127.0.0.1:端口；原值备份，停止时恢复\n" +
                "· 若原配置有 ANTHROPIC_FOUNDRY_BASE_URL：一并改写为本地代理，停止时恢复原值\n" +
                "· 正向：HTTPS_PROXY + NODE_EXTRA_CA_CERTS；移除 BASE_URL/FOUNDRY 并备份，停止时恢复\n" +
                "只改用户级 settings.json，不会启动 Claude CLI。",
        initiallyExpanded = true,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { model.wire() }, enabled = !model.busy) {
                Icon(Icons.Default.Cable, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("写入")
            }
            OutlinedButton(onClick = { model.unwire() }, enabled = !model.busy) {
                Icon(Icons.Default.LinkOff, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("移除")
            }
            TextButton(onClick = { model.refreshPreview() }, enabled = !model.busy) {
                Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("刷新预览")
            }
        }
        FieldHelp("下方为即将写入（或已计算）的 env 预览，不会自动保存，需点「写入」。")
        Surface(
            color = PanelAlt,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                model.envPreview,
                modifier = Modifier.padding(12.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = Muted,
            )
        }
    }

    FoldSection(
        title = "运行信息",
        summary = "阶段 · 健康 · 发现结果 · 路径",
        detail = "约每 4 秒自动刷新。阶段含义：Stopped / Starting / Running / Attached / Degraded / Error。",
    ) {
        val h = model.health
        val healthText = buildString {
            append(h["kind"] ?: "—")
            h["version"]?.let { append(" · v$it") }
            h["forwardProxy"]?.let { append(" · forward=$it") }
        }
        MetaGrid(
            listOf(
                "阶段" to model.phase,
                "端口" to (statusPort(model) ?: "—"),
                "模式" to modeLabel(model.form.mode),
                "健康" to healthText,
                "来源" to (model.launch?.get("source")?.toString() ?: "—"),
                "版本" to (model.launch?.get("version")?.toString() ?: "—"),
                "Claude" to if (model.claudeWired) "已接线" else "未接线",
                "配置目录" to (model.paths["configRoot"]?.toString() ?: "—"),
            ),
        )
    }

    FoldSection(
        title = "日志",
        summary = "代理 stdout/stderr 尾部",
        detail = "内存环形缓冲 + 写入 ~/.cache-fix-gui/proxy.log。仅显示最近若干行。",
    ) {
        Surface(
            color = LogBg,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
        ) {
            val logScroll = rememberScrollState()
            Text(
                model.logTail.joinToString("\n").ifEmpty { "（暂无日志）" },
                modifier = Modifier
                    .verticalScroll(logScroll)
                    .padding(12.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = LogFg,
            )
        }
    }
}

@Composable
private fun ColumnScope.SettingsPage(model: AppModel) {
    Text(
        "应用级设置与「控制台」里的代理 env 分开保存。改完点底部「保存设置」。",
        color = Muted,
        fontSize = 12.sp,
    )

    FoldSection(
        title = "路径与发现",
        summary = "Claude 配置根 · 显式 proxy 路径",
        detail =
            "· Claude 配置目录覆盖：等价于设置 CLAUDE_CONFIG_DIR，优先于环境变量与默认 ~/.claude\n" +
                "  全局 settings.json 路径 = {该目录}/settings.json\n" +
                "· 显式 proxy 路径：强制使用该包根或二进制，跳过 PATH/npm 搜索（发现顺序最高优先级）\n" +
                "  可填 claude-code-cache-fix 包根（含 proxy/server.mjs）或 cache-fix-proxy 可执行文件",
        initiallyExpanded = true,
    ) {
        OutlinedTextField(
            value = model.configDirOverride,
            onValueChange = { model.updateSettings(configDirOverride = it) },
            label = { Text("Claude 配置目录覆盖") },
            placeholder = { Text("空 = 默认 ~/.claude 或环境变量 CLAUDE_CONFIG_DIR") },
            singleLine = true,
            enabled = !model.busy,
            modifier = Modifier.fillMaxWidth(),
            textStyle = LocalMono,
        )
        FieldHelp("只影响本 GUI 读写的 settings.json / CA 路径，不会修改系统环境变量本身。")
        OutlinedTextField(
            value = model.explicitProxyPath,
            onValueChange = { model.updateSettings(explicitProxyPath = it) },
            label = { Text("显式 proxy 路径") },
            placeholder = { Text("包根目录或 cache-fix-proxy 可执行文件") },
            singleLine = true,
            enabled = !model.busy,
            modifier = Modifier.fillMaxWidth(),
            textStyle = LocalMono,
        )
        FieldHelp("当前生效配置根：${model.paths["configRoot"] ?: "—"}")
        FieldHelp("settings.json：${model.paths["settingsFile"] ?: "—"}")
    }

    FoldSection(
        title = "生命周期与托盘",
        summary = "退出停代理 · 关窗进托盘 · 启动进托盘",
        detail =
            "· 退出 GUI 时停止托管代理：仅杀死本 GUI 拉起的进程；「附着」到已有代理时不会杀\n" +
                "· 关闭窗口隐藏到托盘：点窗口 X 不退出，可从托盘「显示主窗口」恢复\n" +
                "· 启动时最小化到托盘：下次启动只出现托盘图标\n" +
                "托盘菜单：显示 / 隐藏 / 退出；双击托盘图标也可显示窗口。",
        initiallyExpanded = true,
    ) {
        SwitchRow(
            label = "退出 GUI 时停止托管代理",
            description = "quitStopsProxy，写入 ~/.cache-fix-gui/state.json",
            checked = model.quitStopsProxy,
            enabled = !model.busy,
        ) { model.updateSettings(quitStopsProxy = it) }
        SwitchRow(
            label = "关闭窗口时隐藏到托盘",
            description = "关闭 = 不退出；从托盘可重新打开",
            checked = model.closeToTray,
            enabled = true,
        ) { model.updateSettings(closeToTray = it) }
        SwitchRow(
            label = "启动时最小化到托盘",
            description = "下次启动不弹出主窗口",
            checked = model.startInTray,
            enabled = true,
        ) { model.updateSettings(startInTray = it) }
    }

    FoldSection(
        title = "关于",
        summary = "版本与职责边界",
        detail =
            "本程序是 claude-code-cache-fix 的桌面控制面（KMP + Compose）。\n" +
                "负责：启停代理、写 proxy 启动 env、wire/unwire Claude 全局 settings.env。\n" +
                "不负责：启动 Claude CLI、项目级 .claude/settings.json、安装系统服务。",
    ) {
        Text("cache-fix GUI · Kotlin Multiplatform + Compose Desktop", fontSize = 13.sp)
        Text(
            "兼容 proxy 版本: ${model.status["compatibleRange"] ?: ">=4.3.0 <5"}",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = Muted,
        )
        FieldHelp("状态与日志目录：~/.cache-fix-gui/（state.json、proxy.log）")
    }

    Button(
        onClick = { model.saveSettings() },
        enabled = !model.busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Default.Save, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("保存设置")
    }
}

private val LocalMono = androidx.compose.ui.text.TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
)

@Composable
private fun PhaseCard(model: AppModel) {
    val phase = model.phase
    val color = when (phase.lowercase()) {
        "running", "attached" -> Ok
        "error" -> Err
        "starting", "degraded", "discovering" -> Warn
        else -> Muted
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = PanelAlt),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(color),
                )
                Spacer(Modifier.width(8.dp))
                Text(phase, fontWeight = FontWeight.SemiBold)
            }
            Text(
                ":${model.form.port} · ${modeLabel(model.form.mode)}",
                color = Muted,
                fontSize = 12.sp,
            )
            Text(
                if (model.claudeWired) "Claude 已接线" else "Claude 未接线",
                color = if (model.claudeWired) Ok else Muted,
                fontSize = 12.sp,
            )
        }
    }
}

/**
 * Collapsible config block — same pattern for 控制台 groups and 设置 groups.
 * @param detail multi-line help shown under the header when expanded (and a one-line summary when collapsed).
 */
@Composable
private fun FoldSection(
    title: String,
    summary: String,
    detail: String,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(
                        if (expanded) summary else summary,
                        color = Muted,
                        fontSize = 12.sp,
                        maxLines = if (expanded) 3 else 2,
                    )
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "折叠" else "展开",
                    tint = Muted,
                )
            }
            if (expanded) {
                HorizontalDivider(color = Border)
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        color = PanelAlt,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            detail,
                            modifier = Modifier.padding(12.dp),
                            color = Muted,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        )
                    }
                    content()
                }
            }
        }
    }
}

@Composable
private fun FieldHelp(text: String) {
    Text(
        text,
        color = Muted,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    )
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    description: String? = null,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, fontSize = 13.sp)
            if (description != null) {
                Text(description, color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeDropdown(
    value: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = modeLabel(value),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("模式") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("反向 (ANTHROPIC_BASE_URL)") },
                onClick = {
                    onChange("reverse")
                    expanded = false
                },
            )
            DropdownMenuItem(
                text = { Text("正向 (Remote Control)") },
                onClick = {
                    onChange("forward")
                    expanded = false
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleDropdown(
    label: String,
    value: String,
    options: List<String>,
    enabled: Boolean,
    onChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt) },
                    onClick = {
                        onChange(opt)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun MetaGrid(items: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (k, v) ->
                    Surface(
                        color = PanelAlt,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text(k, color = Muted, fontSize = 11.sp)
                            Text(
                                v,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 2,
                            )
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private fun modeLabel(mode: String) = when (mode) {
    "forward" -> "正向"
    "reverse" -> "反向"
    else -> mode
}

private fun statusPort(model: AppModel): String? =
    (model.status["port"] as? Number)?.toString() ?: model.form.port.toString()
