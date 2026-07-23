#!/usr/bin/env bash
# Package a SINGLE Windows PE executable into dist-kmp-windows/
#   cache-fix-gui-kmp.exe  = PE stub (with app icon) + embedded fat jar
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="$ROOT/dist-kmp-windows"
SCRATCH_BUILD="${SCRATCH_BUILD:-$ROOT/build/kmp-windows-package}"
MINGW_CC="${MINGW_CC:-x86_64-w64-mingw32-gcc}"
STUB="$SCRATCH_BUILD/cache-fix-gui-kmp-stub.exe"
OUT_EXE="$DIST/cache-fix-gui-kmp.exe"
ICON_ICO="$ROOT/scripts-kmp/app-icon.ico"

if [[ -z "${JAVA_HOME:-}" ]]; then
  for candidate in \
    "${HOME}/.sdkman/candidates/java/current" \
    /home/ziyou/.sdkman/candidates/java/17.0.19-tem
  do
    if [[ -x "${candidate}/bin/java" ]]; then
      export JAVA_HOME="$candidate"
      break
    fi
  done
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi
if ! command -v java >/dev/null 2>&1; then
  echo "ERROR: java not found; set JAVA_HOME or install JDK 17+" >&2
  exit 1
fi

echo "==> Building fat jar (with Windows skiko natives when available)"
cd "$ROOT"
./gradlew --no-daemon :desktop:fatJarWindows :shared:compileKotlinJvm

JAR=""
for cand in \
  "$ROOT/desktop/build/libs/cache-fix-gui-kmp-windows-all.jar" \
  "$ROOT/desktop/build/libs/cache-fix-gui-kmp-all.jar" \
  "$ROOT/desktop/build/libs"/cache-fix-gui-kmp-*-all.jar
do
  if [[ -f "$cand" ]]; then
    JAR="$cand"
    break
  fi
done
if [[ -z "$JAR" || ! -f "$JAR" ]]; then
  echo "ERROR: fat jar not found under desktop/build/libs" >&2
  ls -la "$ROOT/desktop/build/libs" || true
  exit 1
fi
echo "Using jar: $JAR ($(du -h "$JAR" | awk '{print $1}'))"

mkdir -p "$DIST" "$SCRATCH_BUILD"
rm -rf "$DIST/ui"
rm -f "$DIST"/*.bat "$DIST"/cache-fix-gui-kmp-all.jar "$DIST"/cache-fix-gui-kmp-windows-all.jar

if ! command -v "$MINGW_CC" >/dev/null 2>&1; then
  echo "ERROR: $MINGW_CC not found; install mingw-w64 / zig" >&2
  exit 1
fi

# ── Brand icon via windres (.rsrc section) ─────────────────────────────────
ICON_OBJ=""
if [[ -f "$ICON_ICO" ]]; then
  echo "==> Ensuring x86_64-w64-mingw32-windres"
  WINDRES_BIN_DIR="$(bash "$ROOT/scripts-kmp/ensure-windres.sh")"
  export PATH="$WINDRES_BIN_DIR:$PATH"
  WINDRES="$(command -v x86_64-w64-mingw32-windres || true)"
  if [[ -z "$WINDRES" ]]; then
    echo "ERROR: windres not available — cannot embed exe icon" >&2
    exit 1
  fi
  echo "    windres: $WINDRES"
  cp -f "$ICON_ICO" "$SCRATCH_BUILD/app-icon.ico"
  # IDI_ICON1 = application icon (Explorer / taskbar for the PE)
  cat > "$SCRATCH_BUILD/app-icon.rc" << 'RC'
1 ICON "app-icon.ico"
IDI_ICON1 ICON "app-icon.ico"
RC
  echo "==> Compiling icon resource"
  (cd "$SCRATCH_BUILD" && "$WINDRES" -O coff app-icon.rc -o app-icon.o)
  ICON_OBJ="$SCRATCH_BUILD/app-icon.o"
  file "$ICON_OBJ"
else
  echo "ERROR: icon not found at $ICON_ICO" >&2
  exit 1
fi

echo "==> Linking PE stub + icon ($MINGW_CC)"
# Link launcher C + .rsrc object. Subsystem patched to GUI after link (zig may ignore -mwindows).
set +e
"$MINGW_CC" -O2 -s \
  -o "$STUB" \
  "$ROOT/scripts-kmp/windows-launcher.c" \
  "$ICON_OBJ" \
  -lshell32
LINK_RC=$?
set -e
if [[ $LINK_RC -ne 0 || ! -f "$STUB" ]]; then
  echo "ERROR: failed to link stub with icon" >&2
  exit 1
fi

if ! grep -a -q '.rsrc' "$STUB" 2>/dev/null; then
  # binary may not contain literal string; check with objdump if available
  if command -v x86_64-w64-mingw32-objdump >/dev/null 2>&1; then
    if ! x86_64-w64-mingw32-objdump -h "$STUB" 2>/dev/null | grep -q '\.rsrc'; then
      echo "WARN: .rsrc section not obvious in stub" >&2
    else
      echo "    .rsrc section present"
    fi
  fi
else
  echo "    stub contains .rsrc marker"
fi
file "$STUB"

echo "==> Patch PE subsystem to WINDOWS (no CMD) + embed jar"
python3 - << PY
import struct, pathlib

def force_windows_subsystem(pe: bytes) -> bytes:
    b = bytearray(pe)
    if b[:2] != b"MZ":
        raise SystemExit("stub is not PE/MZ")
    e_lfanew = struct.unpack_from("<I", b, 0x3C)[0]
    if b[e_lfanew:e_lfanew+4] != b"PE\0\0":
        raise SystemExit("bad PE signature")
    opt_off = e_lfanew + 4 + 20
    magic = struct.unpack_from("<H", b, opt_off)[0]
    if magic not in (0x10B, 0x20B):
        raise SystemExit(f"unknown optional header magic {magic:#x}")
    sub_off = opt_off + 0x44
    old = struct.unpack_from("<H", b, sub_off)[0]
    struct.pack_into("<H", b, sub_off, 2)  # IMAGE_SUBSYSTEM_WINDOWS_GUI
    print(f"PE subsystem {old} -> 2 (WINDOWS_GUI)")
    return bytes(b)

def has_rsrc_section(pe: bytes) -> bool:
    e_lfanew = struct.unpack_from("<I", pe, 0x3C)[0]
    # COFF: NumberOfSections at +6
    num_sections = struct.unpack_from("<H", pe, e_lfanew + 6)[0]
    size_opt = struct.unpack_from("<H", pe, e_lfanew + 20)[0]
    sec_off = e_lfanew + 24 + size_opt
    for i in range(num_sections):
        off = sec_off + i * 40
        name = pe[off:off+8].split(b"\0", 1)[0]
        if name == b".rsrc":
            return True
    return False

stub_path = pathlib.Path("$STUB")
stub_raw = stub_path.read_bytes()
if not has_rsrc_section(stub_raw):
    raise SystemExit("ERROR: stub PE has no .rsrc section — icon was not linked")
print("stub .rsrc section: OK")

stub = force_windows_subsystem(stub_raw)
jar = pathlib.Path("$JAR").read_bytes()
magic = b"CFKGJAR1"
footer = struct.pack("<Q", len(jar)) + magic
out = pathlib.Path("$OUT_EXE")
out.write_bytes(stub + jar + footer)
print(f"Wrote {out} ({out.stat().st_size} bytes = stub {len(stub)} + jar {len(jar)} + footer {len(footer)})")
data = out.read_bytes()
assert data[-8:] == magic
assert struct.unpack("<Q", data[-16:-8])[0] == len(jar)
e = struct.unpack_from("<I", data, 0x3C)[0]
sub = struct.unpack_from("<H", data, e + 4 + 20 + 0x44)[0]
assert sub == 2, f"subsystem still {sub}"
if not has_rsrc_section(data):
    raise SystemExit("ERROR: final exe lost .rsrc section")
print("Footer OK; WINDOWS_GUI + .rsrc icon OK")
PY

cat > "$DIST/README.txt" << EOF
cache-fix-gui — 单文件 Windows 程序
====================================

  cache-fix-gui-kmp.exe   （带应用图标）

双击打开 Compose 控制台（无 CMD；系统托盘自绘菜单）。

  · 启动 → 自动写入 Claude 配置
  · 停止/退出 → 自动恢复配置
  · 关闭窗口 → 隐藏到托盘（可在设置中改）

依赖：Java 17+（javaw.exe / PATH / JAVA_HOME）
      或 runtime\\bin\\javaw.exe

Built: $(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF

echo "==> Artifact summary"
ls -la "$DIST"
file "$OUT_EXE" || true
python3 - << 'PY'
import struct, pathlib
p = pathlib.Path("dist-kmp-windows/cache-fix-gui-kmp.exe")
data = p.read_bytes()
assert data[:2] == b"MZ"
e = struct.unpack_from("<I", data, 0x3C)[0]
num = struct.unpack_from("<H", data, e + 6)[0]
size_opt = struct.unpack_from("<H", data, e + 20)[0]
sec = e + 24 + size_opt
names = []
for i in range(num):
    off = sec + i * 40
    names.append(data[off:off+8].split(b"\0", 1)[0].decode("ascii", "replace"))
print("PE sections:", names)
assert ".rsrc" in names, names
sub = struct.unpack_from("<H", data, e + 4 + 20 + 0x44)[0]
print(f"subsystem={sub} (2=GUI)")
print("ICON EMBED OK")
PY
echo "OK: single Windows executable with icon at $OUT_EXE"
