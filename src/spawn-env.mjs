/**
 * Build environment for spawning cache-fix-proxy.
 * Pure: no process spawn.
 *
 * @see docs/design/2026-07-22-gui-design.md §6.1
 */
import path from "node:path";
import { validatePort, DEFAULT_PORT } from "./settings-env.mjs";

/**
 * Build child process env for the proxy.
 *
 * Always sets:
 * - CACHE_FIX_PROXY_PORT
 * - CLAUDE_CONFIG_DIR  (effective config root)
 * - CACHE_FIX_CA_DIR   (both modes — CA/state never desync)
 *
 * Sets CACHE_FIX_FORWARD_PROXY=on only when mode === "forward".
 *
 * @param {{
 *   port?: number | string,
 *   mode?: "reverse" | "forward",
 *   effectiveConfigRoot: string,
 *   caDir?: string,
 *   baseEnv?: Record<string, string | undefined>,
 * }} opts
 * @returns {Record<string, string>}
 */
export function buildProxySpawnEnv({
  port = DEFAULT_PORT,
  mode = "reverse",
  effectiveConfigRoot,
  caDir,
  baseEnv = {},
}) {
  if (typeof effectiveConfigRoot !== "string" || effectiveConfigRoot.trim() === "") {
    throw new Error("effectiveConfigRoot is required");
  }

  const portStr = validatePort(port);
  const resolvedCaDir =
    typeof caDir === "string" && caDir.trim() !== ""
      ? path.normalize(caDir)
      : path.join(effectiveConfigRoot, "cache-fix-ca");

  /** @type {Record<string, string>} */
  const env = {};
  // Copy string values from baseEnv (skip undefined).
  for (const [k, v] of Object.entries(baseEnv)) {
    if (typeof v === "string") env[k] = v;
  }

  env.CACHE_FIX_PROXY_PORT = portStr;
  env.CLAUDE_CONFIG_DIR = path.normalize(effectiveConfigRoot);
  env.CACHE_FIX_CA_DIR = resolvedCaDir;

  if (mode === "forward") {
    env.CACHE_FIX_FORWARD_PROXY = "on";
  } else {
    // Ensure reverse spawn does not inherit a stale forward flag from baseEnv.
    delete env.CACHE_FIX_FORWARD_PROXY;
  }

  return env;
}
