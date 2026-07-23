package com.cachefix.gui.settings

import com.cachefix.gui.path.Paths

/** Upstream default listen port. */
const val DEFAULT_PORT = 9801

const val KEY_ANTHROPIC_BASE_URL = "ANTHROPIC_BASE_URL"
const val KEY_ANTHROPIC_FOUNDRY_BASE_URL = "ANTHROPIC_FOUNDRY_BASE_URL"

/** Hosts always ensured on forward apply; only these stripped on unwire. */
private val LOCALHOST_NO_PROXY_HOSTS = listOf("127.0.0.1", "localhost", "::1")

enum class ProxyMode {
    REVERSE,
    FORWARD;

    companion object {
        fun from(value: String): ProxyMode = when (value) {
            "reverse" -> REVERSE
            "forward" -> FORWARD
            else -> throw IllegalArgumentException(
                "unknown mode: \"$value\" (expected \"reverse\" | \"forward\")",
            )
        }
    }

    fun wireName(): String = when (this) {
        REVERSE -> "reverse"
        FORWARD -> "forward"
    }
}

/** Validate port as decimal integer 1..65535; returns decimal string. */
fun validatePort(port: Any?): String {
    when (port) {
        is Int -> {
            if (port < 1 || port > 65535) {
                throw IllegalArgumentException("invalid port: $port (must be integer 1..65535)")
            }
            return port.toString()
        }
        is Long -> {
            if (port < 1 || port > 65535) {
                throw IllegalArgumentException("invalid port: $port (must be integer 1..65535)")
            }
            return port.toString()
        }
        is String -> {
            val trimmed = port.trim()
            if (!Regex("^\\d+$").matches(trimmed)) {
                throw IllegalArgumentException(
                    "invalid port: \"$port\" (must be decimal integer)",
                )
            }
            val n = trimmed.toInt()
            if (n < 1 || n > 65535) {
                throw IllegalArgumentException("invalid port: $port (must be 1..65535)")
            }
            return n.toString()
        }
        else -> throw IllegalArgumentException(
            "invalid port: $port (must be number or decimal string)",
        )
    }
}

/** Merge localhost hosts into an existing NO_PROXY list. */
fun mergeNoProxy(existing: String?): String {
    val parts = splitNoProxy(existing).toMutableList()
    val seen = parts.toMutableSet()
    for (host in LOCALHOST_NO_PROXY_HOSTS) {
        if (host !in seen) {
            parts.add(host)
            seen.add(host)
        }
    }
    return parts.joinToString(",")
}

/**
 * Remove only the three localhost hosts; return null if empty after strip.
 * Corp entries (e.g. corp.example) survive.
 */
fun stripLocalhostNoProxy(existing: String?): String? {
    if (existing == null) return null
    val drop = LOCALHOST_NO_PROXY_HOSTS.toSet()
    val kept = splitNoProxy(existing).filter { it !in drop }
    if (kept.isEmpty()) return null
    return kept.joinToString(",")
}

/**
 * Compute the exact env key/value map the GUI will write for a mode.
 *
 * @param includeFoundry when true (reverse), also set ANTHROPIC_FOUNDRY_BASE_URL to the same proxy URL.
 *   Callers should pass true only if settings already had a non-empty FOUNDRY URL.
 */
fun computeExpectedEnv(
    mode: ProxyMode,
    port: Any? = DEFAULT_PORT,
    caPemPath: String? = null,
    existingNoProxy: String? = null,
    includeFoundry: Boolean = false,
): Map<String, String> {
    val portStr = validatePort(port)
    return when (mode) {
        ProxyMode.REVERSE -> {
            val url = "http://127.0.0.1:$portStr"
            buildMap {
                put(KEY_ANTHROPIC_BASE_URL, url)
                if (includeFoundry) {
                    put(KEY_ANTHROPIC_FOUNDRY_BASE_URL, url)
                }
            }
        }
        ProxyMode.FORWARD -> {
            if (caPemPath == null || caPemPath.trim().isEmpty()) {
                throw IllegalArgumentException("caPemPath is required for forward mode")
            }
            val proxyUrl = "http://127.0.0.1:$portStr"
            val noProxy = mergeNoProxy(existingNoProxy)
            mapOf(
                "HTTPS_PROXY" to proxyUrl,
                "https_proxy" to proxyUrl,
                "NODE_EXTRA_CA_CERTS" to Paths.normalize(caPemPath),
                "NO_PROXY" to noProxy,
                "no_proxy" to noProxy,
            )
        }
    }
}

data class ApplyResult(
    val nextSettings: Map<String, Any?>,
    val expectedEnv: Map<String, String>,
    val anthropicBaseUrlBackup: String?,
    val anthropicFoundryBaseUrlBackup: String? = null,
)

data class RemoveResult(
    val nextSettings: Map<String, Any?>,
    val skipped: List<String>,
    val anthropicBaseUrlBackup: String?,
    val anthropicFoundryBaseUrlBackup: String? = null,
)

private fun nonEmpty(env: Map<String, String>, key: String): String? {
    val v = env[key] ?: return null
    val t = v.trim()
    return t.ifEmpty { null }
}

/**
 * Apply reverse or forward env into a Claude settings object.
 * Settings are modeled as Map with optional "env" map of string values.
 *
 * ANTHROPIC_BASE_URL:
 * - Reverse: snapshot original (if not already local proxy), then point at local proxy.
 * - Forward: snapshot + remove.
 * - Unwire: restore backup when key is absent.
 *
 * ANTHROPIC_FOUNDRY_BASE_URL:
 * - Reverse: if already present (non-empty), also point it at local proxy (same as BASE_URL).
 * - Forward: snapshot + remove if present (like ANTHROPIC_BASE_URL).
 * - Unwire: restore backups when keys are absent.
 */
@Suppress("UNCHECKED_CAST")
fun applyClaudeEnv(
    settings: Map<String, Any?>,
    mode: ProxyMode,
    port: Any? = DEFAULT_PORT,
    caPemPath: String? = null,
    anthropicBaseUrlBackup: String? = null,
    anthropicFoundryBaseUrlBackup: String? = null,
): ApplyResult {
    assertSettingsShape(settings)
    val env = mutableMapOf<String, String>()
    val existingEnv = settings["env"] as? Map<*, *>
    if (existingEnv != null) {
        for ((k, v) in existingEnv) {
            if (k is String && v != null) env[k] = v.toString()
        }
    }

    var baseBackup = anthropicBaseUrlBackup
    var foundryBackup = anthropicFoundryBaseUrlBackup

    when (mode) {
        ProxyMode.REVERSE -> {
            val forwardLike = buildForwardStripCandidates(port, caPemPath, env)
            for ((k, v) in forwardLike) {
                if (env[k] == v) env.remove(k)
            }
            for (key in listOf("NO_PROXY", "no_proxy")) {
                if (key in env) {
                    val stripped = stripLocalhostNoProxy(env[key])
                    if (stripped == null) env.remove(key) else env[key] = stripped
                }
            }

            val portStr = validatePort(port)
            val proxyUrl = "http://127.0.0.1:$portStr"

            // Snapshot original ANTHROPIC_BASE_URL before overwrite (skip if already local proxy).
            val existingBase = nonEmpty(env, KEY_ANTHROPIC_BASE_URL)
            if (existingBase != null && existingBase != proxyUrl) {
                baseBackup = existingBase
            }

            // FOUNDRY: only manage if already present in settings
            val existingFoundry = nonEmpty(env, KEY_ANTHROPIC_FOUNDRY_BASE_URL)
            val includeFoundry = existingFoundry != null
            if (includeFoundry && existingFoundry != proxyUrl) {
                // Preserve original non-proxy value for restore on unwire
                foundryBackup = existingFoundry
            }

            val expectedEnv = computeExpectedEnv(
                ProxyMode.REVERSE,
                port,
                includeFoundry = includeFoundry,
            )
            env.putAll(expectedEnv)
            return ApplyResult(
                nextSettings = settings.toMutableMap().also { it["env"] = env.toMap() },
                expectedEnv = expectedEnv,
                anthropicBaseUrlBackup = baseBackup,
                anthropicFoundryBaseUrlBackup = foundryBackup,
            )
        }
        ProxyMode.FORWARD -> {
            val existingNoProxy = env["NO_PROXY"] ?: env["no_proxy"]

            // Snapshot then remove ANTHROPIC_BASE_URL if present.
            nonEmpty(env, KEY_ANTHROPIC_BASE_URL)?.let { v ->
                baseBackup = v
                env.remove(KEY_ANTHROPIC_BASE_URL)
            }
            // Snapshot then remove ANTHROPIC_FOUNDRY_BASE_URL if present.
            nonEmpty(env, KEY_ANTHROPIC_FOUNDRY_BASE_URL)?.let { v ->
                foundryBackup = v
                env.remove(KEY_ANTHROPIC_FOUNDRY_BASE_URL)
            }

            val expectedEnv = computeExpectedEnv(
                ProxyMode.FORWARD,
                port,
                caPemPath,
                existingNoProxy,
            )
            env.putAll(expectedEnv)
            return ApplyResult(
                nextSettings = settings.toMutableMap().also { it["env"] = env.toMap() },
                expectedEnv = expectedEnv,
                anthropicBaseUrlBackup = baseBackup,
                anthropicFoundryBaseUrlBackup = foundryBackup,
            )
        }
    }
}

/**
 * Remove GUI-managed env keys whose current values exact-match expectedEnv.
 * Restores ANTHROPIC_BASE_URL / ANTHROPIC_FOUNDRY_BASE_URL backups when absent.
 */
@Suppress("UNCHECKED_CAST")
fun removeClaudeEnv(
    settings: Map<String, Any?>,
    expectedEnv: Map<String, String>?,
    anthropicBaseUrlBackup: String? = null,
    anthropicFoundryBaseUrlBackup: String? = null,
): RemoveResult {
    assertSettingsShape(settings)
    val env = mutableMapOf<String, String>()
    val existingEnv = settings["env"] as? Map<*, *>
    if (existingEnv != null) {
        for ((k, v) in existingEnv) {
            if (k is String && v != null) env[k] = v.toString()
        }
    }
    val skipped = mutableListOf<String>()
    var baseBackup = anthropicBaseUrlBackup
    var foundryBackup = anthropicFoundryBaseUrlBackup

    for ((key, expectedVal) in (expectedEnv ?: emptyMap())) {
        if (key == "NO_PROXY" || key == "no_proxy") continue
        if (key !in env) continue
        if (env[key] == expectedVal) {
            env.remove(key)
        } else {
            skipped.add(key)
        }
    }

    for (key in listOf("NO_PROXY", "no_proxy")) {
        if (key in env) {
            val stripped = stripLocalhostNoProxy(env[key])
            if (stripped == null) env.remove(key) else env[key] = stripped
        }
    }

    if (
        baseBackup != null &&
        baseBackup.isNotEmpty() &&
        KEY_ANTHROPIC_BASE_URL !in env
    ) {
        env[KEY_ANTHROPIC_BASE_URL] = baseBackup
        baseBackup = null
    }
    if (
        foundryBackup != null &&
        foundryBackup.isNotEmpty() &&
        KEY_ANTHROPIC_FOUNDRY_BASE_URL !in env
    ) {
        env[KEY_ANTHROPIC_FOUNDRY_BASE_URL] = foundryBackup
        foundryBackup = null
    }

    return RemoveResult(
        nextSettings = settings.toMutableMap().also { it["env"] = env.toMap() },
        skipped = skipped,
        anthropicBaseUrlBackup = baseBackup,
        anthropicFoundryBaseUrlBackup = foundryBackup,
    )
}

private fun splitNoProxy(existing: String?): List<String> {
    if (existing.isNullOrEmpty()) return emptyList()
    val seen = mutableSetOf<String>()
    val out = mutableListOf<String>()
    for (s in existing.split(",")) {
        val t = s.trim()
        if (t.isNotEmpty() && t !in seen) {
            seen.add(t)
            out.add(t)
        }
    }
    return out
}

private fun buildForwardStripCandidates(
    port: Any?,
    caPemPath: String?,
    env: Map<String, String>,
): Map<String, String> {
    val portStr = validatePort(port ?: DEFAULT_PORT)
    val proxyUrl = "http://127.0.0.1:$portStr"
    val candidates = mutableMapOf(
        "HTTPS_PROXY" to proxyUrl,
        "https_proxy" to proxyUrl,
    )
    if (caPemPath != null && caPemPath.trim().isNotEmpty()) {
        candidates["NODE_EXTRA_CA_CERTS"] = Paths.normalize(caPemPath)
    } else {
        val ca = env["NODE_EXTRA_CA_CERTS"]
        if (ca != null) {
            if (env["HTTPS_PROXY"] == proxyUrl || env["https_proxy"] == proxyUrl) {
                candidates["NODE_EXTRA_CA_CERTS"] = ca
            }
        }
    }
    return candidates
}

private fun assertSettingsShape(settings: Map<String, Any?>) {
    val env = settings["env"]
    if (env != null && env !is Map<*, *>) {
        throw IllegalArgumentException("settings.env must be a plain object when present")
    }
}
