#!/usr/bin/env python3
"""frag_apply.py — apply a config fragment onto nuttx .config via kconfiglib.

Fixes vs the naive kconfig-tweak loop:
  * symbol names accepted with or without the CONFIG_ prefix
  * string values accepted quoted or unquoted (no double-quoting bugs)
  * 3 full passes so symbols created by earlier ones stick
  * dependency-aware verification report (per-symbol verdict)
"""
import os
import sys

import kconfiglib

NUTTX = "/home/z/ov/nuttx"
FRAG = sys.argv[1] if len(sys.argv) > 1 else \
    "/home/z/my-project/scripts/vapp_fragment.txt"
PASSES = int(sys.argv[2]) if len(sys.argv) > 2 else 3


def setup_env():
    os.environ["APPSDIR"] = "/home/z/ov/apps"
    os.environ["APPSBINDIR"] = "/home/z/ov/apps"
    os.environ["BINDIR"] = NUTTX
    os.environ["EXTERNALDIR"] = "dummy"
    os.environ["srctree"] = NUTTX
    os.environ["KCONFIG_CONFIG"] = os.path.join(NUTTX, ".config")
    os.environ["ARCH"] = "arm"
    os.environ["SRCARCH"] = "arm"


def parse_fragment(path):
    out = []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            if "=" not in line:
                continue
            sym, val = line.split("=", 1)
            sym = sym.strip()
            if sym.startswith("CONFIG_"):
                sym = sym[len("CONFIG_"):]
            val = val.strip()
            if len(val) >= 2 and val[0] == '"' and val[-1] == '"':
                val = val[1:-1]
            out.append((sym, val))
    return out


def apply_pass(fragment):
    kconf = kconfiglib.Kconfig(os.path.join(NUTTX, "Kconfig"))
    kconf.load_config()
    stuck = []
    for sym_name, val in fragment:
        sym = kconf.syms.get(sym_name)
        if sym is None:
            stuck.append((sym_name, val, "no such symbol"))
            continue
        try:
            if sym.type in (kconfiglib.INT,):
                # kconfiglib 14 wants STRING values for int symbols
                int(val)
                ok = sym.set_value(val)
            elif sym.type in (kconfiglib.HEX,):
                int(val, 16)
                ok = sym.set_value(val)
            elif sym.type in (kconfiglib.BOOL, kconfiglib.TRISTATE):
                if val.lower() in ("y", "m", "n"):
                    ok = sym.set_value(
                        {"y": 2, "m": 1, "n": 0}[val.lower()])
                else:
                    ok = sym.set_value(val)
            else:  # STRING or unknown
                ok = sym.set_value(val)
            if not ok:
                stuck.append((sym_name, val, "set_value rejected (deps?)"))
        except Exception as e:  # noqa: BLE001
            stuck.append((sym_name, val, f"error: {e}"))
    kconf.write_config()
    return stuck


def verify(fragment):
    kconf = kconfiglib.Kconfig(os.path.join(NUTTX, "Kconfig"))
    kconf.load_config()
    bad = []
    for sym_name, val in fragment:
        sym = kconf.syms.get(sym_name)
        if sym is None:
            bad.append((sym_name, val, "MISSING symbol"))
            continue
        cur = sym.str_value
        want = val
        if sym.type in (kconfiglib.BOOL, kconfiglib.TRISTATE):
            cur = {0: "n", 1: "m", 2: "y"}[int(sym.tri_value)]
        if cur != want:
            bad.append((sym_name, val, f"got {cur}"))
    return bad


def main():
    setup_env()
    frag = parse_fragment(FRAG)
    print(f"fragment: {len(frag)} symbols, {PASSES} passes")
    for i in range(PASSES):
        stuck = apply_pass(frag)
        print(f"pass {i + 1}: {len(stuck)} stuck")
        for s in stuck:
            print("   ", s)
        if not stuck:
            break
    bad = verify(frag)
    if bad:
        print(f"=== VERIFY: {len(bad)} NOT in effect ===")
        for sym_name, want, got in bad:
            print(f"  {sym_name}: want {want}, {got}")
        sys.exit(1)
    print("VERIFY OK: all fragment symbols in effect")


if __name__ == "__main__":
    main()
