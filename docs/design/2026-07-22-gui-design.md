# Design: claude-code-cache-fix GUI (Windows / macOS)

**Status:** approved product decisions (session 2026-07-22) + completed technical sections  
**Repo:** local `/home/ziyou/project/claude-code-cache-fix-gui`  
**Future GitHub:** `BingZi-233/claude-code-cache-fix-gui`  
**Upstream proxy:** `cnighswonger/claude-code-cache-fix` (npm `claude-code-cache-fix`)  
**Date:** 2026-07-22

---

## 1. Goal

Ship a **Tauri 2** desktop app for **Windows and macOS** so users can start/stop the cache-fix proxy and wire Claude Code’s global config without memorizing shell commands.

Primary pain: CLI-only start/stop and env wiring is inconvenient, especially on Windows (no first-class `install-service` path in upstream today).

---

## 2. Product decisions (locked)

| Decision | Choice |
|----------|--------|
| Surface | **Both** system tray + full control panel |
| Setup depth | Full setup UX **except** launching Claude |
| Claude integration | **Only** modify Claude Code config files; user still runs CLI `claude` |
| Config scope | **User-global** settings only |
| Config root | Honor **`CLAUDE_CONFIG_DIR`** when set; else default `~/.claude` / `%USERPROFILE%\.claude` |
| Config file | `{configRoot}/settings.json` |
| Proxy modes | **Both** reverse (default) and forward (Remote Control); user-switchable in GUI |
| Tech stack | **Tauri 2** |
| Repo model | **Independent repo** (not monorepo into upstream cache-fix) |
| Proxy binary source | **Hybrid:** prefer local npm / `cache-fix-proxy`; else embedded **sidecar** |
| GitHub owner (intent) | `BingZi-233` (local-first; no publish required for this phase) |
| Quit App default | **Stop proxy** on quit (avoid orphan processes); preference may override later |

### Explicit non-goals (v1)

- Launch Claude Code from the GUI
- Linux GUI
- Project-level `.claude/settings.json`
- Per-extension toggle editor
- Production code-signed installers / GitHub Releases publish
- Merging GUI into `cnighswonger/claude-code-cache-fix`
- Replacing upstream `install-service` systemd/launchd units as the only supervision model

---

## 3. Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  Tauri 2 shell (tray + control panel UI)                    │
│  ┌──────────────────┐  ┌──────────────────────────────────┐ │
│  │ UI (HTML/TS)     │  │ Rust commands (thin I/O layer)   │ │
│  └────────┬─────────┘  └────────────────┬─────────────────┘ │
│           │                             │                   │
│           ▼                             ▼                   │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ Pure logic package (Node or shared TS, unit-tested)  │   │
│  │  - resolveClaudeConfigDir / settings path            │   │
│  │  - apply/remove Claude env (reverse vs forward)      │   │
│  │  - discoverProxy (PATH/npm → sidecar)                │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
          │ spawn / health                         │ read/write
          ▼                                        ▼
   cache-fix-proxy server                 settings.json env
   GET /health on port
```

**Principle:** pure functions for path resolution, env merge/diff, and discovery order are **I/O-free** and unit-tested without Tauri. Tauri/Rust (or Node sidecar helpers) only perform filesystem, process, and HTTP I/O.

**Upstream contract (read-only dependency):**

- Process: `cache-fix-proxy server` (or `node …/proxy/server.mjs`)
- Health: `GET http://127.0.0.1:<port>/health`
- Env: `CACHE_FIX_PROXY_PORT`, `CACHE_FIX_FORWARD_PROXY=on`, `CACHE_FIX_CA_DIR` / CA under `{configRoot}/cache-fix-ca/ca.pem` (same rules as upstream)
- Client reverse: `ANTHROPIC_BASE_URL=http://127.0.0.1:<port>`
- Client forward: `HTTPS_PROXY` + `NODE_EXTRA_CA_CERTS`; **leave `ANTHROPIC_BASE_URL` unset**

Version policy: GUI records compatible range (e.g. `>=4.3.0 <5`). On start, inspect local package version; if incompatible, prefer sidecar or surface an upgrade prompt.

---

## 4. UI information architecture

### 4.1 System tray

| Element | Behavior |
|---------|----------|
| Icon | Grey=stopped · Green=running · Yellow=starting/degraded · Red=error |
| Activate | Open control panel (platform-native tray menu on macOS) |
| Start / Stop | Proxy lifecycle |
| Open control panel | Show main window |
| Enable / Disable in Claude | Apply or remove `settings.json` env (same pure logic as panel) |
| Quit | Default **stop proxy** then exit |

Tooltip examples: `cache-fix · running :9801 · reverse` / `stopped`.

### 4.2 Control panel (single page)

1. **Status** — run state, port, mode, health, proxy source (npm vs sidecar), versions  
2. **Proxy control** — start / stop / restart; port; mode reverse (default) / forward  
3. **Claude config** — wire / unwire global settings; env preview; resolved config dir (show if `CLAUDE_CONFIG_DIR` active)  
4. **Startup** — launch app at login; optional “auto-start proxy after login”  
5. **Logs** — recent proxy stdout/stderr; copy / open log file  
6. **About** — GUI version, compatible cache-fix range, links  

### 4.3 Lifecycle state machine

States: `Discovering` → `Stopped` | `Starting` | `Running` | `Degraded` | `Error` | `Attached`.

Rules:

- **Starting:** spawn then poll `/health` (timeout ~10s) → Error + kill child on failure  
- **Running:** health poll ~5s; consecutive **transport** failures (connection refused / non-JSON) → Error path + optional 1–2 auto-restarts of **GUI-spawned** child only  
- **Port/mode change:** requires proxy restart; if Claude wired, rewrite env for new mode/port  
- **Proxy running vs Claude wired** are independent booleans (see §5.5)

#### 4.3.1 Health parse (locked)

```
parseCacheFixHealth(httpStatus, bodyText) →
  { kind: 'ok' | 'degraded' | 'foreign' | 'unreachable', version?, forwardProxy? }
```

Recognition predicate (must all hold for non-foreign when body parses as object):

- JSON object
- `status` ∈ `{ "ok", "degraded" }`
- **and** (`typeof version === "string"` **or** `typeof forward_proxy === "boolean"`)

Mapping:

| Result | State |
|--------|--------|
| `status=="ok"` (HTTP 200 typical) | `Running` (or `Attached` if not GUI-spawned) |
| `status=="degraded"` (HTTP **503** typical) | `Degraded` — process up; show `failed_extensions` / `hint`; **do not** treat as transport death |
| foreign / unknown JSON | `Error` + suggest other port |
| unreachable | start path or transport-failure counter |

#### 4.3.2 Attach-to-existing-port (locked)

- Attach only when `parseCacheFixHealth` returns `ok` or `degraded`.
- Prefer matching `forward_proxy` to GUI-selected mode; on mismatch → **Error** “restart proxy in selected mode” (no silent Claude rewrite).
- Version gate: if `version` present, must satisfy `compatibleRange` (§6); if missing, allow attach only with UI warning.
- Label UI: **“attached (not managed)”**.
- **Stop:** only kill processes the GUI spawned (tracked PID). Attached foreign → **Detach** only (clear GUI state); never kill foreign PID in v1.
- **Never auto-wire Claude** to an attached foreign process without explicit user confirm.

---

## 5. Claude `settings.json` write rules

### 5.1 Config root resolution (shipped pure function)

```
resolveClaudeConfigDir(env, homedir, configDirOverride?):
  if configDirOverride is non-empty string → use it (normalized)
  else if env.CLAUDE_CONFIG_DIR is non-empty string → use it (normalized)
  else → join(homedir, ".claude")
```

Empty string `""` for `CLAUDE_CONFIG_DIR` is treated as **unset** (same as Claude / upstream `claudeHome()`).

Settings path: `join(resolveClaudeConfigDir(...), "settings.json")`.

**GUI process env:** honor `CLAUDE_CONFIG_DIR` when present. Finder/Start Menu launches may lack it → default home. App-config `configDirOverride` wins when set (pure API parameter).

### 5.2 JSON load / save discipline

- If `settings.json` missing → treat as `{}`  
- Parse as JSON object; if top-level is not object → **fail closed** (do not overwrite)  
- If `settings.env` missing → create `env: {}`  
- If `settings.env` exists but is **not** a plain object → **fail closed**  
- Preserve unknown keys; mutate `env` only; stringify 2-space indent + trailing newline  
- Never write secrets; only keys listed in §5.3  
- Atomic write: temp file in same directory then rename  
- **Backup:** before first successful mutate in a session (or first mutate after load), copy existing file to `settings.json.bak` if it exists  

### 5.3 Env keys managed by GUI

**Default port (locked):** `9801` (upstream default).

**Canonical expected env (pure):**

```
computeExpectedEnv({ mode, port, caPemPath }) → Record<string,string>
// reverse: { ANTHROPIC_BASE_URL: "http://127.0.0.1:<port>" }
// forward: { HTTPS_PROXY, https_proxy, NODE_EXTRA_CA_CERTS, NO_PROXY, no_proxy }
//   HTTPS_PROXY = https_proxy = "http://127.0.0.1:<port>"
//   NODE_EXTRA_CA_CERTS = caPemPath (normalized)
//   NO_PROXY/no_proxy = mergeNoProxy(existing)  // see below
```

**Value-match / durability (locked — dual-review H1):**

- On successful **wire**, persist to **GUI app config**: `{ mode, port, caPemPath, expectedEnv, anthropicBaseUrlBackup? }`.
- Equality for unwire: **exact string equality** against that snapshot’s `expectedEnv` (or recompute via `computeExpectedEnv` from the same `{mode,port,caPemPath}` — both must agree; tests pin recomputation).
- Path strings: store and compare the normalized form written at apply time (no silent localhost↔127.0.0.1 aliasing).

| Mode | Set | Remove / must-not-set |
|------|-----|------------------------|
| **Reverse** (default) | `ANTHROPIC_BASE_URL` = `http://127.0.0.1:<port>` | Strip forward keys (`HTTPS_PROXY`, `https_proxy`, `NODE_EXTRA_CA_CERTS`) **only if** values exact-match snapshot/recomputed expected. Then `stripLocalhostNoProxy` on `NO_PROXY`/`no_proxy`. |
| **Forward** | Set dual-case proxy + CA + merged NO_PROXY (parity with upstream launcher) | **Snapshot then remove** `ANTHROPIC_BASE_URL` (see §5.3.1). |

**CA path for forward mode:**

```
caDir = explicitCaDir
     || join(effectiveConfigRoot, "cache-fix-ca")
caPem = join(caDir, "ca.pem")
```

If wiring forward and `ca.pem` missing, start proxy in forward mode first, wait for file **and** `/health` with `forward_proxy:true`, then write settings — else refuse wire.

#### 5.3.1 `ANTHROPIC_BASE_URL` snapshot (blocking fix)

On forward apply, if `env.ANTHROPIC_BASE_URL` is a non-empty string:

1. Copy value into GUI app-config field `anthropicBaseUrlBackup` (do **not** store inside Claude settings).
2. Delete key from `settings.env` (RC requires unset).

On unwire (any mode) or switch reverse→ after forward:

- If `anthropicBaseUrlBackup` is set **and** `ANTHROPIC_BASE_URL` is currently absent → restore backup into settings.
- If user already set a new `ANTHROPIC_BASE_URL`, leave it; clear backup after successful restore or explicit discard.
- Never restore a value that equals a reverse proxy URL we manage unless it was the pre-wire backup.

#### 5.3.2 `NO_PROXY` merge / unmerge (blocking fix — option A)

Hosts always ensured on forward apply: `127.0.0.1`, `localhost`, `::1`.

```
mergeNoProxy(existing: string | undefined): string
  // split on ",", trim, drop empties; append missing localhost hosts; de-dupe exact match
  // existing = settings.env.NO_PROXY || settings.env.no_proxy

stripLocalhostNoProxy(existing: string | undefined): string | undefined
  // remove only the three hosts if present; return undefined if empty after strip
```

- Forward apply: set **both** `NO_PROXY` and `no_proxy` to `mergeNoProxy(...)`.
- Unwire / leave-forward: apply `stripLocalhostNoProxy` to both keys; delete key if empty. **Never** delete the whole list blindly. Corp entries (e.g. `corp.example`) must survive apply→unwire.

`NO_PROXY` is **partially owned**: only the three localhost tokens.

### 5.4 Apply / remove pure API (locked)

```
applyClaudeEnv(settings, { mode, port, caPemPath }, { anthropicBaseUrlBackup? })
  → { nextSettings, expectedEnv, anthropicBaseUrlBackup }

removeClaudeEnv(settings, expectedEnv, { anthropicBaseUrlBackup? })
  → { nextSettings, skipped: string[], anthropicBaseUrlBackup }
```

- Mode switch reverse↔forward: remove with old expected (match guards) then apply new expected; preserve anthropic backup across switch.
- I/O wrappers: load → pure → backup file → atomic save → persist snapshot to GUI app config.

**Unwire:** remove only keys whose current values exact-match `expectedEnv`; mismatched keys go to `skipped`.

### 5.5 Decoupling

| Flag | Meaning |
|------|---------|
| Proxy running | Process up; health kind ok or degraded |
| Claude wired | settings env matches last successful expectedEnv snapshot |

Valid: proxy on + unwired (debug); wired + proxy off (tray warns).

---

## 6. Proxy discovery order (hybrid)

**Compatible range (locked for v1, both modes):** `>=4.3.0 <5`  
(Rationale: forward-proxy requires ≥4.3.0; single range avoids mode-split complexity.)

Ordered candidates; first existing + version-compatible wins:

1. **Explicit path** from GUI app config (if set)  
2. **`cache-fix-proxy` on PATH**  
3. **npm global** `claude-code-cache-fix` → `bin` / `proxy/server.mjs` + host `node`  
4. **Embedded sidecar** (pinned; always treated compatible by construction)  
5. **None** → Error with install guidance  

**Version resolution order:** (1) adjacent `package.json`, (2) package root walk / `npm list -g`, (3) `GET /health` `.version` if already listening, (4) sidecar pin.  
If PATH hit incompatible but sidecar compatible → use sidecar + notice.

### 6.1 Spawn env (blocking fix — always align config root)

When spawning the child proxy, **always** set:

| Variable | Value |
|----------|--------|
| `CACHE_FIX_PROXY_PORT` | validated decimal port string |
| `CLAUDE_CONFIG_DIR` | **effective config root** (same as settings resolution) |
| `CACHE_FIX_CA_DIR` | `join(effectiveConfigRoot, "cache-fix-ca")` (or explicit override) — set for **both** modes so CA/state never desync |
| `CACHE_FIX_FORWARD_PROXY` | `on` only in forward mode |

Single source of truth: GUI-resolved root drives settings path, CA path, and child env.

Default listen port if unset: **9801**.

---

## 7. Packaging & sidecar

| Item | v1 approach |
|------|-------------|
| Framework | Tauri 2 |
| Targets | Windows x64, macOS universal or arm64+x64 as CI allows |
| Sidecar | Bundle pinned `claude-code-cache-fix` tree + Node runtime **or** node-sea launcher for `proxy/server.mjs` |
| OpenSSL | Forward CA generation requires `openssl` on PATH (upstream). GUI detects before enabling forward; if missing, disable forward with message. Refuse wire until health `forward_proxy:true`. |
| Sidecar update | Bump with GUI release; document pin in `About` |
| App config | OS app data: port, mode, quit-stops-proxy, configDirOverride, proxy path, **wire snapshot** (`expectedEnv`, `anthropicBaseUrlBackup`) |
| Auto-start (login) | macOS LaunchAgent/SMAppService; Windows Run key/Task Scheduler — start GUI app; optional auto-start proxy |
| Not v1 | Full notarization/signing; Microsoft Store |

---

## 8. Security & threat model

- **Trust boundary:** localhost only; GUI never sends API keys to third parties  
- **settings.json:** may contain other secrets/tokens; GUI must not log full file contents  
- **Forward MITM CA:** local CA is powerful; only write `NODE_EXTRA_CA_CERTS` to the path this stack generated; document risk in About  
- **Command injection:** never shell-interpolate port; validate port ∈ 1..65535 as decimal integer  
- **Path traversal:** config dir from env is user-controlled by design (same as Claude Code); do not resolve `..` into unrelated system paths beyond normalize  

**Load-bearing?** Yes — touches Claude global config and optional MITM CA wiring.

---

## 9. Non-functional requirements

| Topic | Statement |
|-------|-----------|
| Size/complexity budget | Pure logic ~200–400 LOC + tests; Tauri shell thin; full UI polish iterative |
| Threat model | See §8; no network exfil; careful settings mutation |
| Maintainability | Pure logic in testable module; no premature abstraction beyond discovery/settings/lifecycle |
| Performance | Health poll ≤5s; settings write atomic and rare |
| Load-bearing? | **Yes** |

---

## 10. Testing strategy

Unit tests (no GUI) must exercise **shipped** functions:

1. `resolveClaudeConfigDir` — unset vs set vs empty-string `CLAUDE_CONFIG_DIR`; `configDirOverride` precedence  
2. `settingsPath`  
3. `applyClaudeEnv` reverse — sets `ANTHROPIC_BASE_URL`, strips matching forward keys  
4. `applyClaudeEnv` forward — sets dual-case proxy+CA, snapshots+removes `ANTHROPIC_BASE_URL`, merges `NO_PROXY`  
5. `removeClaudeEnv` — only removes exact-match values; restores `anthropicBaseUrlBackup`  
6. `mergeNoProxy` / `stripLocalhostNoProxy` — corp list survives apply→unwire  
7. `computeExpectedEnv` + port validation  
8. `rankProxyCandidates` / discovery order — PATH before sidecar when both valid; incompatible PATH → sidecar  
9. `parseCacheFixHealth` — ok / degraded(503) / foreign / unreachable  
10. `buildProxySpawnEnv` — always includes `CLAUDE_CONFIG_DIR` + `CACHE_FIX_CA_DIR`  

Integration (optional later): spawn real `cache-fix-proxy server` if present; not gating for headless CI without package.

---

## 11. Implementation slices

1. Spec + dual review + process log (this goal)  
2. Pure logic package + unit tests  
3. Thin CLI or Node entry that uses pure logic (dev aid)  
4. Tauri scaffold + tray/panel wiring (may be stubbed if desktop build unavailable)  
5. Sidecar packaging scripts  

---

## 12. Process

- Dual independent reviews of this design (review-agent + Codex-style)  
- Auto-advance: fix blockers; record decision in `docs/process-log.md`  
- Implementation via subagents where practical  

---

## 13. Open follow-ups (non-blocking)

- GUI app-config override UI for config dir when launched without env  
- Deeper Windows service supervision parity with Linux healthcheck timer  
- Auto-merge `NO_PROXY` edge cases with IPv6 zone IDs  
- Signed releases under `BingZi-233`  
