/**
 * Claude settings.json env apply/remove (reverse & forward).
 * Pure: no filesystem I/O.
 *
 * @see docs/design/2026-07-22-gui-design.md §5.3–5.4
 */
import path from "node:path";

/** Upstream default listen port. */
export const DEFAULT_PORT = 9801;

/** Hosts always ensured on forward apply; only these stripped on unwire. */
const LOCALHOST_NO_PROXY_HOSTS = ["127.0.0.1", "localhost", "::1"];

/**
 * Validate port as decimal integer 1..65535.
 * @param {unknown} port
 * @returns {string} decimal string
 */
export function validatePort(port) {
  if (typeof port === "number") {
    if (!Number.isInteger(port) || port < 1 || port > 65535) {
      throw new Error(`invalid port: ${port} (must be integer 1..65535)`);
    }
    return String(port);
  }
  if (typeof port === "string") {
    const trimmed = port.trim();
    if (!/^\d+$/.test(trimmed)) {
      throw new Error(`invalid port: ${JSON.stringify(port)} (must be decimal integer)`);
    }
    const n = Number(trimmed);
    if (n < 1 || n > 65535) {
      throw new Error(`invalid port: ${port} (must be 1..65535)`);
    }
    return String(n);
  }
  throw new Error(`invalid port: ${JSON.stringify(port)} (must be number or decimal string)`);
}

/**
 * Merge localhost hosts into an existing NO_PROXY list.
 * @param {string | undefined} existing
 * @returns {string}
 */
export function mergeNoProxy(existing) {
  const parts = splitNoProxy(existing);
  const seen = new Set(parts);
  for (const host of LOCALHOST_NO_PROXY_HOSTS) {
    if (!seen.has(host)) {
      parts.push(host);
      seen.add(host);
    }
  }
  return parts.join(",");
}

/**
 * Remove only the three localhost hosts; return undefined if empty after strip.
 * Corp entries (e.g. corp.example) survive.
 * @param {string | undefined} existing
 * @returns {string | undefined}
 */
export function stripLocalhostNoProxy(existing) {
  if (existing === undefined || existing === null) return undefined;
  const drop = new Set(LOCALHOST_NO_PROXY_HOSTS);
  const kept = splitNoProxy(existing).filter((h) => !drop.has(h));
  if (kept.length === 0) return undefined;
  return kept.join(",");
}

/**
 * Compute the exact env key/value map the GUI will write for a mode.
 *
 * @param {{
 *   mode: "reverse" | "forward",
 *   port?: number | string,
 *   caPemPath?: string,
 *   existingNoProxy?: string,
 * }} opts
 * @returns {Record<string, string>}
 */
export function computeExpectedEnv({
  mode,
  port = DEFAULT_PORT,
  caPemPath,
  existingNoProxy,
}) {
  const portStr = validatePort(port);
  if (mode === "reverse") {
    return {
      ANTHROPIC_BASE_URL: `http://127.0.0.1:${portStr}`,
    };
  }
  if (mode === "forward") {
    if (typeof caPemPath !== "string" || caPemPath.trim() === "") {
      throw new Error("caPemPath is required for forward mode");
    }
    const proxyUrl = `http://127.0.0.1:${portStr}`;
    const noProxy = mergeNoProxy(existingNoProxy);
    return {
      HTTPS_PROXY: proxyUrl,
      https_proxy: proxyUrl,
      NODE_EXTRA_CA_CERTS: path.normalize(caPemPath),
      NO_PROXY: noProxy,
      no_proxy: noProxy,
    };
  }
  throw new Error(`unknown mode: ${JSON.stringify(mode)} (expected "reverse" | "forward")`);
}

/**
 * Apply reverse or forward env into a Claude settings object.
 *
 * @param {unknown} settings
 * @param {{ mode: "reverse" | "forward", port?: number | string, caPemPath?: string }} opts
 * @param {{ anthropicBaseUrlBackup?: string | null }} [meta]
 * @returns {{
 *   nextSettings: object,
 *   expectedEnv: Record<string, string>,
 *   anthropicBaseUrlBackup: string | null | undefined,
 * }}
 */
export function applyClaudeEnv(settings, opts, meta = {}) {
  assertSettingsShape(settings);
  const { mode, port = DEFAULT_PORT, caPemPath } = opts;
  const env = { ...(settings.env ?? {}) };

  let anthropicBaseUrlBackup = meta.anthropicBaseUrlBackup ?? null;

  if (mode === "reverse") {
    // Strip matching forward keys first (exact-match against recomputed expected not needed here —
    // reverse apply removes any forward dual-case keys we own when values match a recompute if we
    // can; design: strip forward keys only if values exact-match snapshot/recomputed expected).
    // On reverse apply we set reverse URL and strip known forward keys that look like ours for this port.
    const forwardLike = buildForwardStripCandidates(port, caPemPath, env);
    for (const [k, v] of Object.entries(forwardLike)) {
      if (env[k] === v) {
        delete env[k];
      }
    }
    // Strip localhost hosts from NO_PROXY after leaving forward.
    for (const key of ["NO_PROXY", "no_proxy"]) {
      if (key in env) {
        const stripped = stripLocalhostNoProxy(env[key]);
        if (stripped === undefined) delete env[key];
        else env[key] = stripped;
      }
    }

    const expectedEnv = computeExpectedEnv({ mode: "reverse", port });
    Object.assign(env, expectedEnv);
    return {
      nextSettings: { ...settings, env },
      expectedEnv,
      anthropicBaseUrlBackup,
    };
  }

  if (mode === "forward") {
    const existingNoProxy = env.NO_PROXY || env.no_proxy;
    // Snapshot then remove ANTHROPIC_BASE_URL if present.
    if (typeof env.ANTHROPIC_BASE_URL === "string" && env.ANTHROPIC_BASE_URL.trim() !== "") {
      anthropicBaseUrlBackup = env.ANTHROPIC_BASE_URL;
      delete env.ANTHROPIC_BASE_URL;
    }

    const expectedEnv = computeExpectedEnv({
      mode: "forward",
      port,
      caPemPath,
      existingNoProxy,
    });
    Object.assign(env, expectedEnv);
    return {
      nextSettings: { ...settings, env },
      expectedEnv,
      anthropicBaseUrlBackup,
    };
  }

  throw new Error(`unknown mode: ${JSON.stringify(mode)}`);
}

/**
 * Remove GUI-managed env keys whose current values exact-match expectedEnv.
 * Restores anthropicBaseUrlBackup if ANTHROPIC_BASE_URL is absent.
 *
 * @param {unknown} settings
 * @param {Record<string, string>} expectedEnv
 * @param {{ anthropicBaseUrlBackup?: string | null }} [meta]
 * @returns {{
 *   nextSettings: object,
 *   skipped: string[],
 *   anthropicBaseUrlBackup: string | null | undefined,
 * }}
 */
export function removeClaudeEnv(settings, expectedEnv, meta = {}) {
  assertSettingsShape(settings);
  if (expectedEnv !== null && typeof expectedEnv !== "object") {
    throw new Error("expectedEnv must be a plain object");
  }
  const env = { ...(settings.env ?? {}) };
  const skipped = [];
  let anthropicBaseUrlBackup = meta.anthropicBaseUrlBackup ?? null;

  // Remove exact-match managed keys (except NO_PROXY which is partial).
  for (const [key, expectedVal] of Object.entries(expectedEnv ?? {})) {
    if (key === "NO_PROXY" || key === "no_proxy") {
      continue; // handled below via stripLocalhostNoProxy
    }
    if (!(key in env)) continue;
    if (env[key] === expectedVal) {
      delete env[key];
    } else {
      skipped.push(key);
    }
  }

  // Partial ownership of NO_PROXY: strip only the three localhost hosts.
  for (const key of ["NO_PROXY", "no_proxy"]) {
    if (key in env) {
      const stripped = stripLocalhostNoProxy(env[key]);
      if (stripped === undefined) delete env[key];
      else env[key] = stripped;
    }
  }

  // Restore ANTHROPIC_BASE_URL backup if key currently absent.
  if (
    anthropicBaseUrlBackup != null &&
    anthropicBaseUrlBackup !== "" &&
    !("ANTHROPIC_BASE_URL" in env)
  ) {
    env.ANTHROPIC_BASE_URL = anthropicBaseUrlBackup;
    anthropicBaseUrlBackup = null; // consumed
  }

  return {
    nextSettings: { ...settings, env },
    skipped,
    anthropicBaseUrlBackup,
  };
}

// ── internals ──────────────────────────────────────────────────────────────

/**
 * @param {string | undefined | null} existing
 * @returns {string[]}
 */
function splitNoProxy(existing) {
  if (existing === undefined || existing === null || existing === "") return [];
  return String(existing)
    .split(",")
    .map((s) => s.trim())
    .filter((s) => s.length > 0)
    .filter((s, i, arr) => arr.indexOf(s) === i); // de-dupe exact match, preserve order
}

/**
 * Build candidate forward keys to strip when applying reverse, if they match
 * the values we would have written for this port/ca.
 * @param {number | string} port
 * @param {string | undefined} caPemPath
 * @param {Record<string, string>} env
 * @returns {Record<string, string>}
 */
function buildForwardStripCandidates(port, caPemPath, env) {
  const portStr = validatePort(port ?? DEFAULT_PORT);
  const proxyUrl = `http://127.0.0.1:${portStr}`;
  const candidates = {
    HTTPS_PROXY: proxyUrl,
    https_proxy: proxyUrl,
  };
  if (typeof caPemPath === "string" && caPemPath.trim() !== "") {
    candidates.NODE_EXTRA_CA_CERTS = path.normalize(caPemPath);
  } else if (typeof env.NODE_EXTRA_CA_CERTS === "string") {
    // If we don't know ca path, still strip NODE_EXTRA_CA_CERTS only when
    // HTTPS_PROXY already matches our proxy URL (same session ownership).
    if (env.HTTPS_PROXY === proxyUrl || env.https_proxy === proxyUrl) {
      candidates.NODE_EXTRA_CA_CERTS = env.NODE_EXTRA_CA_CERTS;
    }
  }
  return candidates;
}

/**
 * Fail closed if settings is not a plain object, or env present but not object.
 * @param {unknown} settings
 */
function assertSettingsShape(settings) {
  if (settings === null || typeof settings !== "object" || Array.isArray(settings)) {
    throw new Error("settings must be a plain object");
  }
  if ("env" in settings && settings.env != null) {
    if (typeof settings.env !== "object" || Array.isArray(settings.env)) {
      throw new Error("settings.env must be a plain object when present");
    }
  }
}
