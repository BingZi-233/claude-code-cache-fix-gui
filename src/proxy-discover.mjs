/**
 * Proxy discovery ranking + version compatibility (pure, no fs).
 *
 * @see docs/design/2026-07-22-gui-design.md §6
 */

/**
 * Compatible cache-fix package range for v1 (both modes).
 * Forward-proxy requires ≥4.3.0; single range avoids mode-split complexity.
 */
export const COMPATIBLE_RANGE = ">=4.3.0 <5";

/** Source rank: lower = higher priority. */
const SOURCE_ORDER = {
  explicit: 0,
  path: 1,
  "npm-global": 2,
  sidecar: 3,
};

/**
 * Minimal major.minor.patch compare for range `>=4.3.0 <5`.
 * Accepts plain `x.y.z` (optional leading `v`); rejects non-numeric prerelease tags.
 *
 * @param {string | undefined | null} version
 * @returns {boolean}
 */
export function satisfiesCompatible(version) {
  if (typeof version !== "string" || version.trim() === "") return false;
  const parsed = parseSemver(version);
  if (!parsed) return false;
  const { major, minor, patch } = parsed;
  // >= 4.3.0
  if (major < 4) return false;
  if (major === 4 && minor < 3) return false;
  // < 5
  if (major >= 5) return false;
  // major === 4 && (minor > 3 || (minor === 3 && patch >= 0)) — always true for patch >= 0
  void patch;
  return true;
}

/**
 * Rank candidates by discovery order. Pure sort/filter — no fs.
 * Does not drop incompatible entries; use selectProxy for first compatible.
 *
 * Source order: explicit → path → npm-global → sidecar.
 * Within same source, original order is stable.
 *
 * @param {Array<{ source: string, path: string, version?: string, compatible?: boolean }>} candidates
 * @returns {typeof candidates}
 */
export function rankProxyCandidates(candidates) {
  if (!Array.isArray(candidates)) {
    throw new Error("candidates must be an array");
  }
  return candidates
    .map((c, index) => ({ c, index }))
    .sort((a, b) => {
      const ra = SOURCE_ORDER[a.c.source] ?? 99;
      const rb = SOURCE_ORDER[b.c.source] ?? 99;
      if (ra !== rb) return ra - rb;
      return a.index - b.index;
    })
    .map(({ c }) => c);
}

/**
 * Select first compatible candidate after ranking.
 * A candidate is compatible if:
 *   - `compatible === true`, or
 *   - `compatible` is undefined and `satisfiesCompatible(version)`, or
 *   - source === "sidecar" (always treated compatible by construction)
 *
 * @param {Array<{ source: string, path: string, version?: string, compatible?: boolean }>} candidates
 * @returns {{ source: string, path: string, version?: string, compatible?: boolean } | null}
 */
export function selectProxy(candidates) {
  const ranked = rankProxyCandidates(candidates);
  for (const c of ranked) {
    if (isCompatibleCandidate(c)) return c;
  }
  return null;
}

/**
 * @param {{ source: string, version?: string, compatible?: boolean }} c
 * @returns {boolean}
 */
function isCompatibleCandidate(c) {
  if (c.source === "sidecar") return true;
  if (typeof c.compatible === "boolean") return c.compatible;
  return satisfiesCompatible(c.version);
}

/**
 * @param {string} version
 * @returns {{ major: number, minor: number, patch: number } | null}
 */
function parseSemver(version) {
  const s = version.trim().replace(/^v/i, "");
  // x.y.z with optional extra suffix ignored only if pure digits parts
  const m = /^(\d+)\.(\d+)\.(\d+)(?:[-+].*)?$/.exec(s);
  if (!m) return null;
  return {
    major: Number(m[1]),
    minor: Number(m[2]),
    patch: Number(m[3]),
  };
}
