package com.cachefix.gui.health

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Cache-fix /health response parser.
 * Pure: no network I/O.
 *
 * @see docs/design/2026-07-22-gui-design.md §4.3.1
 */
enum class HealthKind {
    OK,
    DEGRADED,
    FOREIGN,
    UNREACHABLE;

    fun wireName(): String = when (this) {
        OK -> "ok"
        DEGRADED -> "degraded"
        FOREIGN -> "foreign"
        UNREACHABLE -> "unreachable"
    }
}

data class HealthResult(
    val kind: HealthKind,
    val version: String? = null,
    val forwardProxy: Boolean? = null,
    val failedExtensions: Any? = null,
    val hint: Any? = null,
    val httpStatus: Int? = null,
)

private val json = Json { ignoreUnknownKeys = true }

/**
 * Parse a cache-fix health HTTP response.
 *
 * Recognition predicate (must all hold for non-foreign when body parses as object):
 * - JSON object
 * - status ∈ { "ok", "degraded" }
 * - and (typeof version === "string" OR typeof forward_proxy === "boolean")
 */
fun parseCacheFixHealth(httpStatus: Int?, bodyText: String?): HealthResult {
    if (httpStatus == null || bodyText == null) {
        return HealthResult(kind = HealthKind.UNREACHABLE, httpStatus = httpStatus)
    }

    val parsed = try {
        json.parseToJsonElement(bodyText)
    } catch (_: Exception) {
        if (httpStatus == 0) {
            return HealthResult(kind = HealthKind.UNREACHABLE, httpStatus = httpStatus)
        }
        return HealthResult(kind = HealthKind.FOREIGN, httpStatus = httpStatus)
    }

    if (parsed !is JsonObject) {
        return HealthResult(kind = HealthKind.FOREIGN, httpStatus = httpStatus)
    }

    val statusPrim = parsed["status"] as? JsonPrimitive
    val status = statusPrim?.contentOrNull
    val versionPrim = parsed["version"] as? JsonPrimitive
    val hasVersion = versionPrim != null && versionPrim.isString
    val forwardPrim = parsed["forward_proxy"] as? JsonPrimitive
    val hasForward = forwardPrim != null && forwardPrim.booleanOrNull != null

    if ((status == "ok" || status == "degraded") && (hasVersion || hasForward)) {
        val kind = if (status == "ok") HealthKind.OK else HealthKind.DEGRADED
        val failed = parsed["failed_extensions"]
        val hintEl = parsed["hint"]
        return HealthResult(
            kind = kind,
            version = if (hasVersion) versionPrim!!.content else null,
            forwardProxy = if (hasForward) forwardPrim!!.booleanOrNull else null,
            failedExtensions = when (failed) {
                is JsonArray -> failed.map {
                    (it as? JsonPrimitive)?.contentOrNull ?: it.toString()
                }
                null -> null
                else -> failed.toString()
            },
            hint = (hintEl as? JsonPrimitive)?.contentOrNull ?: hintEl?.toString(),
            httpStatus = httpStatus,
        )
    }

    return HealthResult(kind = HealthKind.FOREIGN, httpStatus = httpStatus)
}
