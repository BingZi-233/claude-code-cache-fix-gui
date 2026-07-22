/**
 * Filesystem I/O for Claude settings.json (atomic write + .bak).
 * Uses pure apply/remove from settings-env.mjs.
 */
import { readFile, writeFile, rename, copyFile, mkdir, access } from "node:fs/promises";
import { constants as fsConstants } from "node:fs";
import { dirname, join } from "node:path";
import { homedir } from "node:os";
import { resolveClaudeConfigDir, settingsPath } from "./claude-config.mjs";
import { applyClaudeEnv, removeClaudeEnv, DEFAULT_PORT } from "./settings-env.mjs";

/**
 * @param {Record<string, string | undefined>} [env]
 * @param {string} [home]
 * @param {string} [configDirOverride]
 */
export function resolvePaths(env = process.env, home = homedir(), configDirOverride) {
  const configRoot = resolveClaudeConfigDir(env, home, configDirOverride);
  return {
    configRoot,
    settingsFile: settingsPath(env, home, configDirOverride),
    caDir: join(configRoot, "cache-fix-ca"),
    caPem: join(configRoot, "cache-fix-ca", "ca.pem"),
  };
}

/**
 * Load settings.json; missing file → {}.
 * Fail closed if top-level is not a plain object.
 * @param {string} filePath
 */
export async function loadSettings(filePath) {
  try {
    await access(filePath, fsConstants.F_OK);
  } catch {
    return {};
  }
  const raw = await readFile(filePath, "utf8");
  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch (err) {
    throw new Error(`settings.json is not valid JSON: ${err.message}`);
  }
  if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new Error("settings.json top-level must be a JSON object (fail closed)");
  }
  if ("env" in parsed && parsed.env != null) {
    if (typeof parsed.env !== "object" || Array.isArray(parsed.env)) {
      throw new Error("settings.json env must be a plain object (fail closed)");
    }
  }
  return parsed;
}

/**
 * Atomic write with optional .bak of previous file.
 * @param {string} filePath
 * @param {object} settings
 * @param {{ writeBackup?: boolean }} [opts]
 */
export async function saveSettings(filePath, settings, opts = {}) {
  const { writeBackup = true } = opts;
  await mkdir(dirname(filePath), { recursive: true });

  let existed = false;
  try {
    await access(filePath, fsConstants.F_OK);
    existed = true;
  } catch {
    existed = false;
  }

  if (writeBackup && existed) {
    await copyFile(filePath, `${filePath}.bak`);
  }

  const body = `${JSON.stringify(settings, null, 2)}\n`;
  const tmp = `${filePath}.${process.pid}.${Date.now()}.tmp`;
  await writeFile(tmp, body, "utf8");
  await rename(tmp, filePath);
}

/**
 * Wire Claude global settings for reverse or forward mode.
 *
 * @param {{
 *   mode: "reverse" | "forward",
 *   port?: number | string,
 *   configDirOverride?: string,
 *   env?: Record<string, string | undefined>,
 *   anthropicBaseUrlBackup?: string | null,
 * }} opts
 */
export async function wireClaudeSettings(opts) {
  const {
    mode,
    port = DEFAULT_PORT,
    configDirOverride,
    env = process.env,
    anthropicBaseUrlBackup = null,
  } = opts;

  const paths = resolvePaths(env, homedir(), configDirOverride);
  const settings = await loadSettings(paths.settingsFile);

  if (mode === "forward") {
    try {
      await access(paths.caPem, fsConstants.F_OK);
    } catch {
      throw new Error(
        `Forward mode requires CA at ${paths.caPem}. Start the proxy in forward mode first so it can generate the CA.`,
      );
    }
  }

  const result = applyClaudeEnv(
    settings,
    { mode, port, caPemPath: paths.caPem },
    { anthropicBaseUrlBackup },
  );
  await saveSettings(paths.settingsFile, result.nextSettings);
  return {
    paths,
    expectedEnv: result.expectedEnv,
    anthropicBaseUrlBackup: result.anthropicBaseUrlBackup,
    settingsFile: paths.settingsFile,
  };
}

/**
 * Unwire GUI-managed env keys (value-match).
 *
 * @param {{
 *   expectedEnv: Record<string, string>,
 *   configDirOverride?: string,
 *   env?: Record<string, string | undefined>,
 *   anthropicBaseUrlBackup?: string | null,
 * }} opts
 */
export async function unwireClaudeSettings(opts) {
  const {
    expectedEnv,
    configDirOverride,
    env = process.env,
    anthropicBaseUrlBackup = null,
  } = opts;

  const paths = resolvePaths(env, homedir(), configDirOverride);
  const settings = await loadSettings(paths.settingsFile);
  const result = removeClaudeEnv(settings, expectedEnv, { anthropicBaseUrlBackup });
  await saveSettings(paths.settingsFile, result.nextSettings);
  return {
    paths,
    skipped: result.skipped,
    anthropicBaseUrlBackup: result.anthropicBaseUrlBackup,
    settingsFile: paths.settingsFile,
  };
}
