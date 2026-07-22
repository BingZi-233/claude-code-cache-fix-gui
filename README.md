# claude-code-cache-fix-gui

Desktop GUI (Windows / macOS) for [claude-code-cache-fix](https://github.com/cnighswonger/claude-code-cache-fix).

**Future GitHub owner:** `BingZi-233`  
**Stack (target):** Tauri 2 + pure Node/TS config helpers  
**Status:** design + pure-logic core; full tray UI may require a desktop build environment.

## What it does

- System tray + control panel for start/stop of the local cache-fix proxy
- Hybrid proxy discovery: system `cache-fix-proxy` / npm global → embedded sidecar
- Reverse proxy (default) and forward-proxy (Remote Control) modes
- Wires Claude Code **global** `{CLAUDE_CONFIG_DIR||~/.claude}/settings.json` `env` only — **does not launch Claude**

## Design

See [docs/design/2026-07-22-gui-design.md](docs/design/2026-07-22-gui-design.md).

## Pure logic (unit-tested)

Requires Node.js ≥ 18. No install step (zero runtime deps):

```bash
npm test
```

Uses Node’s built-in test runner (`node --test`). Tests import shipped modules under `src/` (not reimplementations).

Shipped modules under `src/`:

- `claude-config.mjs` — config dir + settings path (`CLAUDE_CONFIG_DIR`)
- `settings-env.mjs` — apply/remove reverse & forward env blocks, NO_PROXY merge
- `proxy-discover.mjs` — discovery order (explicit → PATH → npm global → sidecar)
- `health.mjs` — `parseCacheFixHealth` for `/health` responses
- `spawn-env.mjs` — child proxy env (`CLAUDE_CONFIG_DIR`, `CACHE_FIX_CA_DIR`, …)

## License

MIT (intended; align with upstream cache-fix).
