#!/usr/bin/env bash
# Ensure x86_64-w64-mingw32-windres is on PATH (download Ubuntu deb if missing).
set -euo pipefail

if command -v x86_64-w64-mingw32-windres >/dev/null 2>&1; then
  command -v x86_64-w64-mingw32-windres
  exit 0
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="${WINDRES_TOOLS:-$ROOT/build/mingw-windres}"
BIN="$TOOLS/usr/bin/x86_64-w64-mingw32-windres"

if [[ -x "$BIN" ]]; then
  echo "$TOOLS/usr/bin"
  exit 0
fi

echo "==> Fetching mingw windres into $TOOLS" >&2
mkdir -p "$TOOLS/download"
cd "$TOOLS/download"
if [[ ! -f binutils-mingw-w64-x86-64.deb ]]; then
  apt-get download binutils-mingw-w64-x86-64 >&2
  mv binutils-mingw-w64-x86-64_*.deb binutils-mingw-w64-x86-64.deb
fi
if [[ ! -f binutils-mingw-w64-base.deb ]]; then
  apt-get download binutils-mingw-w64-base >&2 || true
  mv binutils-mingw-w64-base_*.deb binutils-mingw-w64-base.deb 2>/dev/null || true
fi
dpkg-deb -x binutils-mingw-w64-x86-64.deb "$TOOLS"
if [[ -f binutils-mingw-w64-base.deb ]]; then
  dpkg-deb -x binutils-mingw-w64-base.deb "$TOOLS"
fi

if [[ ! -x "$BIN" ]]; then
  echo "ERROR: windres still missing after extract" >&2
  exit 1
fi
echo "$TOOLS/usr/bin"
