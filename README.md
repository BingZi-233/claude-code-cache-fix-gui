# claude-code-cache-fix-gui

Desktop control panel for [claude-code-cache-fix](https://github.com/cnighswonger/claude-code-cache-fix).

**Future GitHub:** `BingZi-233/claude-code-cache-fix-gui`  
**Stack:** Node control plane (unit-tested) + local web panel + Tauri 2 shell (Windows / macOS)

## What it does

| Feature | Status |
|---------|--------|
| Start / stop cache-fix proxy | ✅ Node controller |
| Hybrid discovery (PATH → npm → sibling checkout → sidecar) | ✅ |
| Reverse + forward proxy modes | ✅ |
| Full proxy env UI (Upstream 置顶 + 企业网络 + 扩展 + 高级 KEY=value) | ✅ 持久化到 `~/.cache-fix-gui/state.json` `proxyEnv` |
| Wire / unwire Claude global `settings.json` `env` | ✅ (`CLAUDE_CONFIG_DIR` honored) |
| Local control panel UI | ✅ Vite + React + shadcn/ui → `ui/` (`http://127.0.0.1:19801`) |
| Does **not** launch Claude CLI | ✅ by design |
| Tauri tray shell | ✅ scaffold (`src-tauri/`) — build on Windows/macOS |
| Full tray icons / signed installers | ⏳ next |

## Quick start (works today on any OS with Node 18+)

```bash
cd claude-code-cache-fix-gui
npm test          # pure logic + I/O tests
npm run ui:build  # build React panel into ui/ (first time / after UI changes)
npm start         # opens control panel in browser
```

UI development (hot reload; proxy `/api` → panel on :19801):

```bash
npm run panel:no-open   # terminal 1: API
npm run ui:dev          # terminal 2: Vite on :5173
```

CLI:

```bash
node bin/cache-fix-gui.mjs status
node bin/cache-fix-gui.mjs start --port 9801 --mode reverse
node bin/cache-fix-gui.mjs wire      # write ~/.claude/settings.json env
node bin/cache-fix-gui.mjs unwire
node bin/cache-fix-gui.mjs stop
```

### Claude config

- Global only: `{CLAUDE_CONFIG_DIR||~/.claude}/settings.json`
- Reverse: sets `ANTHROPIC_BASE_URL=http://127.0.0.1:<port>`
- Forward: sets `HTTPS_PROXY` + `NODE_EXTRA_CA_CERTS` (+ `NO_PROXY` localhost merge); snapshots prior `ANTHROPIC_BASE_URL`
- Never starts `claude`

### Proxy discovery order

1. Explicit path (app state)
2. `cache-fix-proxy` on `PATH`
3. npm global / sibling `../claude-code-cache-fix` checkout
4. Embedded `sidecar/claude-code-cache-fix` (optional)

Compatible range: `>=4.3.0 <5`.

## Tauri 2 (native window + tray)

Scaffold lives in `src-tauri/`. Webview loads `ui/`. Tray: open panel / quit. Closing the window hides to tray.

### Docker build (no host sudo; recommended on this machine)

Installs WebKitGTK **inside the image**, compiles release binary + `.deb`:

```bash
./scripts/docker-build-tauri.sh
# or:
docker build -f Dockerfile.tauri -t cache-fix-gui-tauri-build:local .
mkdir -p dist-tauri && cid=$(docker create cache-fix-gui-tauri-build:local) \
  && docker cp "$cid:/out/." dist-tauri/ && docker rm "$cid"
```

Artifacts land in `dist-tauri/`:

- `cache-fix-gui` — Linux amd64 binary (~7.7 MB)
- `cache-fix-gui_0.1.0_amd64.deb` — deb package

Host runtime still needs WebKitGTK libs to **run** the binary (Ubuntu):

```bash
sudo apt-get install -y libwebkit2gtk-4.1-0 libgtk-3-0 libayatana-appindicator3-1
./dist-tauri/cache-fix-gui
# or: sudo dpkg -i dist-tauri/cache-fix-gui_0.1.0_amd64.deb
```

### Native build (Windows / macOS / Linux with deps)

```bash
# Linux system deps (Ubuntu/Debian)
sudo apt-get install -y libwebkit2gtk-4.1-dev libgtk-3-dev \
  libayatana-appindicator3-dev librsvg2-dev patchelf

cargo install tauri-cli --version "^2" --locked
cd src-tauri && cargo tauri build --bundles deb   # Linux
# Windows/macOS: cargo tauri build
```

## Design & reviews

- [docs/design/2026-07-22-gui-design.md](docs/design/2026-07-22-gui-design.md)
- [docs/reviews/](docs/reviews/)
- [docs/process-log.md](docs/process-log.md)

## Layout

```
bin/cache-fix-gui.mjs   CLI + panel launcher
src/                     pure logic + controller + panel server
ui-src/                  Vite + React + shadcn/ui source
ui/                      built static panel (served by panel-server / Tauri)
src-tauri/               Tauri 2 shell (tray + window)
test/                    node:test suite
sidecar/                 optional bundled cache-fix tree
```

## License

MIT
