package com.cachefix.gui.proxy

/**
 * Proxy discovery ranking + version compatibility (pure, no fs).
 *
 * @see docs/design/2026-07-22-gui-design.md §6
 */

/** Compatible cache-fix package range for v1 (both modes). */
const val COMPATIBLE_RANGE = ">=4.3.0 <5"

/** Source rank: lower = higher priority. */
private val SOURCE_ORDER = mapOf(
    "explicit" to 0,
    "path" to 1,
    "npm-global" to 2,
    "sidecar" to 3,
)

data class ProxyCandidate(
    val source: String,
    val path: String,
    val version: String? = null,
    val compatible: Boolean? = null,
)

/**
 * Minimal major.minor.patch compare for range `>=4.3.0 <5`.
 * Accepts plain `x.y.z` (optional leading `v`); rejects non-numeric prerelease tags
 * unless they follow the x.y.z core.
 */
fun satisfiesCompatible(version: String?): Boolean {
    if (version == null || version.trim().isEmpty()) return false
    val parsed = parseSemver(version) ?: return false
    val (major, minor, _) = parsed
    if (major < 4) return false
    if (major == 4 && minor < 3) return false
    if (major >= 5) return false
    return true
}

/**
 * Rank candidates by discovery order. Pure sort/filter — no fs.
 * Source order: explicit → path → npm-global → sidecar.
 * Within same source, original order is stable.
 */
fun rankProxyCandidates(candidates: List<ProxyCandidate>): List<ProxyCandidate> {
    return candidates
        .mapIndexed { index, c -> Triple(c, index, SOURCE_ORDER[c.source] ?: 99) }
        .sortedWith(compareBy({ it.third }, { it.second }))
        .map { it.first }
}

/**
 * Select first compatible candidate after ranking.
 * A candidate is compatible if:
 *   - `compatible === true`, or
 *   - `compatible` is null/undefined and `satisfiesCompatible(version)`, or
 *   - source === "sidecar" (always treated compatible by construction)
 */
fun selectProxy(candidates: List<ProxyCandidate>): ProxyCandidate? {
    for (c in rankProxyCandidates(candidates)) {
        if (isCompatibleCandidate(c)) return c
    }
    return null
}

private fun isCompatibleCandidate(c: ProxyCandidate): Boolean {
    if (c.source == "sidecar") return true
    if (c.compatible != null) return c.compatible
    return satisfiesCompatible(c.version)
}

private fun parseSemver(version: String): Triple<Int, Int, Int>? {
    val s = version.trim().replace(Regex("^v", RegexOption.IGNORE_CASE), "")
    val m = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+].*)?$").find(s) ?: return null
    return Triple(
        m.groupValues[1].toInt(),
        m.groupValues[2].toInt(),
        m.groupValues[3].toInt(),
    )
}
