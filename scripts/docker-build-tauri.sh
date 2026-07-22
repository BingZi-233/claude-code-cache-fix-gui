#!/usr/bin/env bash
# Build Tauri GUI inside Docker (installs WebKitGTK in the image; no host sudo).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

IMAGE="cache-fix-gui-tauri-build:local"
OUT="$ROOT/dist-tauri"
mkdir -p "$OUT"

echo "==> Building Docker image + compiling Tauri (this can take several minutes)…"
docker build -f Dockerfile.tauri -t "$IMAGE" .

echo "==> Copying artifacts to $OUT"
# Create a temp container to copy /out
cid=$(docker create "$IMAGE")
docker cp "$cid:/out/." "$OUT/" || true
docker rm "$cid" >/dev/null

echo "==> Artifacts:"
ls -la "$OUT" || true
echo "Done. Binary/deb (if any) are under dist-tauri/"
