#!/usr/bin/env bash
# Cross-compile Windows .exe via Docker + cargo-xwin.
# MSVC CRT / crates / target are cached by BuildKit (see Dockerfile.tauri-windows).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

IMAGE="cache-fix-gui-tauri-windows:local"
OUT="$ROOT/dist-windows"
mkdir -p "$OUT"

# Docker image has no Node; build React panel on host first so ui/ is current.
if [[ -f "$ROOT/ui-src/package.json" ]]; then
  echo "==> Building UI on host (Vite → ui/)…"
  (cd "$ROOT/ui-src" && npm install --no-audit --no-fund && npm run build)
fi

export DOCKER_BUILDKIT=1

echo "==> Building Windows Tauri artifacts (BuildKit cache: xwin CRT + cargo + target)…"
echo "    First build downloads MSVC CRT once; later builds reuse BuildKit cache."
docker build \
  -f Dockerfile.tauri-windows \
  -t "$IMAGE" \
  --progress=plain \
  .

echo "==> Copying artifacts to $OUT"
cid=$(docker create "$IMAGE")
docker cp "$cid:/out/." "$OUT/" || true
docker rm "$cid" >/dev/null

echo "==> Artifacts:"
ls -lah "$OUT" || true
if [[ -f "$OUT/cache-fix-gui.exe" ]]; then
  file "$OUT/cache-fix-gui.exe" || true
fi
echo "Done. Copy .exe to a Windows machine (needs WebView2 runtime / bootstrapper)."
