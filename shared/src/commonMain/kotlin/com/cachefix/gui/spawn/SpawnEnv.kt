package com.cachefix.gui.spawn

import com.cachefix.gui.path.Paths
import com.cachefix.gui.settings.DEFAULT_PORT
import com.cachefix.gui.settings.ProxyMode
import com.cachefix.gui.settings.validatePort

/**
 * Build environment for spawning cache-fix-proxy.
 * Pure: no process spawn.
 *
 * @see docs/design/2026-07-22-gui-design.md §6.1
 */
fun buildProxySpawnEnv(
    port: Any? = DEFAULT_PORT,
    mode: ProxyMode = ProxyMode.REVERSE,
    effectiveConfigRoot: String,
    caDir: String? = null,
    baseEnv: Map<String, String?> = emptyMap(),
    extraEnv: Map<String, String?> = emptyMap(),
): Map<String, String> {
    if (effectiveConfigRoot.trim().isEmpty()) {
        throw IllegalArgumentException("effectiveConfigRoot is required")
    }

    val portStr = validatePort(port)
    val resolvedCaDir =
        if (caDir != null && caDir.trim().isNotEmpty()) {
            Paths.normalize(caDir)
        } else {
            Paths.join(effectiveConfigRoot, "cache-fix-ca")
        }

    val env = mutableMapOf<String, String>()
    for ((k, v) in baseEnv) {
        if (v != null) env[k] = v
    }
    for ((k, v) in extraEnv) {
        if (v == null || v.isEmpty()) continue
        env[k] = v
    }

    env["CACHE_FIX_PROXY_PORT"] = portStr
    env["CLAUDE_CONFIG_DIR"] = Paths.normalize(effectiveConfigRoot)
    env["CACHE_FIX_CA_DIR"] = resolvedCaDir

    if (mode == ProxyMode.FORWARD) {
        env["CACHE_FIX_FORWARD_PROXY"] = "on"
    } else {
        env.remove("CACHE_FIX_FORWARD_PROXY")
    }

    return env
}
