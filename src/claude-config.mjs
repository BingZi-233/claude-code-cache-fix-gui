/**
 * Claude Code config root + settings path resolution.
 * Pure: no filesystem I/O.
 *
 * @see docs/design/2026-07-22-gui-design.md §5.1
 */
import path from "node:path";

/**
 * Resolve Claude Code config directory.
 *
 * Precedence:
 * 1. non-empty configDirOverride
 * 2. non-empty env.CLAUDE_CONFIG_DIR
 * 3. join(homedir, ".claude")
 *
 * Empty string `""` for CLAUDE_CONFIG_DIR is treated as unset.
 *
 * @param {Record<string, string | undefined>} env
 * @param {string} homedir
 * @param {string} [configDirOverride]
 * @returns {string}
 */
export function resolveClaudeConfigDir(env, homedir, configDirOverride) {
  if (typeof configDirOverride === "string" && configDirOverride.trim() !== "") {
    return path.normalize(configDirOverride);
  }
  const fromEnv = env?.CLAUDE_CONFIG_DIR;
  if (typeof fromEnv === "string" && fromEnv.trim() !== "") {
    return path.normalize(fromEnv);
  }
  return path.join(homedir, ".claude");
}

/**
 * Path to Claude Code global settings.json.
 *
 * @param {Record<string, string | undefined>} env
 * @param {string} homedir
 * @param {string} [configDirOverride]
 * @returns {string}
 */
export function settingsPath(env, homedir, configDirOverride) {
  return path.join(
    resolveClaudeConfigDir(env, homedir, configDirOverride),
    "settings.json",
  );
}
