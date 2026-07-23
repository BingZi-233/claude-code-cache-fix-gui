#!/usr/bin/env bash
# Build desktop/src/main/resources/app-icon.icns from app-icon.png (macOS only).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PNG="$ROOT/desktop/src/main/resources/app-icon.png"
OUT="$ROOT/desktop/src/main/resources/app-icon.icns"
ICONSET="${TMPDIR:-/tmp}/cache-fix-gui-AppIcon.iconset"

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "ERROR: make-macos-icns.sh requires macOS (sips + iconutil)" >&2
  exit 1
fi
if [[ ! -f "$PNG" ]]; then
  echo "ERROR: missing $PNG" >&2
  exit 1
fi

rm -rf "$ICONSET"
mkdir -p "$ICONSET"

sips -z 16 16     "$PNG" --out "$ICONSET/icon_16x16.png" >/dev/null
sips -z 32 32     "$PNG" --out "$ICONSET/icon_16x16@2x.png" >/dev/null
sips -z 32 32     "$PNG" --out "$ICONSET/icon_32x32.png" >/dev/null
sips -z 64 64     "$PNG" --out "$ICONSET/icon_32x32@2x.png" >/dev/null
sips -z 128 128   "$PNG" --out "$ICONSET/icon_128x128.png" >/dev/null
sips -z 256 256   "$PNG" --out "$ICONSET/icon_128x128@2x.png" >/dev/null
sips -z 256 256   "$PNG" --out "$ICONSET/icon_256x256.png" >/dev/null
sips -z 512 512   "$PNG" --out "$ICONSET/icon_256x256@2x.png" >/dev/null
sips -z 512 512   "$PNG" --out "$ICONSET/icon_512x512.png" >/dev/null
sips -z 1024 1024 "$PNG" --out "$ICONSET/icon_512x512@2x.png" >/dev/null

iconutil -c icns "$ICONSET" -o "$OUT"
rm -rf "$ICONSET"
echo "Wrote $OUT ($(wc -c < "$OUT") bytes)"
