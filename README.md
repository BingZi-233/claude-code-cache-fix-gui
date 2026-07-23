# claude-code-cache-fix-gui

[中文文档](README.zh-CN.md)

Desktop control panel for [claude-code-cache-fix](https://github.com/cnighswonger/claude-code-cache-fix).

**Repo:** [BingZi-233/claude-code-cache-fix-gui](https://github.com/BingZi-233/claude-code-cache-fix-gui)  
**Stack:** Kotlin Multiplatform + Compose Desktop  
**License:** MIT

## What is this?

A **native desktop app** that starts and manages the [claude-code-cache-fix](https://github.com/cnighswonger/claude-code-cache-fix) local proxy, and **wires Claude Code’s global config** so requests go through that proxy — without memorizing shell commands.

It does **not** launch the Claude CLI. You still open Claude yourself; this app only owns the proxy and `settings.json` env wiring.

## Features

### Proxy lifecycle

- **Start / Stop / Restart** the cache-fix proxy process
- Live **phase & health** status (running, stopped, errors)
- **Log tail** for quick troubleshooting
- Optional **auto-install** of `claude-code-cache-fix` via npm when the package is missing

### Wire Claude Code

- **Wire / Unwire** global Claude config: `{CLAUDE_CONFIG_DIR||~/.claude}/settings.json` → `env`
- **Reverse mode:** set `ANTHROPIC_BASE_URL=http://127.0.0.1:<port>`
- **Forward mode:** set `HTTPS_PROXY` + `NODE_EXTRA_CA_CERTS`, merge localhost into `NO_PROXY`, snapshot prior `ANTHROPIC_BASE_URL`
- Respects **`CLAUDE_CONFIG_DIR`**
- Start can auto-wire; stop / quit can restore prior env (controller lifecycle)

### Proxy configuration UI

- Port, bind address, reverse / forward **mode**
- **Upstream** settings (primary)
- Enterprise / network options, extensions, and **advanced KEY=value** env editor
- Save config, **preview wire env** before applying
- Debug toggle and related proxy env knobs

### Discovery

Hybrid resolution of the proxy binary / package, in order:

1. Explicit path (saved in app state)
2. `cache-fix-proxy` on `PATH`
3. npm global install / sibling checkout
4. Optional embedded `sidecar/claude-code-cache-fix`

Compatible upstream range: **`>=4.3.0 <5`**.

### Desktop UX

- **Compose Desktop** control panel (console + settings pages)
- **System tray** — close window to hide; restore from tray
- Options: close-to-tray, start minimized to tray
- Windows single-file **GUI subsystem** PE (no black CMD window)
- Optional CLI: `status` / `start` / `stop` / `wire` / `unwire` / `discover`

## Downloads

See [Releases](https://github.com/BingZi-233/claude-code-cache-fix-gui/releases):

| Asset | Platform |
|-------|----------|
| `cache-fix-gui-kmp.exe` | Windows single-file PE (recommended) |
| `*.msi` | Windows installer |
| `*-x64.dmg` | macOS **Intel** |
| `*-arm64.dmg` | macOS **Apple Silicon** |

Packages are **unsigned**; the OS may show a first-run security prompt.  
Windows PE needs **Java 17+** on `PATH` / `JAVA_HOME` (or a side-by-side runtime).

## Quick start

1. Install a compatible [claude-code-cache-fix](https://github.com/cnighswonger/claude-code-cache-fix) (or let the app try npm install).
2. Download the build for your OS from Releases.
3. Open the app → configure port / mode / upstream → **Start**.
4. Use **Wire** (or auto-wire on start) so Claude Code picks up the proxy env.
5. Start Claude Code as usual.

## Build from source

```bash
# JDK 17+
./gradlew :shared:allTests :desktop:test :desktop:fatJar
./gradlew :desktop:run

# Windows single-file PE (needs MinGW on Linux/CI)
./scripts-kmp/package-windows.sh
```

CLI examples:

```bash
java -jar desktop/build/libs/cache-fix-gui-kmp-all.jar status
java -jar desktop/build/libs/cache-fix-gui-kmp-all.jar start
java -jar desktop/build/libs/cache-fix-gui-kmp-all.jar wire
```

CI builds Windows PE/MSI and macOS x64 + arm64 DMGs via [`.github/workflows/build.yml`](.github/workflows/build.yml).

## Project layout

```
shared/          Domain logic (config, wire, health, discovery)
desktop/         Compose Desktop UI + CLI
scripts-kmp/     Windows PE packaging helpers
.github/         CI workflows
docs/            Design notes (historical)
```
