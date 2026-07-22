/** Form state ↔ proxyEnv mapping (scheme B dedicated fields). */

export type FormState = {
  port: number;
  mode: "reverse" | "forward";
  upstream: string;
  bind: string;
  timeout: string;
  debug: boolean;
  httpsProxy: string;
  httpProxy: string;
  noProxy: string;
  caFile: string;
  rejectUnauthorized: boolean;
  caDir: string;
  downloadRewrite: boolean;
  oauthRefresh: boolean;
  bootstrapMode: string;
  thinkingDisplay: string;
  thinkingSanitize: string;
  imageGuard: boolean;
  sessionMirror: boolean;
  upstreamErrorLog: boolean;
  extraEnvText: string;
};

export const DEFAULT_FORM: FormState = {
  port: 9801,
  mode: "reverse",
  upstream: "https://api.anthropic.com",
  bind: "127.0.0.1",
  timeout: "600000",
  debug: false,
  httpsProxy: "",
  httpProxy: "",
  noProxy: "",
  caFile: "",
  rejectUnauthorized: true,
  caDir: "",
  downloadRewrite: false,
  oauthRefresh: false,
  bootstrapMode: "",
  thinkingDisplay: "",
  thinkingSanitize: "",
  imageGuard: false,
  sessionMirror: false,
  upstreamErrorLog: false,
  extraEnvText: "",
};

const DEDICATED_KEYS = new Set([
  "CACHE_FIX_PROXY_UPSTREAM",
  "CACHE_FIX_PROXY_BIND",
  "CACHE_FIX_PROXY_TIMEOUT",
  "CACHE_FIX_PROXY_CA_FILE",
  "HTTPS_PROXY",
  "HTTP_PROXY",
  "NO_PROXY",
  "https_proxy",
  "http_proxy",
  "no_proxy",
  "CACHE_FIX_CA_DIR",
  "CACHE_FIX_BOOTSTRAP_MODE",
  "CACHE_FIX_THINKING_DISPLAY",
  "CACHE_FIX_THINKING_SANITIZE",
  "CACHE_FIX_DEBUG",
  "CACHE_FIX_DOWNLOAD_REWRITE",
  "CACHE_FIX_OAUTH_REFRESH",
  "CACHE_FIX_IMAGE_GUARD",
  "CACHE_FIX_SESSION_MIRROR",
  "CACHE_FIX_UPSTREAM_ERROR_LOG",
  "CACHE_FIX_PROXY_REJECT_UNAUTHORIZED",
]);

export function formToProxyEnv(form: FormState): Record<string, string> {
  const env: Record<string, string> = {};
  const set = (k: string, v: string) => {
    const t = v.trim();
    if (t) env[k] = t;
  };

  set("CACHE_FIX_PROXY_UPSTREAM", form.upstream);
  set("CACHE_FIX_PROXY_BIND", form.bind);
  set("CACHE_FIX_PROXY_TIMEOUT", form.timeout);
  set("CACHE_FIX_PROXY_CA_FILE", form.caFile);
  set("HTTPS_PROXY", form.httpsProxy);
  set("HTTP_PROXY", form.httpProxy);
  set("NO_PROXY", form.noProxy);
  set("CACHE_FIX_CA_DIR", form.caDir);
  set("CACHE_FIX_BOOTSTRAP_MODE", form.bootstrapMode);
  set("CACHE_FIX_THINKING_DISPLAY", form.thinkingDisplay);
  set("CACHE_FIX_THINKING_SANITIZE", form.thinkingSanitize);

  if (env.HTTPS_PROXY) env.https_proxy = env.HTTPS_PROXY;
  if (env.HTTP_PROXY) env.http_proxy = env.HTTP_PROXY;
  if (env.NO_PROXY) env.no_proxy = env.NO_PROXY;

  if (form.debug) env.CACHE_FIX_DEBUG = "1";
  if (form.downloadRewrite) env.CACHE_FIX_DOWNLOAD_REWRITE = "on";
  if (form.oauthRefresh) env.CACHE_FIX_OAUTH_REFRESH = "on";
  if (form.imageGuard) env.CACHE_FIX_IMAGE_GUARD = "1";
  if (form.sessionMirror) env.CACHE_FIX_SESSION_MIRROR = "on";
  if (form.upstreamErrorLog) env.CACHE_FIX_UPSTREAM_ERROR_LOG = "on";
  if (!form.rejectUnauthorized) env.CACHE_FIX_PROXY_REJECT_UNAUTHORIZED = "0";

  if (form.extraEnvText.trim()) {
    for (const line of form.extraEnvText.split(/\r?\n/)) {
      const trimmed = line.trim();
      if (!trimmed || trimmed.startsWith("#")) continue;
      const eq = trimmed.indexOf("=");
      if (eq <= 0) continue;
      const k = trimmed.slice(0, eq).trim();
      const v = trimmed.slice(eq + 1).trim();
      if (!k || DEDICATED_KEYS.has(k)) continue;
      if (v) env[k] = v;
    }
  }

  return env;
}

export function proxyEnvToFormPatch(
  pe: Record<string, string> | undefined,
): Partial<FormState> {
  if (!pe || typeof pe !== "object") return {};
  const on = (v: string | undefined) =>
    v === "on" || v === "1" || v === "true";

  const lines: string[] = [];
  for (const [k, v] of Object.entries(pe)) {
    if (DEDICATED_KEYS.has(k)) continue;
    if (v == null || v === "") continue;
    lines.push(`${k}=${v}`);
  }

  const rawRej = pe.CACHE_FIX_PROXY_REJECT_UNAUTHORIZED;
  return {
    upstream: pe.CACHE_FIX_PROXY_UPSTREAM ?? DEFAULT_FORM.upstream,
    bind: pe.CACHE_FIX_PROXY_BIND ?? DEFAULT_FORM.bind,
    timeout: pe.CACHE_FIX_PROXY_TIMEOUT ?? DEFAULT_FORM.timeout,
    caFile: pe.CACHE_FIX_PROXY_CA_FILE ?? "",
    httpsProxy: pe.HTTPS_PROXY || pe.https_proxy || "",
    httpProxy: pe.HTTP_PROXY || pe.http_proxy || "",
    noProxy: pe.NO_PROXY || pe.no_proxy || "",
    caDir: pe.CACHE_FIX_CA_DIR ?? "",
    bootstrapMode: pe.CACHE_FIX_BOOTSTRAP_MODE ?? "",
    thinkingDisplay: pe.CACHE_FIX_THINKING_DISPLAY ?? "",
    thinkingSanitize: pe.CACHE_FIX_THINKING_SANITIZE ?? "",
    debug: on(pe.CACHE_FIX_DEBUG),
    downloadRewrite: on(pe.CACHE_FIX_DOWNLOAD_REWRITE),
    oauthRefresh: on(pe.CACHE_FIX_OAUTH_REFRESH),
    imageGuard: on(pe.CACHE_FIX_IMAGE_GUARD),
    sessionMirror: on(pe.CACHE_FIX_SESSION_MIRROR),
    upstreamErrorLog: on(pe.CACHE_FIX_UPSTREAM_ERROR_LOG),
    rejectUnauthorized: !(rawRej === "0" || rawRej === "false"),
    extraEnvText: lines.join("\n"),
  };
}
