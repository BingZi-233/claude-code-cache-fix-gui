/**
 * Proxy lifecycle + Claude wire state machine (I/O).
 * @see docs/design/2026-07-22-gui-design.md §4.3
 */
import { spawn } from "node:child_process";
import { createWriteStream, existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import http from "node:http";
import { parseCacheFixHealth } from "./health.mjs";
import { buildProxySpawnEnv } from "./spawn-env.mjs";
import { DEFAULT_PORT } from "./settings-env.mjs";
import { resolvePaths, wireClaudeSettings, unwireClaudeSettings, loadSettings } from "./settings-io.mjs";
import { resolveProxyLaunch } from "./proxy-resolve.mjs";
import { COMPATIBLE_RANGE } from "./proxy-discover.mjs";

const APP_DIR = join(homedir(), ".cache-fix-gui");
const STATE_FILE = join(APP_DIR, "state.json");
const LOG_FILE = join(APP_DIR, "proxy.log");

/**
 * @typedef {{
 *   port: number,
 *   mode: "reverse" | "forward",
 *   configDirOverride?: string | null,
 *   explicitProxyPath?: string | null,
 *   expectedEnv?: Record<string, string> | null,
 *   anthropicBaseUrlBackup?: string | null,
 *   claudeWired: boolean,
 *   quitStopsProxy: boolean,
 *   proxyEnv?: Record<string, string>,
 * }} AppState
 */

/** @type {import("node:child_process").ChildProcess | null} */
let child = null;
/** @type {"Discovering"|"Stopped"|"Starting"|"Running"|"Degraded"|"Error"|"Attached"} */
let phase = "Stopped";
/** @type {string} */
let lastError = "";
/** @type {Awaited<ReturnType<typeof resolveProxyLaunch>>} */
let launchInfo = null;
/** @type {boolean} */
let managedChild = false;
/** @type {ReturnType<typeof setInterval> | null} */
let healthTimer = null;
/** @type {string[]} */
const logBuffer = [];
const MAX_LOG = 500;

function ensureAppDir() {
  if (!existsSync(APP_DIR)) mkdirSync(APP_DIR, { recursive: true });
}

/**
 * @returns {AppState}
 */
export function loadAppState() {
  ensureAppDir();
  const defaults = {
    port: DEFAULT_PORT,
    mode: /** @type {"reverse"} */ ("reverse"),
    configDirOverride: null,
    explicitProxyPath: null,
    expectedEnv: null,
    anthropicBaseUrlBackup: null,
    claudeWired: false,
    quitStopsProxy: true,
    /** @type {Record<string, string>} */
    proxyEnv: {},
  };
  try {
    const raw = readFileSync(STATE_FILE, "utf8");
    return { ...defaults, ...JSON.parse(raw) };
  } catch {
    return { ...defaults };
  }
}

/**
 * @param {Partial<AppState>} patch
 * @returns {AppState}
 */
export function saveAppState(patch) {
  const next = { ...loadAppState(), ...patch };
  ensureAppDir();
  writeFileSync(STATE_FILE, `${JSON.stringify(next, null, 2)}\n`, "utf8");
  return next;
}

function appendLog(line) {
  const ts = new Date().toISOString();
  const row = `[${ts}] ${line}`;
  logBuffer.push(row);
  while (logBuffer.length > MAX_LOG) logBuffer.shift();
  try {
    ensureAppDir();
    createWriteStream(LOG_FILE, { flags: "a" }).end(`${row}\n`);
  } catch {
    /* ignore log disk errors */
  }
}

/**
 * @param {number} port
 * @returns {Promise<{ httpStatus: number | null, body: string | null }>}
 */
export function fetchHealth(port) {
  return new Promise((resolve) => {
    const req = http.get(
      {
        host: "127.0.0.1",
        port,
        path: "/health",
        timeout: 2000,
      },
      (res) => {
        let body = "";
        res.setEncoding("utf8");
        res.on("data", (c) => {
          body += c;
        });
        res.on("end", () => {
          resolve({ httpStatus: res.statusCode ?? null, body });
        });
      },
    );
    req.on("error", () => resolve({ httpStatus: null, body: null }));
    req.on("timeout", () => {
      req.destroy();
      resolve({ httpStatus: null, body: null });
    });
  });
}

/**
 * @param {number} [port]
 */
export async function probeHealth(port = loadAppState().port) {
  const { httpStatus, body } = await fetchHealth(port);
  return parseCacheFixHealth(httpStatus, body);
}

/**
 * Snapshot for UI / tray.
 */
export async function getStatus() {
  const state = loadAppState();
  const health = await probeHealth(state.port);
  if (health.kind === "ok") {
    phase = managedChild ? "Running" : "Attached";
    lastError = "";
  } else if (health.kind === "degraded") {
    phase = "Degraded";
  } else if (phase === "Starting") {
    // keep Starting until timeout handled by startProxy
  } else if (!managedChild || !child || child.killed || child.exitCode != null) {
    if (phase !== "Error" || health.kind === "unreachable") {
      if (health.kind === "foreign") {
        phase = "Error";
        lastError = `Port ${state.port} has a non-cache-fix listener`;
      } else if (!managedChild) {
        phase = "Stopped";
      }
    }
  }

  const paths = resolvePaths(
    process.env,
    homedir(),
    state.configDirOverride || undefined,
  );

  return {
    phase,
    lastError,
    port: state.port,
    mode: state.mode,
    claudeWired: state.claudeWired,
    quitStopsProxy: state.quitStopsProxy,
    proxyEnv: state.proxyEnv || {},
    managedChild,
    pid: child?.pid ?? null,
    health,
    launch: launchInfo,
    compatibleRange: COMPATIBLE_RANGE,
    paths: {
      configRoot: paths.configRoot,
      settingsFile: paths.settingsFile,
      caPem: paths.caPem,
      appState: STATE_FILE,
      logFile: LOG_FILE,
    },
    logTail: logBuffer.slice(-80),
  };
}

/**
 * Discover proxy binary without starting.
 */
export async function discover() {
  phase = "Discovering";
  const state = loadAppState();
  launchInfo = await resolveProxyLaunch({
    explicitPath: state.explicitProxyPath || undefined,
  });
  phase = child && !child.killed ? phase : "Stopped";
  if (!launchInfo) {
    lastError =
      "No compatible cache-fix proxy found (PATH / npm global / sibling / sidecar). " +
      "Install: npm i -g claude-code-cache-fix@^4.3.0 — or use the portable zip (includes sidecar/) — " +
      "or set env CACHE_FIX_GUI_PROXY_ROOT / GUI explicitProxyPath to the package root.";
    phase = "Error";
  } else {
    lastError = "";
    appendLog(`Discovered proxy source=${launchInfo.source} version=${launchInfo.version || "?"} path=${launchInfo.path}`);
  }
  return launchInfo;
}

/**
 * Start proxy process (or attach if already healthy on port).
 */
export async function startProxy(overrides = {}) {
  const state = saveAppState(overrides);
  const port = state.port;
  const mode = state.mode;

  // Attach if already running cache-fix on port
  const existing = await probeHealth(port);
  if (existing.kind === "ok" || existing.kind === "degraded") {
    if (
      typeof existing.forwardProxy === "boolean" &&
      ((mode === "forward" && !existing.forwardProxy) ||
        (mode === "reverse" && existing.forwardProxy))
    ) {
      phase = "Error";
      lastError = `Port ${port} proxy mode mismatch (want ${mode}, health forward_proxy=${existing.forwardProxy}). Stop it or change mode.`;
      appendLog(lastError);
      throw new Error(lastError);
    }
    managedChild = false;
    phase = existing.kind === "degraded" ? "Degraded" : "Attached";
    appendLog(`Attached to existing proxy on :${port} (${existing.kind})`);
    startHealthLoop();
    return getStatus();
  }
  if (existing.kind === "foreign") {
    phase = "Error";
    lastError = `Port ${port} occupied by non-cache-fix service`;
    throw new Error(lastError);
  }

  const launch = await discover();
  if (!launch) throw new Error(lastError || "proxy not found");

  const paths = resolvePaths(
    process.env,
    homedir(),
    state.configDirOverride || undefined,
  );

  const env = buildProxySpawnEnv({
    port,
    mode,
    effectiveConfigRoot: paths.configRoot,
    caDir: paths.caDir,
    baseEnv: process.env,
    extraEnv: state.proxyEnv || {},
  });

  phase = "Starting";
  appendLog(`Starting ${launch.command} ${launch.args.join(" ")} mode=${mode} port=${port}`);

  ensureAppDir();
  const logStream = createWriteStream(LOG_FILE, { flags: "a" });

  child = spawn(launch.command, launch.args, {
    env,
    stdio: ["ignore", "pipe", "pipe"],
    windowsHide: true,
  });
  managedChild = true;

  child.stdout?.on("data", (buf) => {
    const s = buf.toString();
    for (const line of s.split(/\r?\n/).filter(Boolean)) appendLog(`[out] ${line}`);
    logStream.write(buf);
  });
  child.stderr?.on("data", (buf) => {
    const s = buf.toString();
    for (const line of s.split(/\r?\n/).filter(Boolean)) appendLog(`[err] ${line}`);
    logStream.write(buf);
  });
  child.on("exit", (code, signal) => {
    appendLog(`Proxy exited code=${code} signal=${signal}`);
    if (managedChild) {
      child = null;
      managedChild = false;
      if (phase !== "Stopped") {
        phase = "Stopped";
      }
    }
  });

  const deadline = Date.now() + 10000;
  while (Date.now() < deadline) {
    await sleep(200);
    const h = await probeHealth(port);
    if (h.kind === "ok") {
      phase = "Running";
      lastError = "";
      appendLog(`Proxy running on :${port}`);
      startHealthLoop();
      return getStatus();
    }
    if (h.kind === "degraded") {
      phase = "Degraded";
      lastError = typeof h.hint === "string" ? h.hint : "proxy degraded";
      startHealthLoop();
      return getStatus();
    }
    if (child && child.exitCode != null) {
      phase = "Error";
      lastError = `Proxy exited before ready (code ${child.exitCode})`;
      managedChild = false;
      child = null;
      throw new Error(lastError);
    }
  }

  // timeout
  await stopProxy({ force: true });
  phase = "Error";
  lastError = "Proxy failed to become healthy within 10s";
  throw new Error(lastError);
}

/**
 * Stop only GUI-spawned child. Detach if attached.
 */
export async function stopProxy(opts = {}) {
  const { force = false } = opts;
  stopHealthLoop();

  if (!managedChild || !child) {
    managedChild = false;
    child = null;
    phase = "Stopped";
    appendLog(force ? "Detached / stopped (no managed child)" : "Detached from attached proxy (not killed)");
    return getStatus();
  }

  const proc = child;
  appendLog(`Stopping managed proxy pid=${proc.pid}`);
  try {
    proc.kill("SIGTERM");
  } catch {
    /* ignore */
  }

  const deadline = Date.now() + 5000;
  while (Date.now() < deadline && proc.exitCode == null) {
    await sleep(100);
  }
  if (proc.exitCode == null) {
    try {
      proc.kill("SIGKILL");
    } catch {
      /* ignore */
    }
  }

  child = null;
  managedChild = false;
  phase = "Stopped";
  return getStatus();
}

export async function restartProxy() {
  await stopProxy();
  await sleep(300);
  return startProxy();
}

/**
 * Wire Claude settings using current app mode/port.
 */
export async function wireClaude() {
  const state = loadAppState();
  if (state.mode === "forward") {
    const h = await probeHealth(state.port);
    if (h.kind === "unreachable" || h.kind === "foreign") {
      throw new Error("Start proxy in forward mode before wiring Claude (need CA + health)");
    }
    if (h.forwardProxy === false) {
      throw new Error("Proxy health reports forward_proxy=false; cannot wire forward mode");
    }
  }

  const result = await wireClaudeSettings({
    mode: state.mode,
    port: state.port,
    configDirOverride: state.configDirOverride || undefined,
    anthropicBaseUrlBackup: state.anthropicBaseUrlBackup,
  });

  saveAppState({
    claudeWired: true,
    expectedEnv: result.expectedEnv,
    anthropicBaseUrlBackup: result.anthropicBaseUrlBackup ?? null,
  });
  appendLog(`Wired Claude settings at ${result.settingsFile} mode=${state.mode}`);
  return result;
}

/**
 * Unwire Claude settings.
 */
export async function unwireClaude() {
  const state = loadAppState();
  if (!state.expectedEnv) {
    // Best-effort reverse expected for cleanup
    const { computeExpectedEnv } = await import("./settings-env.mjs");
    const paths = resolvePaths(
      process.env,
      homedir(),
      state.configDirOverride || undefined,
    );
    const expectedEnv =
      state.mode === "forward"
        ? computeExpectedEnv({
            mode: "forward",
            port: state.port,
            caPemPath: paths.caPem,
          })
        : computeExpectedEnv({ mode: "reverse", port: state.port });
    const result = await unwireClaudeSettings({
      expectedEnv,
      configDirOverride: state.configDirOverride || undefined,
      anthropicBaseUrlBackup: state.anthropicBaseUrlBackup,
    });
    saveAppState({
      claudeWired: false,
      expectedEnv: null,
      anthropicBaseUrlBackup: result.anthropicBaseUrlBackup ?? null,
    });
    appendLog(`Unwired Claude (recomputed expected) skipped=${result.skipped.join(",") || "none"}`);
    return result;
  }

  const result = await unwireClaudeSettings({
    expectedEnv: state.expectedEnv,
    configDirOverride: state.configDirOverride || undefined,
    anthropicBaseUrlBackup: state.anthropicBaseUrlBackup,
  });
  saveAppState({
    claudeWired: false,
    expectedEnv: null,
    anthropicBaseUrlBackup: result.anthropicBaseUrlBackup ?? null,
  });
  appendLog(`Unwired Claude skipped=${result.skipped.join(",") || "none"}`);
  return result;
}

/**
 * Preview env that would be written (no I/O mutate beyond path resolve).
 */
export async function previewWireEnv() {
  const state = loadAppState();
  const paths = resolvePaths(
    process.env,
    homedir(),
    state.configDirOverride || undefined,
  );
  const { computeExpectedEnv } = await import("./settings-env.mjs");
  let existingNoProxy;
  try {
    const s = await loadSettings(paths.settingsFile);
    existingNoProxy = s.env?.NO_PROXY || s.env?.no_proxy;
  } catch {
    existingNoProxy = undefined;
  }
  if (state.mode === "forward") {
    return computeExpectedEnv({
      mode: "forward",
      port: state.port,
      caPemPath: paths.caPem,
      existingNoProxy,
    });
  }
  return computeExpectedEnv({ mode: "reverse", port: state.port });
}

export function getLogTail(n = 100) {
  return logBuffer.slice(-n);
}

export async function shutdown() {
  const state = loadAppState();
  stopHealthLoop();
  if (state.quitStopsProxy) {
    await stopProxy();
  }
}

function startHealthLoop() {
  stopHealthLoop();
  healthTimer = setInterval(async () => {
    try {
      const state = loadAppState();
      const h = await probeHealth(state.port);
      if (h.kind === "ok") phase = managedChild ? "Running" : "Attached";
      else if (h.kind === "degraded") phase = "Degraded";
      else if (managedChild && child) phase = "Degraded";
      else if (!managedChild) phase = h.kind === "unreachable" ? "Stopped" : "Error";
    } catch {
      /* ignore poll errors */
    }
  }, 5000);
  // Don't keep process alive solely for health if nothing else is running — panel server will.
  if (healthTimer.unref) healthTimer.unref();
}

function stopHealthLoop() {
  if (healthTimer) {
    clearInterval(healthTimer);
    healthTimer = null;
  }
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}
