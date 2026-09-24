#!/bin/bash
# apply_all.sh — idempotent replay of ALL tree modifications for the vapp
# firmware. Safe to re-run: every step is guarded/skipped when already done.
set -u
OVB=/home/z/ov
P=/home/z/my-project/scripts/patches
cd $OVB || exit 1

have() { [ -d "$1/.git" ] || [ -f "$1/.git" ]; }

apply_patch() { # apply_patch <repo dir> <patch file>
  local d=$1 p=$2
  [ -f "$p" ] || { echo "PATCH MISSING $p"; return 0; }
  if git -C "$d" apply --reverse --check "$p" 2>/dev/null; then
    echo "already applied: $(basename $p)"
  else
    git -C "$d" apply "$p" && echo "applied: $(basename $p)" || { echo "APPLY FAIL: $p"; return 1; }
  fi
}

echo "=== [1] openrt sources -> quickapp/openrt ==="
QD=$OVB/frameworks/runtimes/quickapp
mkdir -p $QD/openrt
cp $P/openrt/*.c $P/openrt/*.h $QD/openrt/
apply_patch $QD $P/quickapp-openrt-wiring.patch

echo "=== [2] zlib Makefile patch ==="
apply_patch $OVB/apps $P/apps-zlib.patch

echo "=== [2b] feature jidl neuter ==="
apply_patch $OVB/frameworks/runtimes/feature $P/feature-jidl.patch

echo "=== [3] nuttx patches (drivers/headers/Make.defs) ==="
apply_patch $OVB/nuttx $P/nuttx.patch

echo "=== [4] external wrappers ==="
cp $P/wrappers/libpng/Kconfig $P/wrappers/libpng/Make.defs $P/wrappers/libpng/Makefile $OVB/external/libpng/
cp $P/wrappers/freetype/Kconfig $P/wrappers/freetype/Make.defs $P/wrappers/freetype/Makefile $OVB/external/freetype/freetype/
cp $P/wrappers/yoga/Kconfig $P/wrappers/yoga/Make.defs $P/wrappers/yoga/Makefile $OVB/external/yoga/
cp $P/wrappers/protobuf-c/Kconfig $P/wrappers/protobuf-c/Make.defs $P/wrappers/protobuf-c/Makefile $OVB/external/protobuf-c/
cp $P/wrappers/curl/Kconfig $P/wrappers/curl/Make.defs $P/wrappers/curl/Makefile $OVB/external/curl/
cp $P/wrappers/curl/curl_config.h $OVB/external/curl/lib/curl_config.h
echo "wrappers copied"

echo "=== [5] frameworks/external glue ==="
cp $P/wrappers/glue/frameworks.Kconfig $OVB/frameworks/Kconfig
cp $P/wrappers/glue/frameworks.Make.defs $OVB/frameworks/Make.defs
cp $P/wrappers/glue/external.Kconfig $OVB/external/Kconfig
cp $P/wrappers/glue/external.Make.defs $OVB/external/Make.defs
echo "glue copied"

echo "=== [6] libpng pnglibconf + NEON off ==="
if [ ! -f $OVB/external/libpng/pnglibconf.h ]; then
  cp $OVB/external/libpng/scripts/pnglibconf.h.prebuilt $OVB/external/libpng/pnglibconf.h
fi
sed -i 's/define PNG_ARM_NEON_OPT .*/define PNG_ARM_NEON_OPT 0/' $OVB/external/libpng/pnglibconf.h
if ! grep -q "define PNG_ARM_NEON_OPT" $OVB/external/libpng/pnglibconf.h; then
  echo '#define PNG_ARM_NEON_OPT 0' >> $OVB/external/libpng/pnglibconf.h
fi
# PNG_ZLIB_VERNUM: prebuilt header was generated against an older zlib;
# 0 disables the version check (allowed by pngpriv.h)
sed -i 's/define PNG_ZLIB_VERNUM .*/define PNG_ZLIB_VERNUM 0/' $OVB/external/libpng/pnglibconf.h
if ! grep -q "define PNG_ZLIB_VERNUM" $OVB/external/libpng/pnglibconf.h; then
  echo '#define PNG_ZLIB_VERNUM 0' >> $OVB/external/libpng/pnglibconf.h
fi
grep -n "PNG_ARM_NEON_OPT" $OVB/external/libpng/pnglibconf.h | head -2

echo "=== [7] freetype trimmed module table ==="
FTM=$OVB/external/freetype/freetype/include/freetype/config/ftmodule.h
if [ ! -f $FTM ] || [ -f $FTM.orig ]; then :; fi
cat > $FTM <<'EOF'
/* trimmed module table for the vela simulator firmware (no demos/extra) */
FT_USE_MODULE( FT_Module_Class, autofit_module_class )
FT_USE_MODULE( FT_Driver_ClassRec, tt_driver_class )
FT_USE_MODULE( FT_Driver_ClassRec, cff_driver_class )
FT_USE_MODULE( FT_Module_Class, psaux_module_class )
FT_USE_MODULE( FT_Module_Class, psnames_module_class )
FT_USE_MODULE( FT_Module_Class, pshinter_module_class )
FT_USE_MODULE( FT_Renderer_Class, ft_smooth_renderer_class )
FT_USE_MODULE( FT_Module_Class, sfnt_module_class )
EOF
echo "ftmodule written"

echo "=== [8] yoga .cxx shadow copies ==="
if [ ! -d $OVB/external/yoga/yoga_cxx ]; then
  (cd $OVB/external/yoga && find yoga -name "*.cpp" | while read f; do
     dst="yoga_cxx/${f%.cpp}.cxx"; mkdir -p "$(dirname "$dst")"; cp "$f" "$dst"
   done
   find yoga -name "*.h" | while read f; do
     dst="yoga_cxx/${f}"; mkdir -p "$(dirname "$dst")"; cp "$f" "$dst"
   done)
fi
echo "yoga_cxx files: $(find $OVB/external/yoga/yoga_cxx -name '*.cxx' 2>/dev/null | wc -l)"

echo "=== [9] zlib sources preset ==="
ZD=$OVB/apps/system/zlib
if [ ! -d $ZD/zlib ]; then
  (cd $ZD && [ -f zlib13.zip ] || curl -sO -L https://github.com/madler/zlib/releases/download/v1.3/zlib13.zip && \
   unzip -oq zlib13.zip && mv zlib-1.3 zlib && touch zlib/.git)
fi
ls $ZD/zlib/zlib.h >/dev/null 2>&1 && echo "zlib preset OK" || echo "ZLIB PRESET FAILED"

echo "APPLY_ALL DONE"
