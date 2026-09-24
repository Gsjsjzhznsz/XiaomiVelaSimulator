#!/bin/bash
# presets.sh — download & patch the big vendored sources: libcxx/libcxxabi
# (LLVM 17.0.6) and libmetal/open-amp (openamp fixed commits). Idempotent.
set -u
N=/home/z/ov/nuttx
cd $N || exit 1

fetch() { # fetch <url> <outfile>
  local url=$1 out=$2
  [ -f "$out" ] && return 0
  curl -sL --retry 3 -o "$out" "$url" && echo "fetched $(basename $out)" || { echo "FETCH FAIL $url"; return 1; }
}

apply_patch() { # apply_patch <workdir> <patchfile> [-pN]  (patch(1) based)
  local d=$1 p=$2 pn=${3:--p1}
  if patch -R -d "$d" --dry-run -s $pn < "$p" 2>/dev/null; then
    echo "already applied: $(basename $p)"
  else
    if patch -d "$d" -N -s $pn < "$p" >/dev/null 2>&1; then
      echo "applied: $(basename $p)"
    else
      echo "APPLY FAIL: $(basename $p)"
      return 1
    fi
  fi
}

patch_apply_dir() { # patch_apply_dir <workdir> <patchfile> <strip>
  local d=$1 p=$2 pn=$3
  if patch -R -d "$d" --dry-run -s $pn < "$p" >/dev/null 2>&1; then
    echo "already applied: $(basename $p)"
  else
    if patch -d "$d" -N -s $pn < "$p" >/dev/null 2>&1; then
      echo "applied: $(basename $p)"
    else
      echo "PATCH APPLY FAIL: $(basename $p)"
      return 1
    fi
  fi
}

norm_openamp_patch() { # normalize asymmetric git patches to standard a/ b/
  local p=$1 repo=$2
  sed -E "s|^--- a/|--- a/${repo}/|; s|^\\+\\+\\+ ${repo}/|+++ b/${repo}/|; s|^diff --git a/(.*) b/${repo}/(.*)|diff --git a/${repo}/\\1 b/${repo}/\\2|" "$p"
}

echo "=== [A] libcxx tarball + unpack ==="
LXV=17.0.6
LXD=$N/libs/libxx/libcxx
fetch https://github.com/llvm/llvm-project/releases/download/llvmorg-$LXV/libcxx-$LXV.src.tar.xz $LXD/libcxx-$LXV.src.tar.xz || exit 1
if [ ! -d $LXD/libcxx ]; then
  tar -xf $LXD/libcxx-$LXV.src.tar.xz -C $LXD --exclude libcxx-$LXV.src/test/std/pstl
  mv $LXD/libcxx-$LXV.src $LXD/libcxx
  echo "libcxx unpacked"
fi

echo "=== [B] libcxxabi tarball + unpack ==="
LXAD=$N/libs/libxx/libcxxabi
fetch https://github.com/llvm/llvm-project/releases/download/llvmorg-$LXV/libcxxabi-$LXV.src.tar.xz $LXAD/libcxxabi-$LXV.src.tar.xz || exit 1
if [ ! -d $LXAD/libcxxabi ]; then
  tar -xf $LXAD/libcxxabi-$LXV.src.tar.xz -C $LXAD
  mv $LXAD/libcxxabi-$LXV.src $LXAD/libcxxabi
  echo "libcxxabi unpacked"
fi

patch_apply_dir() { # patch_apply_dir <workdir> <patchfile> <strip>
  local d=$1 p=$2 pn=$3
  if patch -R -d "$d" --dry-run -s $pn < "$p" >/dev/null 2>&1; then
    echo "already applied: $(basename $p)"
  else
    if patch -d "$d" -N -s $pn < "$p" >/dev/null 2>&1; then
      echo "applied: $(basename $p)"
    else
      echo "PATCH APPLY FAIL: $(basename $p)"
      return 1
    fi
  fi
}

norm_openamp_patch() { # normalize asymmetric git patches to standard a/ b/
  local p=$1 repo=$2
  sed -E "s|^--- a/|--- a/${repo}/|; s|^\\+\\+\\+ ${repo}/|+++ b/${repo}/|; s|^diff --git a/(.*) b/${repo}/(.*)|diff --git a/${repo}/\\1 b/${repo}/\\2|" "$p"
}

echo "=== [C] libcxx upstream patches ==="
# patch paths are libcxx/... or include/... with NO a/b prefixes; the fork
# unpacks sources to libs/libxx/libcxx/libcxx, so apply -p0 -d $LXD.
for p in $LXD/0*.patch; do
  [ -f "$p" ] || continue
  patch_apply_dir $LXD "$p" -p0
done
# mbstate_t.patch targets the OLD layout path libs/libxx/libcxx/include/...
# translate to libcxx/include/... so it lands at $LXD/libcxx/include/...
sed 's|libs/libxx/libcxx/|libcxx/|' $LXD/mbstate_t.patch > /tmp/mbstate2.patch
patch_apply_dir $LXD /tmp/mbstate2.patch -p0

echo "=== [D] libcxxabi upstream patches ==="
for p in $LXAD/0*.patch; do
  [ -f "$p" ] || continue
  patch_apply_dir $LXAD "$p" -p0
done

# .git markers: the Make.defs unpack+patch blocks are gated on
# libcxx/libcxx/.git — create them so the build uses our pre-patched trees
touch $LXD/libcxx/.git $LXAD/libcxxabi/.git
echo "git markers set (Make.defs download/patch steps short-circuited)"

echo "=== [E] openamp/libmetal sources ==="
OAD=$N/openamp
mkdir -p $OAD/libmetal $OAD/open-amp
if [ ! -f $OAD/libmetal/.patched ]; then
  (cd $OAD/libmetal && git init -q 2>/dev/null; git fetch --depth 1 -q https://github.com/OpenAMP/libmetal a4bce3507502a7eb9e29bafe0eb174ed5c4316e9 && git checkout -q --detach FETCH_HEAD && echo "libmetal @ a4bce35") || { echo "LIBMETAL CLONE FAIL"; exit 1; }
fi
if [ ! -f $OAD/open-amp/.patched ]; then
  (cd $OAD/open-amp && git init -q 2>/dev/null; git fetch --depth 1 -q https://github.com/OpenAMP/open-amp c468328487a1e0596307a5ef7172756819e15745 && git checkout -q --detach FETCH_HEAD && echo "open-amp @ c468328") || { echo "OPENAMP CLONE FAIL"; exit 1; }
fi

echo "=== [F] openamp patch split (libmetal vs open-amp) ==="
# The fork's patches are asymmetric git diffs (a/ = repo-relative; the
# repo name only appears on the b-side, sometimes WITHOUT the b/ prefix).
# Strategy: normalize to standard a/<repo>/ b/<repo>/, classify by the
# b-side path, then apply ALL of the repo's patches in one strict pass
# (no -N, abort on any failure) guarded by the .patched marker.
if [ -f $OAD/libmetal/.patched ] && [ -f $OAD/open-amp/.patched ]; then
  echo "openamp patches already applied (marker)"
else
  FAILP=0
  for p in $OAD/0*.patch; do
    [ -f "$p" ] || continue
    if grep -m1 "^diff --git" "$p" | grep -qE "b/libmetal/| libmetal/"; then
      repo=libmetal
    else
      repo=open-amp
    fi
    norm_openamp_patch "$p" $repo > /tmp/norm-$(basename $p)
    if patch -d $OAD -p1 -s < /tmp/norm-$(basename $p); then
      echo "applied: $(basename $p) -> $repo"
    else
      echo "PATCH APPLY FAIL: $(basename $p) -> $repo"; FAILP=1
    fi
  done
  [ $FAILP -eq 0 ] || { echo "OPENAMP PATCHES INCOMPLETE"; exit 1; }
  touch $OAD/libmetal/.patched $OAD/open-amp/.patched
fi

echo "=== [G] libcxx custom (GCC14 compat) ==="
STDDEF=$LXD/libcxx/include/stddef.h
if [ -f "$STDDEF" ] && ! grep -q "__need_.*cleared" "$STDDEF"; then
  python3 - "$STDDEF" <<'EOF'
import sys
p = sys.argv[1]
s = open(p).read()
old = "#include_next <stddef.h>"
new = ("#  undef __need_ptrdiff_t\n"
       "#  undef __need_size_t\n"
       "#  undef __need_wchar_t\n"
       "#  undef __need_NULL\n"
       "#  undef __need_wint_t\n"
       "#  include_next <stddef.h> /* full definitions: __need_* cleared */")
if old in s:
    s = s.replace(old, new, 1)
    open(p, "w").write(s)
    print("stddef.h __need fix applied")
else:
    print("stddef.h pattern not found (skipped)")
EOF
fi

# minimal uchar.h: newlib arm-none-eabi lacks it; libc++ __mbstate_t.h
# reaches for it via include_next. Mirror newlib's _MBSTATE_T guard so
# wchar.h and uchar.h agree on the mbstate_t definition.
UCHAR=$N/include/uchar.h
if [ ! -f "$UCHAR" ]; then
  cat > $UCHAR <<'EOF'
  /* Minimal <uchar.h> for NuttX (required by libc++ __mbstate_t.h).
   * Mirrors newlib's _MBSTATE_T guard so wchar.h and uchar.h agree. */
  #ifndef __UCHAR_H
  #define __UCHAR_H

  #ifndef __cplusplus
  typedef unsigned short char16_t; /* actually uint_least16_t */
  typedef unsigned int   char32_t; /* actually uint_least32_t */
  #endif

  #ifndef _MBSTATE_T
  #define _MBSTATE_T
  #if defined(__has_include) && __has_include(<sys/_types.h>)
  #  include <sys/_types.h>
  typedef _mbstate_t mbstate_t;
  #define _MBSTATE_T_TYPE_OK 1
  #endif
  #ifndef _MBSTATE_T_TYPE_OK
  typedef struct __mbstate_t
  {
    char __mbstate8[128];
  } __mbstate_t;
  typedef __mbstate_t mbstate_t;
  #endif
  #endif /* _MBSTATE_T */

  #endif /* __UCHAR_H */
EOF
  echo "uchar.h created"
fi

echo "=== [H] openamp custom fixes (fork API gaps) ==="
IO=$N/openamp/libmetal/lib/system/nuttx/io.c
if [ -f "$IO" ] && ! grep -q "up_addrenv_va_to_pa(v)" "$IO"; then
  python3 - "$IO" <<'PYI'
import sys
p = sys.argv[1]
s = open(p).read()
anchor = "#include <nuttx/arch.h>"
add = ("\n/* FLAT build: PA == VA (fork arm addrenv lacks FLAT converters). */\n"
       "#ifndef up_addrenv_va_to_pa\n"
       "#  define up_addrenv_va_to_pa(v) ((uint64_t)(uintptr_t)(v))\n"
       "#endif\n"
       "#ifndef up_addrenv_pa_to_va\n"
       "#  define up_addrenv_pa_to_va(p) ((uintptr_t)(p))\n"
       "#endif\n")
if anchor in s:
    s = s.replace(anchor, anchor + add, 1)
    open(p, "w").write(s)
    print("libmetal io.c identity added")
else:
    print("io.c anchor missing!")
PYI
fi

echo "PRESETS DONE"
