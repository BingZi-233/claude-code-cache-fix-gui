package com.cachefix.gui.io

import com.cachefix.gui.config.ClaudeConfig
import com.cachefix.gui.path.Paths
import com.cachefix.gui.settings.DEFAULT_PORT
import com.cachefix.gui.settings.ProxyMode
import com.cachefix.gui.settings.applyClaudeEnv
import com.cachefix.gui.settings.removeClaudeEnv
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

data class ClaudePaths(
    val configRoot: String,
    val settingsFile: String,
    val caDir: String,
    val caPem: String,
)

fun resolvePaths(
    env: Map<String, String?> = System.getenv(),
    home: String = System.getProperty("user.home"),
    configDirOverride: String? = null,
): ClaudePaths {
    val configRoot = ClaudeConfig.resolveClaudeConfigDir(env, home, configDirOverride)
    return ClaudePaths(
        configRoot = configRoot,
        settingsFile = ClaudeConfig.settingsPath(env, home, configDirOverride),
        caDir = Paths.join(configRoot, "cache-fix-ca"),
        caPem = Paths.join(configRoot, "cache-fix-ca", "ca.pem"),
    )
}

private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

/** Load settings.json; missing file → empty map. Fail closed if top-level is not an object. */
fun loadSettings(filePath: String): Map<String, Any?> {
    val path = Path.of(filePath)
    if (!Files.exists(path)) return emptyMap()
    val raw = Files.readString(path, StandardCharsets.UTF_8)
    val element = try {
        json.parseToJsonElement(raw)
    } catch (e: Exception) {
        throw IllegalArgumentException("settings.json is not valid JSON: ${e.message}")
    }
    if (element !is JsonObject) {
        throw IllegalArgumentException("settings.json top-level must be a JSON object (fail closed)")
    }
    return jsonObjectToMap(element)
}

fun saveSettings(filePath: String, settings: Map<String, Any?>, writeBackup: Boolean = true) {
    val path = Path.of(filePath)
    Files.createDirectories(path.parent)
    if (writeBackup && Files.exists(path)) {
        Files.copy(path, Path.of("$filePath.bak"), StandardCopyOption.REPLACE_EXISTING)
    }
    val body = json.encodeToString(JsonObject.serializer(), mapToJsonObject(settings)) + "\n"
    val tmp = Path.of("$filePath.${ProcessHandle.current().pid()}.${System.currentTimeMillis()}.tmp")
    Files.writeString(tmp, body, StandardCharsets.UTF_8)
    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}

data class WireResult(
    val paths: ClaudePaths,
    val expectedEnv: Map<String, String>,
    val anthropicBaseUrlBackup: String?,
    val anthropicFoundryBaseUrlBackup: String? = null,
    val settingsFile: String,
)

data class UnwireResult(
    val paths: ClaudePaths,
    val skipped: List<String>,
    val anthropicBaseUrlBackup: String?,
    val anthropicFoundryBaseUrlBackup: String? = null,
    val settingsFile: String,
)

fun wireClaudeSettings(
    mode: ProxyMode,
    port: Any? = DEFAULT_PORT,
    configDirOverride: String? = null,
    env: Map<String, String?> = System.getenv(),
    home: String = System.getProperty("user.home"),
    anthropicBaseUrlBackup: String? = null,
    anthropicFoundryBaseUrlBackup: String? = null,
): WireResult {
    val paths = resolvePaths(env, home, configDirOverride)
    val settings = loadSettings(paths.settingsFile)
    if (mode == ProxyMode.FORWARD && !Files.exists(Path.of(paths.caPem))) {
        throw IllegalStateException(
            "Forward mode requires CA at ${paths.caPem}. Start the proxy in forward mode first so it can generate the CA.",
        )
    }
    val result = applyClaudeEnv(
        settings = settings,
        mode = mode,
        port = port,
        caPemPath = paths.caPem,
        anthropicBaseUrlBackup = anthropicBaseUrlBackup,
        anthropicFoundryBaseUrlBackup = anthropicFoundryBaseUrlBackup,
    )
    saveSettings(paths.settingsFile, result.nextSettings)
    return WireResult(
        paths = paths,
        expectedEnv = result.expectedEnv,
        anthropicBaseUrlBackup = result.anthropicBaseUrlBackup,
        anthropicFoundryBaseUrlBackup = result.anthropicFoundryBaseUrlBackup,
        settingsFile = paths.settingsFile,
    )
}

fun unwireClaudeSettings(
    expectedEnv: Map<String, String>,
    configDirOverride: String? = null,
    env: Map<String, String?> = System.getenv(),
    home: String = System.getProperty("user.home"),
    anthropicBaseUrlBackup: String? = null,
    anthropicFoundryBaseUrlBackup: String? = null,
): UnwireResult {
    val paths = resolvePaths(env, home, configDirOverride)
    val settings = loadSettings(paths.settingsFile)
    val result = removeClaudeEnv(
        settings,
        expectedEnv,
        anthropicBaseUrlBackup = anthropicBaseUrlBackup,
        anthropicFoundryBaseUrlBackup = anthropicFoundryBaseUrlBackup,
    )
    saveSettings(paths.settingsFile, result.nextSettings)
    return UnwireResult(
        paths = paths,
        skipped = result.skipped,
        anthropicBaseUrlBackup = result.anthropicBaseUrlBackup,
        anthropicFoundryBaseUrlBackup = result.anthropicFoundryBaseUrlBackup,
        settingsFile = paths.settingsFile,
    )
}

private fun jsonObjectToMap(obj: JsonObject): Map<String, Any?> {
    val out = linkedMapOf<String, Any?>()
    for ((k, v) in obj) {
        out[k] = jsonElementToAny(v)
    }
    return out
}

private fun jsonElementToAny(el: JsonElement): Any? = when (el) {
    is JsonNull -> null
    is JsonPrimitive -> {
        when {
            el.isString -> el.content
            el.contentOrNull == "true" -> true
            el.contentOrNull == "false" -> false
            el.content.toIntOrNull() != null -> el.content.toInt()
            el.content.toDoubleOrNull() != null -> el.content.toDouble()
            else -> el.content
        }
    }
    is JsonObject -> jsonObjectToMap(el)
    is JsonArray -> el.map { jsonElementToAny(it) }
    else -> el.toString()
}

@Suppress("UNCHECKED_CAST")
private fun mapToJsonObject(map: Map<String, Any?>): JsonObject = buildJsonObject {
    for ((k, v) in map) {
        when (v) {
            null -> put(k, JsonNull)
            is String -> put(k, v)
            is Boolean -> put(k, v)
            is Number -> put(k, JsonPrimitive(v))
            is Map<*, *> -> put(k, mapToJsonObject(v as Map<String, Any?>))
            is List<*> -> put(
                k,
                JsonArray(
                    v.map { item ->
                        when (item) {
                            null -> JsonNull
                            is String -> JsonPrimitive(item)
                            is Boolean -> JsonPrimitive(item)
                            is Number -> JsonPrimitive(item)
                            is Map<*, *> -> mapToJsonObject(item as Map<String, Any?>)
                            else -> JsonPrimitive(item.toString())
                        }
                    },
                ),
            )
            else -> put(k, v.toString())
        }
    }
}
