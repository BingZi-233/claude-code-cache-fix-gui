package com.cachefix.gui.ui

/**
 * Form state ↔ proxyEnv mapping (scheme B dedicated fields).
 * Pure transforms — unit-tested; used by Compose UI and save path.
 */
data class ProxyForm(
    val port: Int = 9801,
    val mode: String = "reverse", // reverse | forward
    val upstream: String = "https://api.anthropic.com",
    val bind: String = "127.0.0.1",
    val timeout: String = "600000",
    val debug: Boolean = false,
    val httpsProxy: String = "",
    val httpProxy: String = "",
    val noProxy: String = "",
    val caFile: String = "",
    val rejectUnauthorized: Boolean = true,
    val caDir: String = "",
    val downloadRewrite: Boolean = false,
    val oauthRefresh: Boolean = false,
    val bootstrapMode: String = "",
    val thinkingDisplay: String = "",
    val thinkingSanitize: String = "",
    val imageGuard: Boolean = false,
    val sessionMirror: Boolean = false,
    val upstreamErrorLog: Boolean = false,
    val extraEnvText: String = "",
)

private val DEDICATED_KEYS = setOf(
    "CACHE_FIX_PROXY_UPSTREAM",
    "CACHE_FIX_PROXY_BIND",
    "CACHE_FIX_PROXY_TIMEOUT",
    "CACHE_FIX_PROXY_CA_FILE",
    "HTTPS_PROXY",
    "HTTP_PROXY",
    "NO_PROXY",
    "https_proxy",
    "http_proxy",
    "no_proxy",
    "CACHE_FIX_CA_DIR",
    "CACHE_FIX_BOOTSTRAP_MODE",
    "CACHE_FIX_THINKING_DISPLAY",
    "CACHE_FIX_THINKING_SANITIZE",
    "CACHE_FIX_DEBUG",
    "CACHE_FIX_DOWNLOAD_REWRITE",
    "CACHE_FIX_OAUTH_REFRESH",
    "CACHE_FIX_IMAGE_GUARD",
    "CACHE_FIX_SESSION_MIRROR",
    "CACHE_FIX_UPSTREAM_ERROR_LOG",
    "CACHE_FIX_PROXY_REJECT_UNAUTHORIZED",
)

fun ProxyForm.toProxyEnv(): Map<String, String> {
    val env = linkedMapOf<String, String>()
    fun set(k: String, v: String) {
        val t = v.trim()
        if (t.isNotEmpty()) env[k] = t
    }
    set("CACHE_FIX_PROXY_UPSTREAM", upstream)
    set("CACHE_FIX_PROXY_BIND", bind)
    set("CACHE_FIX_PROXY_TIMEOUT", timeout)
    set("CACHE_FIX_PROXY_CA_FILE", caFile)
    set("HTTPS_PROXY", httpsProxy)
    set("HTTP_PROXY", httpProxy)
    set("NO_PROXY", noProxy)
    set("CACHE_FIX_CA_DIR", caDir)
    set("CACHE_FIX_BOOTSTRAP_MODE", bootstrapMode)
    set("CACHE_FIX_THINKING_DISPLAY", thinkingDisplay)
    set("CACHE_FIX_THINKING_SANITIZE", thinkingSanitize)

    env["HTTPS_PROXY"]?.let { env["https_proxy"] = it }
    env["HTTP_PROXY"]?.let { env["http_proxy"] = it }
    env["NO_PROXY"]?.let { env["no_proxy"] = it }

    if (debug) env["CACHE_FIX_DEBUG"] = "1"
    if (downloadRewrite) env["CACHE_FIX_DOWNLOAD_REWRITE"] = "on"
    if (oauthRefresh) env["CACHE_FIX_OAUTH_REFRESH"] = "on"
    if (imageGuard) env["CACHE_FIX_IMAGE_GUARD"] = "1"
    if (sessionMirror) env["CACHE_FIX_SESSION_MIRROR"] = "on"
    if (upstreamErrorLog) env["CACHE_FIX_UPSTREAM_ERROR_LOG"] = "on"
    if (!rejectUnauthorized) env["CACHE_FIX_PROXY_REJECT_UNAUTHORIZED"] = "0"

    if (extraEnvText.isNotBlank()) {
        for (line in extraEnvText.split('\n', '\r')) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val eq = trimmed.indexOf('=')
            if (eq <= 0) continue
            val k = trimmed.substring(0, eq).trim()
            val v = trimmed.substring(eq + 1).trim()
            if (k.isEmpty() || k in DEDICATED_KEYS) continue
            if (v.isNotEmpty()) env[k] = v
        }
    }
    return env
}

fun ProxyForm.mergedFromProxyEnv(pe: Map<String, String>?): ProxyForm {
    if (pe.isNullOrEmpty()) return this
    fun on(v: String?) = v == "on" || v == "1" || v == "true"
    val lines = pe.entries
        .filter { (k, v) -> k !in DEDICATED_KEYS && v.isNotEmpty() }
        .map { "${it.key}=${it.value}" }
    val rawRej = pe["CACHE_FIX_PROXY_REJECT_UNAUTHORIZED"]
    return copy(
        upstream = pe["CACHE_FIX_PROXY_UPSTREAM"] ?: upstream,
        bind = pe["CACHE_FIX_PROXY_BIND"] ?: bind,
        timeout = pe["CACHE_FIX_PROXY_TIMEOUT"] ?: timeout,
        caFile = pe["CACHE_FIX_PROXY_CA_FILE"] ?: caFile,
        httpsProxy = pe["HTTPS_PROXY"] ?: pe["https_proxy"] ?: httpsProxy,
        httpProxy = pe["HTTP_PROXY"] ?: pe["http_proxy"] ?: httpProxy,
        noProxy = pe["NO_PROXY"] ?: pe["no_proxy"] ?: noProxy,
        caDir = pe["CACHE_FIX_CA_DIR"] ?: caDir,
        bootstrapMode = pe["CACHE_FIX_BOOTSTRAP_MODE"] ?: bootstrapMode,
        thinkingDisplay = pe["CACHE_FIX_THINKING_DISPLAY"] ?: thinkingDisplay,
        thinkingSanitize = pe["CACHE_FIX_THINKING_SANITIZE"] ?: thinkingSanitize,
        debug = on(pe["CACHE_FIX_DEBUG"]),
        downloadRewrite = on(pe["CACHE_FIX_DOWNLOAD_REWRITE"]),
        oauthRefresh = on(pe["CACHE_FIX_OAUTH_REFRESH"]),
        imageGuard = on(pe["CACHE_FIX_IMAGE_GUARD"]),
        sessionMirror = on(pe["CACHE_FIX_SESSION_MIRROR"]),
        upstreamErrorLog = on(pe["CACHE_FIX_UPSTREAM_ERROR_LOG"]),
        rejectUnauthorized = !(rawRej == "0" || rawRej == "false"),
        extraEnvText = lines.joinToString("\n"),
    )
}
