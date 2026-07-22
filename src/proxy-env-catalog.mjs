/**
 * Known CACHE_FIX_* / related env keys for documentation + UI grouping.
 * Scheme B uses dedicated controls for common keys; advanced textarea covers the rest.
 */

/**
 * @typedef {{
 *   group: "core" | "enterprise" | "forward" | "feature" | "advanced",
 *   type: "url" | "port" | "bool" | "enum" | "path" | "string" | "int",
 *   label: string,
 *   default?: string | number | boolean,
 *   help?: string,
 *   enumValues?: string[],
 *   boolOn?: string,
 * }} CatalogEntry
 */

/** @type {Record<string, CatalogEntry>} */
export const CONFIG_CATALOG = {
  // ── core ─────────────────────────────────────────────────────────────
  CACHE_FIX_PROXY_UPSTREAM: {
    group: "core",
    type: "url",
    label: "Upstream",
    default: "https://api.anthropic.com",
    help: "上游 API 地址；链式中继时改为 http://127.0.0.1:8080 等",
  },
  CACHE_FIX_PROXY_PORT: {
    group: "core",
    type: "port",
    label: "端口",
    default: 9801,
  },
  CACHE_FIX_PROXY_BIND: {
    group: "core",
    type: "string",
    label: "绑定地址",
    default: "127.0.0.1",
  },
  CACHE_FIX_PROXY_TIMEOUT: {
    group: "core",
    type: "int",
    label: "超时 (ms)",
    default: 600000,
  },
  CACHE_FIX_DEBUG: {
    group: "core",
    type: "bool",
    label: "调试日志",
    default: false,
    boolOn: "1",
  },

  // ── enterprise ───────────────────────────────────────────────────────
  HTTPS_PROXY: {
    group: "enterprise",
    type: "url",
    label: "HTTPS_PROXY",
    help: "企业 HTTP CONNECT 代理",
  },
  HTTP_PROXY: {
    group: "enterprise",
    type: "url",
    label: "HTTP_PROXY",
  },
  NO_PROXY: {
    group: "enterprise",
    type: "string",
    label: "NO_PROXY",
  },
  CACHE_FIX_PROXY_CA_FILE: {
    group: "enterprise",
    type: "path",
    label: "企业 CA 文件",
  },
  CACHE_FIX_PROXY_REJECT_UNAUTHORIZED: {
    group: "enterprise",
    type: "bool",
    label: "拒绝未授权证书",
    default: true,
    boolOn: "1",
    help: "设为 0 会禁用 TLS 验证（不安全）",
  },

  // ── forward ──────────────────────────────────────────────────────────
  CACHE_FIX_FORWARD_PROXY: {
    group: "forward",
    type: "bool",
    label: "Forward 模式",
    default: false,
    boolOn: "on",
    help: "由 GUI 模式选择控制，勿在高级区手动覆盖",
  },
  CACHE_FIX_CA_DIR: {
    group: "forward",
    type: "path",
    label: "CA 目录",
  },
  CACHE_FIX_DOWNLOAD_REWRITE: {
    group: "forward",
    type: "bool",
    label: "下载加速重写",
    default: false,
    boolOn: "on",
  },
  CACHE_FIX_OAUTH_REFRESH: {
    group: "forward",
    type: "bool",
    label: "OAuth 刷新",
    default: false,
    boolOn: "on",
  },

  // ── feature ──────────────────────────────────────────────────────────
  CACHE_FIX_BOOTSTRAP_MODE: {
    group: "feature",
    type: "enum",
    label: "Bootstrap 模式",
    default: "audit",
    enumValues: ["audit", "block", "allowlist"],
  },
  CACHE_FIX_THINKING_DISPLAY: {
    group: "feature",
    type: "enum",
    label: "Thinking Display",
    default: "summarized",
    enumValues: ["summarized", "omitted", "disabled"],
  },
  CACHE_FIX_THINKING_SANITIZE: {
    group: "feature",
    type: "enum",
    label: "Thinking Sanitize",
    default: "on",
    enumValues: ["on", "off", "v2"],
  },
  CACHE_FIX_IMAGE_GUARD: {
    group: "feature",
    type: "bool",
    label: "Image Guard",
    default: false,
    boolOn: "1",
  },
  CACHE_FIX_SESSION_MIRROR: {
    group: "feature",
    type: "bool",
    label: "Session Mirror",
    default: false,
    boolOn: "on",
  },
  CACHE_FIX_UPSTREAM_ERROR_LOG: {
    group: "feature",
    type: "bool",
    label: "Upstream Error Log",
    default: false,
    boolOn: "on",
  },
  CACHE_FIX_HOT_RELOAD: {
    group: "feature",
    type: "bool",
    label: "扩展热重载",
    default: false,
    boolOn: "on",
  },
  CACHE_FIX_REQUEST_LOG: {
    group: "advanced",
    type: "path",
    label: "请求日志路径",
  },
  CACHE_FIX_EXTENSIONS_DIR: {
    group: "advanced",
    type: "path",
    label: "扩展目录",
  },
  CACHE_FIX_EXTENSIONS_CONFIG: {
    group: "advanced",
    type: "path",
    label: "扩展配置文件",
  },
};

/**
 * @returns {Record<string, CatalogEntry>}
 */
export function getConfigCatalog() {
  return CONFIG_CATALOG;
}

/**
 * Keys that have first-class UI controls (not only advanced textarea).
 * @returns {string[]}
 */
export function dedicatedConfigKeys() {
  return Object.entries(CONFIG_CATALOG)
    .filter(([, e]) => e.group !== "advanced")
    .map(([k]) => k);
}

export default getConfigCatalog;
