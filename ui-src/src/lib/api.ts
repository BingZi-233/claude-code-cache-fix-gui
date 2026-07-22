/** Local panel API client — same contract as legacy ui/app.js */

const API_BASE = (() => {
  try {
    const { protocol, hostname, port } = window.location;
    if (
      (protocol === "http:" || protocol === "https:") &&
      (hostname === "127.0.0.1" || hostname === "localhost") &&
      (port === "19801" || port === "5173" || port === "")
    ) {
      return "";
    }
  } catch {
    /* ignore */
  }
  return "http://127.0.0.1:19801";
})();

export function apiHint(): string {
  return (
    "无法连接控制面板 API (http://127.0.0.1:19801)。\n" +
    "请先在本机启动: npm start  或  node bin/cache-fix-gui.mjs panel\n" +
    "（便携版请双击 启动.bat）"
  );
}

export async function api<T = unknown>(
  path: string,
  opts: RequestInit = {},
): Promise<T> {
  const url = path.startsWith("http") ? path : `${API_BASE}${path}`;
  let res: Response;
  try {
    res = await fetch(url, {
      headers: {
        "Content-Type": "application/json",
        ...(opts.headers || {}),
      },
      ...opts,
    });
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    if (/failed to fetch|networkerror|load failed|connection refused/i.test(msg)) {
      throw new Error(apiHint());
    }
    throw new Error(`${msg}\n\n${apiHint()}`);
  }

  const text = await res.text();
  let data: Record<string, unknown> = {};
  try {
    data = text ? (JSON.parse(text) as Record<string, unknown>) : {};
  } catch {
    throw new Error(text || res.statusText);
  }
  if (!res.ok) {
    throw new Error(
      typeof data.error === "string" ? data.error : res.statusText || "request failed",
    );
  }
  return data as T;
}

export type ProxyMode = "reverse" | "forward";

export type Status = {
  phase: string;
  lastError?: string;
  port: number;
  mode: ProxyMode;
  claudeWired: boolean;
  quitStopsProxy?: boolean;
  managedChild?: boolean;
  pid?: number | null;
  proxyEnv?: Record<string, string>;
  health?: {
    kind?: string;
    version?: string;
    forwardProxy?: boolean;
    hint?: string;
  };
  launch?: {
    source?: string;
    version?: string;
    path?: string;
  } | null;
  compatibleRange?: string;
  paths?: {
    configRoot?: string;
    settingsFile?: string;
    caPem?: string;
    logFile?: string;
  };
  logTail?: string[];
};

export type ConfigBody = {
  port: number;
  mode: ProxyMode;
  proxyEnv: Record<string, string>;
};
