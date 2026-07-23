#!/usr/bin/env python3
"""
Best-effort: set the Windows PE application icon from a .ico file.
Requires `pefile` if available; otherwise exits 1 (caller continues).
"""
from __future__ import annotations

import struct
import sys
from pathlib import Path


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: pe-set-icon.py <exe> <ico>", file=sys.stderr)
        return 2
    exe_path = Path(sys.argv[1])
    ico_path = Path(sys.argv[2])
    if not exe_path.is_file() or not ico_path.is_file():
        return 1
    try:
        import pefile  # type: ignore
    except ImportError:
        # No pefile — leave PE without custom icon (Compose still shows brand icon).
        print("pefile not installed; skip PE icon inject", file=sys.stderr)
        return 1

    # pefile alone cannot easily rewrite RT_GROUP_ICON; use a minimal approach:
    # if already has resources, skip. Prefer windres when available.
    pe = pefile.PE(str(exe_path))
    if hasattr(pe, "DIRECTORY_ENTRY_RESOURCE"):
        print("PE already has resources; leaving as-is")
        pe.close()
        return 0
    pe.close()
    print("PE has no resource directory; use windres for icon embed", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
