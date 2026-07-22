/**
 * Cache-fix /health response parser.
 * Pure: no network I/O.
 *
 * @see docs/design/2026-07-22-gui-design.md §4.3.1
 */

/**
 * Parse a cache-fix health HTTP response.
 *
 * Recognition predicate (must all hold for non-foreign when body parses as object):
 * - JSON object
 * - status ∈ { "ok", "degraded" }
 * - and (typeof version === "string" OR typeof forward_proxy === "boolean")
 *
 * @param {number | null | undefined} httpStatus  HTTP status, or null/undefined if unreachable
 * @param {string | null | undefined} bodyText
 * @returns {{
 *   kind: "ok" | "degraded" | "foreign" | "unreachable",
 *   version?: string,
 *   forwardProxy?: boolean,
 *   failed_extensions?: unknown,
 *   hint?: unknown,
 *   httpStatus?: number | null,
 * }}
 */
export function parseCacheFixHealth(httpStatus, bodyText) {
  // Unreachable: no status or no body that could be health
  if (httpStatus == null || bodyText == null) {
    return { kind: "unreachable", httpStatus: httpStatus ?? null };
  }

  let parsed;
  try {
    parsed = JSON.parse(bodyText);
  } catch {
    // Non-JSON body with a transport-level status still counts as foreign/unknown
    // (connection succeeded but not our health endpoint).
    if (httpStatus === 0) {
      return { kind: "unreachable", httpStatus };
    }
    return { kind: "foreign", httpStatus };
  }

  if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) {
    return { kind: "foreign", httpStatus };
  }

  const status = parsed.status;
  const hasVersion = typeof parsed.version === "string";
  const hasForward = typeof parsed.forward_proxy === "boolean";

  if (
    (status === "ok" || status === "degraded") &&
    (hasVersion || hasForward)
  ) {
    /** @type {{ kind: "ok" | "degraded", version?: string, forwardProxy?: boolean, failed_extensions?: unknown, hint?: unknown, httpStatus: number }} */
    const result = {
      kind: status,
      httpStatus,
    };
    if (hasVersion) result.version = parsed.version;
    if (hasForward) result.forwardProxy = parsed.forward_proxy;
    if ("failed_extensions" in parsed) result.failed_extensions = parsed.failed_extensions;
    if ("hint" in parsed) result.hint = parsed.hint;
    return result;
  }

  return { kind: "foreign", httpStatus };
}
