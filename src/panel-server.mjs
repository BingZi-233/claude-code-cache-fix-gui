/**
 * Local control-panel HTTP server (127.0.0.1 only).
 * Serves static UI and JSON API for tray/panel clients (including future Tauri webview).
 */
import http from "node:http";
import { readFile } from "node:fs/promises";
import { join, dirname, extname } from "node:path";
import { fileURLToPath } from "node:url";
import * as controller from "./controller.mjs";

const __dirname = dirname(fileURLToPath(import.meta.url));
const UI_DIR = join(__dirname, "..", "ui");

const MIME = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".svg": "image/svg+xml",
  ".json": "application/json",
  ".ico": "image/x-icon",
};

/**
 * @param {{ port?: number, host?: string }} [opts]
 * @returns {Promise<{ server: import("node:http").Server, port: number, url: string }>}
 */
export function startPanelServer(opts = {}) {
  const host = opts.host || "127.0.0.1";
  const preferredPort = opts.port ?? 19801;

  const server = http.createServer(async (req, res) => {
    try {
      // CORS for Tauri webview (asset/tauri origin → 127.0.0.1 API)
      applyCors(req, res);
      if (req.method === "OPTIONS") {
        res.writeHead(204);
        res.end();
        return;
      }
      await handle(req, res);
    } catch (err) {
      json(res, 500, { error: err.message || String(err) });
    }
  });

  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(preferredPort, host, () => {
      const addr = server.address();
      const port = typeof addr === "object" && addr ? addr.port : preferredPort;
      resolve({
        server,
        port,
        url: `http://${host}:${port}/`,
      });
    });
  });
}

/**
 * @param {import("node:http").IncomingMessage} req
 * @param {import("node:http").ServerResponse} res
 */
async function handle(req, res) {
  const url = new URL(req.url || "/", "http://127.0.0.1");
  const path = url.pathname;

  if (path.startsWith("/api/")) {
    return handleApi(req, res, path);
  }

  let filePath = path === "/" ? join(UI_DIR, "index.html") : join(UI_DIR, path.replace(/^\//, ""));
  // Prevent path escape
  if (!filePath.startsWith(UI_DIR)) {
    res.writeHead(403);
    res.end("forbidden");
    return;
  }

  try {
    const data = await readFile(filePath);
    const ext = extname(filePath);
    res.writeHead(200, { "Content-Type": MIME[ext] || "application/octet-stream" });
    res.end(data);
  } catch {
    if (path === "/" || path === "/index.html") {
      res.writeHead(500, { "Content-Type": "text/plain" });
      res.end("UI not found — missing ui/index.html");
      return;
    }
    res.writeHead(404);
    res.end("not found");
  }
}

/**
 * @param {import("node:http").IncomingMessage} req
 * @param {import("node:http").ServerResponse} res
 * @param {string} path
 */
async function handleApi(req, res, path) {
  const method = req.method || "GET";

  if (path === "/api/status" && method === "GET") {
    return json(res, 200, await controller.getStatus());
  }
  if (path === "/api/config" && method === "GET") {
    const state = controller.loadAppState();
    return json(res, 200, {
      proxyEnv: state.proxyEnv || {},
      port: state.port,
      mode: state.mode,
      configDirOverride: state.configDirOverride,
    });
  }
  if (path === "/api/preview-env" && method === "GET") {
    return json(res, 200, { env: await controller.previewWireEnv() });
  }
  if (path === "/api/logs" && method === "GET") {
    return json(res, 200, { lines: controller.getLogTail(200) });
  }
  if (path === "/api/discover" && method === "POST") {
    const launch = await controller.discover();
    return json(res, 200, { launch, status: await controller.getStatus() });
  }
  if (path === "/api/start" && method === "POST") {
    const body = await readJson(req);
    const status = await controller.startProxy(body || {});
    return json(res, 200, status);
  }
  if (path === "/api/stop" && method === "POST") {
    const status = await controller.stopProxy();
    return json(res, 200, status);
  }
  if (path === "/api/restart" && method === "POST") {
    const status = await controller.restartProxy();
    return json(res, 200, status);
  }
  if (path === "/api/wire" && method === "POST") {
    const result = await controller.wireClaude();
    return json(res, 200, { result, status: await controller.getStatus() });
  }
  if (path === "/api/unwire" && method === "POST") {
    const result = await controller.unwireClaude();
    return json(res, 200, { result, status: await controller.getStatus() });
  }
  if (path === "/api/config" && method === "POST") {
    const body = await readJson(req);
    const allowed = {};
    if (body.port != null) allowed.port = Number(body.port);
    if (body.mode === "reverse" || body.mode === "forward") allowed.mode = body.mode;
    if ("configDirOverride" in body) allowed.configDirOverride = body.configDirOverride;
    if ("explicitProxyPath" in body) allowed.explicitProxyPath = body.explicitProxyPath;
    if (typeof body.quitStopsProxy === "boolean") allowed.quitStopsProxy = body.quitStopsProxy;
    if (body.proxyEnv && typeof body.proxyEnv === "object") {
      const cleaned = {};
      for (const [k, v] of Object.entries(body.proxyEnv)) {
        if (v === undefined || v === null || v === "") continue;
        cleaned[k] = String(v);
      }
      allowed.proxyEnv = cleaned;
    }
    const state = controller.saveAppState(allowed);
    return json(res, 200, { state, status: await controller.getStatus() });
  }
  if (path === "/api/shutdown" && method === "POST") {
    await controller.shutdown();
    json(res, 200, { ok: true });
    setTimeout(() => process.exit(0), 100);
    return;
  }

  json(res, 404, { error: "unknown api route" });
}

/**
 * Allow local / Tauri webview origins only (panel is 127.0.0.1-only).
 * Without this, Tauri asset:// or tauri:// pages get "Failed to fetch" on /api.
 */
function applyCors(req, res) {
  const origin = req.headers.origin;
  if (!origin || isAllowedOrigin(origin)) {
    res.setHeader("Access-Control-Allow-Origin", origin || "*");
    res.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    res.setHeader("Access-Control-Allow-Headers", "Content-Type");
    res.setHeader("Access-Control-Max-Age", "600");
    // Vary so caches don't mix origins
    res.setHeader("Vary", "Origin");
  }
}

function isAllowedOrigin(origin) {
  try {
    if (origin === "null") return true;
    const u = new URL(origin);
    const host = (u.hostname || "").toLowerCase();
    if (host === "127.0.0.1" || host === "localhost" || host === "::1") return true;
    if (host === "tauri.localhost" || host.endsWith(".localhost")) return true;
    if (u.protocol === "tauri:" || u.protocol === "asset:" || u.protocol === "ipc:") return true;
    return false;
  } catch {
    return /^(tauri|asset|ipc):/i.test(origin);
  }
}

function json(res, code, obj) {
  const body = JSON.stringify(obj, null, 2);
  // CORS already applied on the response in createServer; set content headers only.
  res.writeHead(code, {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
  });
  res.end(body);
}

/**
 * @param {import("node:http").IncomingMessage} req
 */
function readJson(req) {
  return new Promise((resolve, reject) => {
    let raw = "";
    req.setEncoding("utf8");
    req.on("data", (c) => {
      raw += c;
      if (raw.length > 1_000_000) {
        reject(new Error("body too large"));
        req.destroy();
      }
    });
    req.on("end", () => {
      if (!raw.trim()) return resolve({});
      try {
        resolve(JSON.parse(raw));
      } catch (err) {
        reject(new Error(`invalid JSON body: ${err.message}`));
      }
    });
    req.on("error", reject);
  });
}
