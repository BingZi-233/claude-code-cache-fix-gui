import { useCallback, useEffect, useRef, useState } from "react";
import {
  Cable,
  ChevronDown,
  ExternalLink,
  Info,
  Play,
  RefreshCw,
  RotateCcw,
  Save,
  Search,
  Square,
  Unplug,
  X,
} from "lucide-react";

/** Upstream proxy this GUI controls (not this GUI repo). */
const UPSTREAM_REPO = "https://github.com/cnighswonger/claude-code-cache-fix";
const UPSTREAM_NAME = "claude-code-cache-fix";
const ABOUT_DISMISS_KEY = "cache-fix-gui.about-dismissed";
import { toast, Toaster } from "sonner";
import { api, type Status } from "@/lib/api";
import {
  DEFAULT_FORM,
  formToProxyEnv,
  proxyEnvToFormPatch,
  type FormState,
} from "@/lib/proxy-env";
import { cn } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";
import { Textarea } from "@/components/ui/textarea";

function phaseTone(phase: string): {
  dot: string;
  label: string;
} {
  const p = phase.toLowerCase();
  if (p === "running" || p === "attached")
    return { dot: "bg-emerald-500", label: phase };
  if (p === "error") return { dot: "bg-red-500", label: phase };
  if (p === "starting" || p === "degraded" || p === "discovering")
    return { dot: "bg-amber-500", label: phase };
  return { dot: "bg-zinc-400", label: phase || "Stopped" };
}

function modeLabel(mode: string) {
  if (mode === "forward") return "正向";
  if (mode === "reverse") return "反向";
  return mode || "—";
}

function Field({
  label,
  hint,
  children,
  className,
}: {
  label: string;
  hint?: string;
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <div className={cn("grid gap-1.5", className)}>
      <Label className="text-xs font-medium text-muted-foreground">
        {label}
        {hint ? (
          <span className="ml-1.5 font-normal text-foreground/50">· {hint}</span>
        ) : null}
      </Label>
      {children}
    </div>
  );
}

function SwitchRow({
  label,
  description,
  checked,
  onCheckedChange,
  disabled,
}: {
  label: string;
  description?: string;
  checked: boolean;
  onCheckedChange: (v: boolean) => void;
  disabled?: boolean;
}) {
  return (
    <label className="flex cursor-pointer items-center justify-between gap-3 rounded-md border border-border/80 bg-background px-3 py-2">
      <div className="min-w-0">
        <div className="text-sm leading-none">{label}</div>
        {description ? (
          <div className="mt-1 text-xs text-muted-foreground">{description}</div>
        ) : null}
      </div>
      <Switch
        checked={checked}
        onCheckedChange={onCheckedChange}
        disabled={disabled}
      />
    </label>
  );
}

function Section({
  title,
  description,
  action,
  children,
  className,
}: {
  title: string;
  description?: string;
  action?: React.ReactNode;
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <section className={cn("border-b border-border/70 last:border-b-0", className)}>
      <div className="flex items-start justify-between gap-3 px-5 py-3">
        <div className="min-w-0">
          <h2 className="text-sm font-semibold tracking-tight">{title}</h2>
          {description ? (
            <p className="mt-0.5 text-xs text-muted-foreground">{description}</p>
          ) : null}
        </div>
        {action ? <div className="shrink-0">{action}</div> : null}
      </div>
      <div className="space-y-3 px-5 pb-5">{children}</div>
    </section>
  );
}

function MetaItem({ label, value }: { label: string; value: string }) {
  return (
    <div className="min-w-0 rounded-md border border-border/70 bg-muted/30 px-2.5 py-2">
      <div className="text-[11px] text-muted-foreground">{label}</div>
      <div className="mt-0.5 truncate font-mono text-xs" title={value}>
        {value}
      </div>
    </div>
  );
}

export default function App() {
  const [status, setStatus] = useState<Status | null>(null);
  const [form, setForm] = useState<FormState>(DEFAULT_FORM);
  const [envPreview, setEnvPreview] = useState("（加载中…）");
  const [busy, setBusy] = useState(false);
  const [connectError, setConnectError] = useState("");
  const [featuresOpen, setFeaturesOpen] = useState(false);
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const [aboutOpen, setAboutOpen] = useState(() => {
    try {
      return localStorage.getItem(ABOUT_DISMISS_KEY) !== "1";
    } catch {
      return true;
    }
  });
  const formDirty = useRef(false);
  const logsRef = useRef<HTMLPreElement>(null);

  const dismissAbout = useCallback(() => {
    setAboutOpen(false);
    try {
      localStorage.setItem(ABOUT_DISMISS_KEY, "1");
    } catch {
      /* ignore */
    }
  }, []);

  const patchForm = useCallback((p: Partial<FormState>) => {
    formDirty.current = true;
    setForm((f) => ({ ...f, ...p }));
  }, []);

  const collectConfigBody = useCallback(
    () => ({
      port: Number(form.port) || 9801,
      mode: form.mode,
      proxyEnv: formToProxyEnv(form),
    }),
    [form],
  );

  const refresh = useCallback(async () => {
    const s = await api<Status>("/api/status");
    setStatus(s);
    setConnectError(s.lastError || "");
    if (!formDirty.current) {
      setForm((prev) => ({
        ...prev,
        port: s.port ?? prev.port,
        mode: s.mode ?? prev.mode,
        ...proxyEnvToFormPatch(s.proxyEnv),
      }));
    }
    return s;
  }, []);

  const refreshPreview = useCallback(async () => {
    const { env } = await api<{ env: Record<string, string> }>("/api/preview-env");
    setEnvPreview(JSON.stringify(env, null, 2));
  }, []);

  const run = useCallback(
    async (fn: () => Promise<void>, okMsg?: string) => {
      setBusy(true);
      try {
        await fn();
        formDirty.current = false;
        await refresh();
        await refreshPreview().catch(() => {});
        if (okMsg) toast.success(okMsg);
      } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        toast.error(msg);
        setConnectError(msg);
        try {
          await refresh();
        } catch {
          /* ignore */
        }
      } finally {
        setBusy(false);
      }
    },
    [refresh, refreshPreview],
  );

  useEffect(() => {
    refresh()
      .then(() => refreshPreview())
      .catch((err: unknown) => {
        const msg = err instanceof Error ? err.message : String(err);
        setConnectError(msg);
        toast.error(msg);
      });
    const t = setInterval(() => {
      refresh().catch(() => {});
    }, 4000);
    return () => clearInterval(t);
  }, [refresh, refreshPreview]);

  useEffect(() => {
    const el = logsRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [status?.logTail]);

  const s = status;
  const phase = s?.phase || "Stopped";
  const tone = phaseTone(phase);
  const health = s?.health || {};
  const phaseLower = phase.toLowerCase();
  const canStart =
    !busy &&
    phaseLower !== "running" &&
    phaseLower !== "attached" &&
    phaseLower !== "starting";
  const canStop =
    !busy && phaseLower !== "stopped" && phaseLower !== "discovering";

  const saveConfig = () =>
    run(async () => {
      await api("/api/config", {
        method: "POST",
        body: JSON.stringify(collectConfigBody()),
      });
    }, "配置已保存");

  const startProxy = () =>
    run(async () => {
      await api("/api/config", {
        method: "POST",
        body: JSON.stringify(collectConfigBody()),
      });
      await api("/api/start", { method: "POST", body: "{}" });
    }, "代理已启动");

  const stopProxy = () =>
    run(async () => {
      await api("/api/stop", { method: "POST", body: "{}" });
    }, "已停止");

  const restartProxy = () =>
    run(async () => {
      await api("/api/config", {
        method: "POST",
        body: JSON.stringify(collectConfigBody()),
      });
      await api("/api/restart", { method: "POST", body: "{}" });
    }, "已重启");

  const healthText = health.kind
    ? `${health.kind}${health.version ? ` · v${health.version}` : ""}${
        health.forwardProxy != null ? ` · forward=${health.forwardProxy}` : ""
      }`
    : "—";

  return (
    <div className="flex h-full min-h-0 w-full flex-col bg-background text-foreground">
      <Toaster position="top-center" richColors closeButton />

      {/* ── Sticky toolbar ── */}
      <header
        className="shrink-0 border-b bg-card/80 backdrop-blur supports-[backdrop-filter]:bg-card/70"
        data-tauri-drag-region
      >
        <div className="flex h-12 items-center gap-3 px-4" data-tauri-drag-region>
          <div className="flex min-w-0 items-center gap-2.5">
            <img
              src="/favicon.png"
              alt=""
              width={28}
              height={28}
              className="size-7 shrink-0 rounded-md shadow-sm"
              draggable={false}
            />
            <div className="min-w-0">
              <div className="text-sm font-semibold leading-none">cache-fix GUI</div>
              <div className="mt-0.5 truncate text-[11px] text-muted-foreground">
                {UPSTREAM_NAME} 控制面板
              </div>
            </div>
          </div>

          <div className="mx-1 h-5 w-px shrink-0 bg-border" />

          <div className="flex min-w-0 items-center gap-2">
            <span
              className={cn("size-2 shrink-0 rounded-full", tone.dot)}
              aria-hidden
            />
            <span className="truncate text-sm font-medium capitalize">
              {tone.label}
            </span>
            <span className="hidden text-xs text-muted-foreground sm:inline">
              :{s?.port ?? form.port} · {modeLabel(s?.mode || form.mode)}
            </span>
            {s?.claudeWired ? (
              <Badge variant="outline" className="h-5 px-1.5 text-[10px] font-normal">
                Claude 已接线
              </Badge>
            ) : (
              <Badge
                variant="secondary"
                className="h-5 px-1.5 text-[10px] font-normal text-muted-foreground"
              >
                Claude 未接线
              </Badge>
            )}
          </div>

          <div className="ml-auto flex shrink-0 items-center gap-1.5">
            <Button
              size="sm"
              disabled={!canStart}
              onClick={startProxy}
              className="h-8"
            >
              <Play className="size-3.5" />
              启动
            </Button>
            <Button
              size="sm"
              variant="secondary"
              disabled={!canStop}
              onClick={stopProxy}
              className="h-8"
            >
              <Square className="size-3.5" />
              停止
            </Button>
            <Button
              size="sm"
              variant="outline"
              disabled={busy}
              onClick={restartProxy}
              className="h-8"
            >
              <RotateCcw className="size-3.5" />
              重启
            </Button>
            <Button
              size="sm"
              variant="ghost"
              disabled={busy}
              onClick={saveConfig}
              className="h-8"
            >
              <Save className="size-3.5" />
              保存
            </Button>
          </div>
        </div>
      </header>

      {/* ── Scrollable body ── */}
      <div className="min-h-0 flex-1 overflow-auto">
        <div className="mx-auto max-w-3xl">
          {connectError ? (
            <div
              role="alert"
              className="mx-5 mt-4 rounded-md border border-destructive/40 bg-destructive/8 px-3 py-2 text-xs text-destructive"
            >
              <pre className="whitespace-pre-wrap font-sans leading-relaxed">
                {connectError}
              </pre>
            </div>
          ) : null}

          {/* 启动说明：本程序是上游 cache-fix 的 GUI */}
          {aboutOpen ? (
            <div className="mx-5 mt-4 rounded-md border border-border/80 bg-muted/30 px-3 py-3">
              <div className="flex items-start gap-2.5">
                <Info className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
                <div className="min-w-0 flex-1 space-y-1.5 text-xs leading-relaxed">
                  <p className="font-medium text-foreground">
                    本软件是{" "}
                    <span className="font-semibold">{UPSTREAM_NAME}</span>{" "}
                    的桌面 GUI 控制面板
                  </p>
                  <p className="text-muted-foreground">
                    用于启动 / 停止代理、修改{" "}
                    <code className="rounded bg-muted px-1 py-0.5 font-mono text-[11px]">
                      CACHE_FIX_*
                    </code>{" "}
                    配置，以及把 Claude Code 全局 env 接到本地代理。
                    底层代理来自 GitHub 项目{" "}
                    <a
                      href={UPSTREAM_REPO}
                      target="_blank"
                      rel="noreferrer"
                      className="inline-flex items-center gap-0.5 font-medium text-foreground underline-offset-2 hover:underline"
                    >
                      cnighswonger/{UPSTREAM_NAME}
                      <ExternalLink className="size-3" />
                    </a>
                    ，本 GUI 不替代上游本体，只负责图形化管理。
                  </p>
                  <p className="break-all font-mono text-[11px] text-muted-foreground/90">
                    {UPSTREAM_REPO}
                  </p>
                </div>
                <Button
                  size="sm"
                  variant="ghost"
                  className="h-6 w-6 shrink-0 p-0"
                  onClick={dismissAbout}
                  title="关闭说明（下次启动不再显示）"
                >
                  <X className="size-3.5" />
                </Button>
              </div>
            </div>
          ) : null}

          {/* 代理 */}
          <Section
            title="代理"
            description="Upstream 多数需要修改；保存后若已在运行请重启生效"
            action={
              <Button
                size="sm"
                variant="ghost"
                disabled={busy}
                onClick={() =>
                  run(async () => {
                    await api("/api/discover", {
                      method: "POST",
                      body: "{}",
                    });
                  }, "已重新发现代理")
                }
                className="h-7 text-xs"
              >
                <Search className="size-3.5" />
                重新发现
              </Button>
            }
          >
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="Upstream" hint="常用" className="sm:col-span-2">
                <Input
                  type="url"
                  value={form.upstream}
                  onChange={(e) => patchForm({ upstream: e.target.value })}
                  placeholder="https://api.anthropic.com"
                  disabled={busy}
                  className="h-8 font-mono text-xs"
                />
              </Field>
              <Field label="端口">
                <Input
                  type="number"
                  min={1}
                  max={65535}
                  value={form.port}
                  onChange={(e) =>
                    patchForm({ port: Number(e.target.value) || 9801 })
                  }
                  disabled={busy}
                  className="h-8"
                />
              </Field>
              <Field label="模式">
                <Select
                  value={form.mode}
                  onValueChange={(v) =>
                    patchForm({ mode: v as FormState["mode"] })
                  }
                  disabled={busy}
                >
                  <SelectTrigger className="h-8">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="reverse">
                      反向代理（ANTHROPIC_BASE_URL）
                    </SelectItem>
                    <SelectItem value="forward">
                      正向代理（Remote Control）
                    </SelectItem>
                  </SelectContent>
                </Select>
              </Field>
              <Field label="绑定地址">
                <Input
                  value={form.bind}
                  onChange={(e) => patchForm({ bind: e.target.value })}
                  disabled={busy}
                  className="h-8 font-mono text-xs"
                />
              </Field>
              <Field label="超时 (ms)">
                <Input
                  type="number"
                  value={form.timeout}
                  onChange={(e) => patchForm({ timeout: e.target.value })}
                  disabled={busy}
                  className="h-8"
                />
              </Field>
              <div className="sm:col-span-2">
                <SwitchRow
                  label="调试日志"
                  description="CACHE_FIX_DEBUG"
                  checked={form.debug}
                  onCheckedChange={(v) => patchForm({ debug: v })}
                  disabled={busy}
                />
              </div>
            </div>

            <div className="rounded-md border border-border/70 bg-muted/20 p-3">
              <div className="mb-2 text-xs font-medium text-muted-foreground">
                Forward 增强
              </div>
              <div className="grid gap-3 sm:grid-cols-2">
                <Field label="CACHE_FIX_CA_DIR" className="sm:col-span-2">
                  <Input
                    value={form.caDir}
                    onChange={(e) => patchForm({ caDir: e.target.value })}
                    placeholder="默认：配置目录/cache-fix-ca"
                    disabled={busy}
                    className="h-8 font-mono text-xs"
                  />
                </Field>
                <SwitchRow
                  label="下载加速重写"
                  checked={form.downloadRewrite}
                  onCheckedChange={(v) => patchForm({ downloadRewrite: v })}
                  disabled={busy}
                />
                <SwitchRow
                  label="OAuth 刷新"
                  checked={form.oauthRefresh}
                  onCheckedChange={(v) => patchForm({ oauthRefresh: v })}
                  disabled={busy}
                />
              </div>
            </div>
          </Section>

          {/* 企业网络 */}
          <Section
            title="企业网络"
            description="公司代理 / 自定义 CA / TLS"
          >
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="HTTPS_PROXY">
                <Input
                  type="url"
                  value={form.httpsProxy}
                  onChange={(e) => patchForm({ httpsProxy: e.target.value })}
                  placeholder="http://proxy.corp:8080"
                  disabled={busy}
                  className="h-8 font-mono text-xs"
                />
              </Field>
              <Field label="HTTP_PROXY">
                <Input
                  type="url"
                  value={form.httpProxy}
                  onChange={(e) => patchForm({ httpProxy: e.target.value })}
                  placeholder="http://proxy.corp:8080"
                  disabled={busy}
                  className="h-8 font-mono text-xs"
                />
              </Field>
              <Field label="NO_PROXY" className="sm:col-span-2">
                <Input
                  value={form.noProxy}
                  onChange={(e) => patchForm({ noProxy: e.target.value })}
                  placeholder="localhost,127.0.0.1,.corp.example"
                  disabled={busy}
                  className="h-8 font-mono text-xs"
                />
              </Field>
              <Field label="CACHE_FIX_PROXY_CA_FILE" className="sm:col-span-2">
                <Input
                  value={form.caFile}
                  onChange={(e) => patchForm({ caFile: e.target.value })}
                  placeholder="/path/to/corp-ca.pem"
                  disabled={busy}
                  className="h-8 font-mono text-xs"
                />
              </Field>
              <div className="sm:col-span-2">
                <SwitchRow
                  label="拒绝未授权证书"
                  description="关闭后不安全，仅调试用"
                  checked={form.rejectUnauthorized}
                  onCheckedChange={(v) => patchForm({ rejectUnauthorized: v })}
                  disabled={busy}
                />
              </div>
            </div>
          </Section>

          {/* 扩展配置 — collapsible */}
          <Section
            title="扩展配置"
            description="常用 CACHE_FIX_* 开关与高级 env"
            action={
              <Button
                size="sm"
                variant="ghost"
                className="h-7 text-xs"
                onClick={() => setFeaturesOpen((v) => !v)}
              >
                <ChevronDown
                  className={cn(
                    "size-3.5 transition-transform",
                    featuresOpen && "rotate-180",
                  )}
                />
                {featuresOpen ? "收起" : "展开"}
              </Button>
            }
          >
            {featuresOpen ? (
              <>
                <div className="grid gap-3 sm:grid-cols-2">
                  <Field label="Bootstrap 模式">
                    <Select
                      value={form.bootstrapMode || "__default__"}
                      onValueChange={(v) =>
                        patchForm({
                          bootstrapMode: v === "__default__" ? "" : v,
                        })
                      }
                      disabled={busy}
                    >
                      <SelectTrigger className="h-8">
                        <SelectValue placeholder="默认 (audit)" />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="__default__">默认 (audit)</SelectItem>
                        <SelectItem value="audit">audit</SelectItem>
                        <SelectItem value="block">block</SelectItem>
                        <SelectItem value="allowlist">allowlist</SelectItem>
                      </SelectContent>
                    </Select>
                  </Field>
                  <Field label="Thinking Display">
                    <Select
                      value={form.thinkingDisplay || "__default__"}
                      onValueChange={(v) =>
                        patchForm({
                          thinkingDisplay: v === "__default__" ? "" : v,
                        })
                      }
                      disabled={busy}
                    >
                      <SelectTrigger className="h-8">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="__default__">
                          默认 (summarized)
                        </SelectItem>
                        <SelectItem value="summarized">summarized</SelectItem>
                        <SelectItem value="omitted">omitted</SelectItem>
                        <SelectItem value="disabled">disabled</SelectItem>
                      </SelectContent>
                    </Select>
                  </Field>
                  <Field label="Thinking Sanitize">
                    <Select
                      value={form.thinkingSanitize || "__default__"}
                      onValueChange={(v) =>
                        patchForm({
                          thinkingSanitize: v === "__default__" ? "" : v,
                        })
                      }
                      disabled={busy}
                    >
                      <SelectTrigger className="h-8">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="__default__">默认 (on)</SelectItem>
                        <SelectItem value="on">on</SelectItem>
                        <SelectItem value="off">off</SelectItem>
                        <SelectItem value="v2">v2</SelectItem>
                      </SelectContent>
                    </Select>
                  </Field>
                  <SwitchRow
                    label="Image Guard"
                    checked={form.imageGuard}
                    onCheckedChange={(v) => patchForm({ imageGuard: v })}
                    disabled={busy}
                  />
                  <SwitchRow
                    label="Session Mirror"
                    checked={form.sessionMirror}
                    onCheckedChange={(v) => patchForm({ sessionMirror: v })}
                    disabled={busy}
                  />
                  <SwitchRow
                    label="Upstream Error Log"
                    checked={form.upstreamErrorLog}
                    onCheckedChange={(v) => patchForm({ upstreamErrorLog: v })}
                    disabled={busy}
                  />
                </div>

                <div className="rounded-md border border-border/70">
                  <button
                    type="button"
                    className="flex w-full items-center justify-between px-3 py-2 text-left text-xs font-medium text-muted-foreground hover:bg-muted/40"
                    onClick={() => setAdvancedOpen((v) => !v)}
                  >
                    <span>高级 / 自定义 env（每行 KEY=value）</span>
                    <ChevronDown
                      className={cn(
                        "size-3.5 transition-transform",
                        advancedOpen && "rotate-180",
                      )}
                    />
                  </button>
                  {advancedOpen ? (
                    <div className="border-t px-3 py-3">
                      <Textarea
                        value={form.extraEnvText}
                        onChange={(e) =>
                          patchForm({ extraEnvText: e.target.value })
                        }
                        placeholder={
                          "CACHE_FIX_REQUEST_LOG=/tmp/req.log\nCACHE_FIX_HOT_RELOAD=on"
                        }
                        disabled={busy}
                        rows={5}
                        className="font-mono text-xs"
                      />
                      <p className="mt-1.5 text-[11px] text-muted-foreground">
                        同名键以专用控件为准
                      </p>
                    </div>
                  ) : null}
                </div>
              </>
            ) : (
              <p className="text-xs text-muted-foreground">
                Bootstrap / Thinking / Image Guard 等扩展项默认收起，点击右上角展开。
              </p>
            )}
          </Section>

          {/* Claude 接线 */}
          <Section
            title="Claude 接线"
            description="只写入全局 settings.json 的 env，不会启动 Claude CLI"
            action={
              <div className="flex gap-1.5">
                <Button
                  size="sm"
                  disabled={busy}
                  onClick={() =>
                    run(async () => {
                      await api("/api/wire", {
                        method: "POST",
                        body: "{}",
                      });
                    }, "已写入 Claude 配置")
                  }
                  className="h-7 text-xs"
                >
                  <Cable className="size-3.5" />
                  写入
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={busy}
                  onClick={() =>
                    run(async () => {
                      await api("/api/unwire", {
                        method: "POST",
                        body: "{}",
                      });
                    }, "已从 Claude 移除")
                  }
                  className="h-7 text-xs"
                >
                  <Unplug className="size-3.5" />
                  移除
                </Button>
              </div>
            }
          >
            <div className="overflow-hidden rounded-md border border-border/70">
              <div className="flex items-center justify-between border-b bg-muted/30 px-3 py-1.5">
                <span className="text-[11px] text-muted-foreground">
                  将写入的 env 预览
                </span>
                <Button
                  size="sm"
                  variant="ghost"
                  disabled={busy}
                  onClick={() =>
                    run(async () => {
                      await refreshPreview();
                    }, "预览已刷新")
                  }
                  className="h-6 px-2 text-[11px]"
                >
                  <RefreshCw className="size-3" />
                  刷新
                </Button>
              </div>
              <pre className="max-h-40 overflow-auto p-3 font-mono text-[11px] leading-relaxed text-muted-foreground">
                {envPreview}
              </pre>
            </div>
          </Section>

          {/* 运行信息 */}
          <Section title="运行信息" description="状态约 4s 自动刷新">
            <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
              <MetaItem label="阶段" value={phase} />
              <MetaItem label="端口" value={String(s?.port ?? "—")} />
              <MetaItem label="模式" value={modeLabel(s?.mode || "")} />
              <MetaItem label="健康" value={healthText} />
              <MetaItem
                label="代理来源"
                value={
                  s?.launch
                    ? `${s.launch.source} (${s.managedChild ? "managed" : "attached/none"})`
                    : "—"
                }
              />
              <MetaItem label="版本" value={s?.launch?.version || "—"} />
              <MetaItem
                label="Claude"
                value={s?.claudeWired ? "已接线" : "未接线"}
              />
              <MetaItem
                label="配置目录"
                value={s?.paths?.configRoot || "—"}
              />
            </div>
          </Section>

          {/* 日志 */}
          <Section title="日志" description="最近事件（自动滚底）">
            <pre
              ref={logsRef}
              className="max-h-52 overflow-auto rounded-md border border-border/70 bg-muted/25 p-3 font-mono text-[11px] leading-relaxed"
            >
              {(s?.logTail && s.logTail.length
                ? s.logTail.join("\n")
                : "（暂无日志）") +
                (connectError && !s?.logTail?.length
                  ? `\n${connectError}`
                  : "")}
            </pre>
          </Section>

          {/* 关于 */}
          <Section
            title="关于"
            description="本 GUI 与上游代理的关系"
            action={
              !aboutOpen ? (
                <Button
                  size="sm"
                  variant="ghost"
                  className="h-7 text-xs"
                  onClick={() => setAboutOpen(true)}
                >
                  <Info className="size-3.5" />
                  显示说明
                </Button>
              ) : null
            }
          >
            <div className="space-y-2 text-xs leading-relaxed text-muted-foreground">
              <p>
                <span className="font-medium text-foreground">cache-fix GUI</span>{" "}
                是{" "}
                <span className="font-medium text-foreground">{UPSTREAM_NAME}</span>{" "}
                的图形界面：启停代理、写配置、接线 Claude Code。
              </p>
              <p>
                底层代理项目（GitHub）：
                <br />
                <a
                  href={UPSTREAM_REPO}
                  target="_blank"
                  rel="noreferrer"
                  className="mt-1 inline-flex items-center gap-1 break-all font-mono text-[11px] text-foreground underline-offset-2 hover:underline"
                >
                  {UPSTREAM_REPO}
                  <ExternalLink className="size-3 shrink-0" />
                </a>
              </p>
              <p>
                兼容范围{" "}
                <code className="rounded bg-muted px-1 py-0.5 font-mono text-[11px]">
                  {s?.compatibleRange || "—"}
                </code>
                {s?.launch?.version
                  ? ` · 当前代理 v${s.launch.version}`
                  : ""}
              </p>
            </div>
          </Section>
        </div>
      </div>

      {/* ── Footer status bar ── */}
      <footer className="flex h-7 shrink-0 items-center justify-between gap-3 border-t bg-muted/40 px-4 text-[11px] text-muted-foreground">
        <span className="truncate">
          GUI for {UPSTREAM_NAME}
          {" · "}
          兼容 {s?.compatibleRange || "—"}
        </span>
        <a
          href={UPSTREAM_REPO}
          target="_blank"
          rel="noreferrer"
          className="shrink-0 truncate hover:text-foreground hover:underline"
          title={UPSTREAM_REPO}
        >
          GitHub ↗
        </a>
      </footer>
    </div>
  );
}
