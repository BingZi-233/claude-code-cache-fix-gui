# claude-code-cache-fix-gui

Desktop control panel for [claude-code-cache-fix](https://github.com/cnighswonger/claude-code-cache-fix).

**Future GitHub:** `BingZi-233/claude-code-cache-fix-gui`  
**Stack:** Kotlin Multiplatform + Compose Multiplatform Desktop

## Overview

Gradle multiplatform project:

| Module | Role |
|--------|------|
| `shared/` | Domain logic (config, wire/unwire, health, discovery, spawn env) |
| `desktop/` | Compose Desktop UI + CLI entry + fat JAR packaging |
| `scripts-kmp/` | Windows single-file PE packaging |

## Build & test

```bash
# JDK 17+
./gradlew :shared:allTests :desktop:test :desktop:fatJar

# Compose Desktop GUI (default)
./gradlew :desktop:run
java -jar desktop/build/libs/cache-fix-gui-kmp-all.jar gui

# CLI
java -jar desktop/build/libs/cache-fix-gui-kmp-all.jar status
java -jar desktop/build/libs/cache-fix-gui-kmp-all.jar start|stop|wire|unwire

# Optional: local HTTP API panel (JSON + optional static UI dir)
java -jar desktop/build/libs/cache-fix-gui-kmp-all.jar serve
```

Compose UI covers: start/stop/restart, save config, proxy env form, discover,
wire/unwire Claude, env preview, status metrics, log tail, **设置页**, **系统托盘**
（关闭窗口可隐藏到托盘；Windows 单文件 exe 为 GUI 子系统，无 CMD 黑窗）。

## CI (GitHub Actions)

Workflow: [`.github/workflows/build.yml`](.github/workflows/build.yml)

| Job | Runner | Artifact |
|-----|--------|----------|
| `windows` | Ubuntu + MinGW | `cache-fix-gui-kmp.exe` (single-file PE) |
| `windows-msi` | Windows + WiX | `.msi` installer |
| `macos` (x64) | `macos-13` (Intel) | `macos-dmg-x64` — `*-x64.dmg` |
| `macos` (arm64) | `macos-14` (Apple Silicon) | `macos-dmg-arm64` — `*-arm64.dmg` |

Triggers: `push` / `pull_request` to `main`, tags `v*`, and manual `workflow_dispatch`.  
Tag `v*` also attaches artifacts to a GitHub Release.

## Windows 单文件 exe

```bash
./scripts-kmp/package-windows.sh
# → dist-kmp-windows/cache-fix-gui-kmp.exe   （唯一交付物：PE + 内嵌 jar）
```

Windows 上（需 Java 17+ 在 PATH / JAVA_HOME，或旁路 `runtime\bin\java.exe`）：

- **双击** `cache-fix-gui-kmp.exe` → Compose GUI  
- 或命令行：`cache-fix-gui-kmp.exe status`  

无 bat、无旁路 jar；首次运行解压到 `%LOCALAPPDATA%\cache-fix-gui-kmp\`。

Shared pure domain lives under `shared/src/commonMain` and is unit-tested in `commonTest` / `jvmTest`.

## What it does

| Feature | Status |
|---------|--------|
| Start / stop cache-fix proxy | ✅ KMP JVM controller |
| Hybrid discovery (PATH → npm → sibling checkout → sidecar) | ✅ |
| Reverse + forward proxy modes | ✅ |
| Full proxy env UI (Upstream 置顶 + 企业网络 + 扩展 + 高级 KEY=value) | ✅ Compose Desktop |
| Wire / unwire Claude global `settings.json` `env` | ✅ (`CLAUDE_CONFIG_DIR` honored) |
| Compose Desktop control panel | ✅ |
| Does **not** launch Claude CLI | ✅ by design |
| Windows PE package (`dist-kmp-windows/`) | ✅ launcher + fat jar |
| System tray (hide on close) | ✅ |

## Claude config

- Global only: `{CLAUDE_CONFIG_DIR||~/.claude}/settings.json`
- Reverse: sets `ANTHROPIC_BASE_URL=http://127.0.0.1:<port>`
- Forward: sets `HTTPS_PROXY` + `NODE_EXTRA_CA_CERTS` (+ `NO_PROXY` localhost merge); snapshots prior `ANTHROPIC_BASE_URL`
- Never starts `claude`

## Proxy discovery order

1. Explicit path (app state)
2. `cache-fix-proxy` on `PATH`
3. npm global / sibling `../claude-code-cache-fix` checkout
4. Embedded `sidecar/claude-code-cache-fix` (optional)

Compatible range: `>=4.3.0 <5`.

## Layout

```
shared/          KMP domain (commonMain + jvmMain)
desktop/         Compose Desktop UI + fatJar / CLI
scripts-kmp/     Windows PE packaging
gradle/          Gradle wrapper
docs/            design notes & process log
```

## Design & reviews

- [docs/design/2026-07-22-gui-design.md](docs/design/2026-07-22-gui-design.md)
- [docs/reviews/](docs/reviews/)
- [docs/process-log.md](docs/process-log.md)

## License

MIT
