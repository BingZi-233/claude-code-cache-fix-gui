#!/usr/bin/env bash
# Assemble a Windows portable zip:
#   dist-windows/cache-fix-gui-portable/
#   dist-windows/cache-fix-gui-portable.zip
#
# Layout:
#   cache-fix-gui.exe                          — Tauri tray shell
#   runtime/node.exe                           — bundled Node (no system install needed)
#   sidecar/claude-code-cache-fix/            — embedded proxy (+ deps)
#   bin/ src/ ui/                              — control panel
#   cache-fix-gui.exe (primary) + 启动.bat (optional shortcut)
#
# Prerequisites:
#   - dist-windows/cache-fix-gui.exe already built
#     (./scripts/docker-build-windows.sh)
#   - network to download Node win-x64 zip (once, cached under .cache/)
#   - sibling (or CACHE_FIX_PROXY_SRC) claude-code-cache-fix source for sidecar
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

PROXY_SRC="${CACHE_FIX_PROXY_SRC:-$ROOT/../claude-code-cache-fix}"

NODE_VER="${CACHE_FIX_PORTABLE_NODE_VER:-v22.17.0}"
NODE_ZIP="node-${NODE_VER}-win-x64.zip"
NODE_URL="https://nodejs.org/dist/${NODE_VER}/${NODE_ZIP}"
CACHE_DIR="$ROOT/.cache/portable-node"
OUT_DIR="$ROOT/dist-windows/cache-fix-gui-portable"
ZIP_OUT="$ROOT/dist-windows/cache-fix-gui-portable.zip"
EXE_SRC="$ROOT/dist-windows/cache-fix-gui.exe"

if [[ ! -f "$EXE_SRC" ]]; then
  echo "Missing $EXE_SRC — run ./scripts/docker-build-windows.sh first." >&2
  exit 1
fi

mkdir -p "$CACHE_DIR"
if [[ ! -f "$CACHE_DIR/$NODE_ZIP" ]]; then
  echo "==> Downloading Node ${NODE_VER} win-x64…"
  curl -fL --retry 3 -o "$CACHE_DIR/$NODE_ZIP.partial" "$NODE_URL"
  mv "$CACHE_DIR/$NODE_ZIP.partial" "$CACHE_DIR/$NODE_ZIP"
else
  echo "==> Using cached $CACHE_DIR/$NODE_ZIP"
fi

EXTRACT_DIR="$CACHE_DIR/extract-${NODE_VER}"
rm -rf "$EXTRACT_DIR"
mkdir -p "$EXTRACT_DIR"
echo "==> Extracting Node…"
# unzip is widely available; fall back to python
if command -v unzip >/dev/null 2>&1; then
  unzip -q -o "$CACHE_DIR/$NODE_ZIP" -d "$EXTRACT_DIR"
else
  python3 - <<PY
import zipfile
zipfile.ZipFile("$CACHE_DIR/$NODE_ZIP").extractall("$EXTRACT_DIR")
PY
fi

NODE_ROOT="$(find "$EXTRACT_DIR" -maxdepth 1 -type d -name 'node-*' | head -1)"
if [[ -z "$NODE_ROOT" || ! -f "$NODE_ROOT/node.exe" ]]; then
  echo "Failed to find node.exe in extracted zip" >&2
  ls -la "$EXTRACT_DIR" >&2 || true
  exit 1
fi

echo "==> Assembling portable tree at $OUT_DIR"
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR/runtime" "$OUT_DIR/bin" "$OUT_DIR/src" "$OUT_DIR/ui"

cp -a "$EXE_SRC" "$OUT_DIR/cache-fix-gui.exe"
# Minimal Node runtime (panel has zero npm deps)
cp -a "$NODE_ROOT/node.exe" "$OUT_DIR/runtime/node.exe"
# Keep LICENSE for compliance
if [[ -f "$NODE_ROOT/LICENSE" ]]; then
  cp -a "$NODE_ROOT/LICENSE" "$OUT_DIR/runtime/NODE-LICENSE.txt"
fi

# Ensure React panel is built into ui/
if [[ -f "$ROOT/ui-src/package.json" ]]; then
  echo "==> Building UI (Vite → ui/)…"
  (cd "$ROOT/ui-src" && npm install --no-audit --no-fund && npm run build)
fi

cp -a "$ROOT/bin/." "$OUT_DIR/bin/"
cp -a "$ROOT/src/." "$OUT_DIR/src/"
cp -a "$ROOT/ui/." "$OUT_DIR/ui/"
cp -a "$ROOT/package.json" "$OUT_DIR/package.json"
# Do not ship ui-src node_modules into portable zip

# --- Embedded cache-fix proxy sidecar (required for portable start) ---
if [[ ! -f "$PROXY_SRC/package.json" || ! -f "$PROXY_SRC/proxy/server.mjs" ]]; then
  echo "Missing proxy source at $PROXY_SRC" >&2
  echo "Set CACHE_FIX_PROXY_SRC to a claude-code-cache-fix checkout (≥4.3.0)." >&2
  exit 1
fi

PROXY_VER="$(node -e "console.log(JSON.parse(require('fs').readFileSync(process.argv[1],'utf8')).version)" "$PROXY_SRC/package.json")"
echo "==> Embedding sidecar claude-code-cache-fix@${PROXY_VER} from $PROXY_SRC"

SIDECAR_DIR="$OUT_DIR/sidecar/claude-code-cache-fix"
mkdir -p "$SIDECAR_DIR"
PACK_CACHE="$ROOT/.cache/proxy-pack"
mkdir -p "$PACK_CACHE"
(
  cd "$PROXY_SRC"
  # npm pack only ships package "files" — clean production surface
  TGZ="$(npm pack --pack-destination "$PACK_CACHE" 2>/dev/null | tail -1)"
  if [[ -z "$TGZ" || ! -f "$PACK_CACHE/$TGZ" ]]; then
    # older npm prints only the filename on stdout from cwd
    TGZ="$(ls -1t "$PACK_CACHE"/claude-code-cache-fix-*.tgz 2>/dev/null | head -1)"
    TGZ="$(basename "${TGZ:-}")"
  fi
  if [[ -z "$TGZ" || ! -f "$PACK_CACHE/$TGZ" ]]; then
    echo "npm pack failed for $PROXY_SRC" >&2
    exit 1
  fi
  echo "    packed $TGZ"
  tar -xzf "$PACK_CACHE/$TGZ" -C "$SIDECAR_DIR" --strip-components=1
)
# Install production deps into sidecar (pure-JS: hpagent, proper-lockfile)
(
  cd "$SIDECAR_DIR"
  npm install --omit=dev --ignore-scripts --no-audit --no-fund 2>&1 | tail -15
)
test -f "$SIDECAR_DIR/proxy/server.mjs"
test -d "$SIDECAR_DIR/node_modules/hpagent"
echo "    sidecar ready: $(du -sh "$SIDECAR_DIR" | awk '{print $1}')"

# Double-click launchers (CRLF for notepad / cmd)
write_crlf() {
  local dest="$1"
  shift
  # shellcheck disable=SC2059
  printf '%s\r\n' "$@" >"$dest"
}

# Optional helper — primary launch is double-click cache-fix-gui.exe (self-bootstraps).
write_crlf "$OUT_DIR/启动.bat" \
  '@echo off' \
  'cd /d "%~dp0"' \
  'start "" "%~dp0cache-fix-gui.exe"' \
  'exit /b 0'

write_crlf "$OUT_DIR/启动-浏览器模式.bat" \
  '@echo off' \
  'cd /d "%~dp0"' \
  'set "NODE=%~dp0runtime\node.exe"' \
  'set "CACHE_FIX_GUI_ROOT=%~dp0"' \
  'set "CACHE_FIX_GUI_PROXY_ROOT=%~dp0sidecar\claude-code-cache-fix"' \
  'if not exist "%NODE%" (' \
  '  echo [error] runtime\node.exe missing' \
  '  pause' \
  '  exit /b 1' \
  ')' \
  'echo Panel: http://127.0.0.1:19801/' \
  'echo Close this window to stop the panel.' \
  '"%NODE%" "%~dp0bin\cache-fix-gui.mjs" panel' \
  'pause'

write_crlf "$OUT_DIR/README.txt" \
  'cache-fix GUI — Windows 便携版' \
  '' \
  '这是什么：' \
  '  本程序是 claude-code-cache-fix 的桌面 GUI 控制面板。' \
  '  底层代理来自 GitHub 项目：' \
  '  https://github.com/cnighswonger/claude-code-cache-fix' \
  '  GUI 负责启停代理、改配置、接线 Claude Code，不替代上游本体。' \
  '' \
  '用法（推荐）：' \
  '  1. 解压本文件夹到任意目录（不要放在需要管理员权限的路径）' \
  '  2. 双击 cache-fix-gui.exe  （无需 bat）' \
  '  3. 托盘图标 / 控制面板窗口会出现' \
  '  4. 在界面点「启动」即可拉起内置 cache-fix 代理' \
  '' \
  '说明：' \
  '  - exe 会自动使用同目录 runtime\\node.exe 与 sidecar\\' \
  '  - 启动失败时查看同目录 cache-fix-gui.log / cache-fix-gui-panel.log' \
  '  - 「启动.bat」仅作可选快捷方式，功能与双击 exe 相同' \
  '' \
  '浏览器模式（不依赖 WebView2 窗口壳）：' \
  '  双击「启动-浏览器模式.bat」' \
  '  浏览器打开 http://127.0.0.1:19801/' \
  '' \
  '系统要求：' \
  '  - Windows 10/11 x64' \
  '  - WebView2 Runtime（Win10/11 通常已自带；缺失时 GUI 会提示安装）' \
  '  - 无需安装系统 Node.js / npm（已内置 runtime + sidecar）' \
  '' \
  '目录说明：' \
  '  cache-fix-gui.exe                 双击启动（托盘 + 窗口）' \
  '  runtime\node.exe                   内置 Node 运行时' \
  '  sidecar\claude-code-cache-fix\    内置代理本体' \
  '  bin\ src\ ui\                      控制面板服务与页面' \
  '' \
  "Node runtime: ${NODE_VER} win-x64 (official nodejs.org build)" \
  "Embedded proxy: claude-code-cache-fix@${PROXY_VER}" \
  'GUI version: see package.json'

echo "==> Zipping…"
rm -f "$ZIP_OUT"
(
  cd "$ROOT/dist-windows"
  if command -v zip >/dev/null 2>&1; then
    zip -r -q cache-fix-gui-portable.zip cache-fix-gui-portable
  else
    python3 - <<'PY'
import shutil
from pathlib import Path
root = Path("cache-fix-gui-portable")
shutil.make_archive("cache-fix-gui-portable", "zip", root.parent, root.name)
PY
  fi
)

echo "==> Done"
ls -lah "$OUT_DIR/cache-fix-gui.exe" "$OUT_DIR/runtime/node.exe" "$OUT_DIR/sidecar/claude-code-cache-fix/proxy/server.mjs" "$ZIP_OUT"
echo
echo "Portable folder: $OUT_DIR"
echo "Portable zip:    $ZIP_OUT"
file "$OUT_DIR/cache-fix-gui.exe" "$OUT_DIR/runtime/node.exe"
# Smoke: discovery must find sidecar without system npm
echo "==> Smoke discover (sidecar)…"
(
  cd "$OUT_DIR"
  # Use host node for smoke on Linux build host (portable node.exe is Windows PE)
  if command -v node >/dev/null 2>&1; then
    CACHE_FIX_GUI_PROXY_ROOT="$OUT_DIR/sidecar/claude-code-cache-fix" \
      node ./bin/cache-fix-gui.mjs discover | head -c 400
    echo
  fi
)
