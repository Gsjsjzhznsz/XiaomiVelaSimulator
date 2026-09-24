#!/bin/bash
# Infra restore: toolchain + mtools + QEMU runtime (idempotent, resumable)
# Run repeatedly until "INFRA OK".
DL=/home/z/dl
TP=/home/z/ov/tools-prefix
mkdir -p $DL $TP/usr /tmp/mt /tmp/qemu/usr

fetch() { # fetch <pkgname> — apt download if no glob match
  local p=$1
  if ls $DL/${p}_*.deb >/dev/null 2>&1; then return 0; fi
  (cd $DL && apt-get download "$p" 2>&1 | tail -1) || { echo "DOWNLOAD-FAIL $p"; return 1; }
}

unpack() { # unpack <pkgname> <dest>
  local p=$1 d=$2
  for f in $DL/${p}_*.deb; do
    [ -f "$f" ] && dpkg -x "$f" "$d" && echo "unpacked $(basename $f) -> $d"
  done
}

# ---- 1. downloads ----
MISS=0
for p in gcc-arm-none-eabi binutils-arm-none-eabi libnewlib-arm-none-eabi \
         mtools libcapstone4 libpmem1 libkmod2 libslirp0 liburing2 libfdt1 \
         libpixman-1-0 libseccomp2 libnuma1 qemu-system-common qemu-system-data ipxe-qemu; do
  fetch $p || MISS=$((MISS+1))
done
echo "download misses: $MISS"

# fallback names (trixie renames)
for alt in "libcapstone5" "libcapstone4" "libpmem1" "libdaxctl1"; do fetch $alt || true; done

# ---- 2. unpack toolchain ----
unpack gcc-arm-none-eabi $TP
unpack binutils-arm-none-eabi $TP
unpack libnewlib-arm-none-eabi $TP
unpack libnewlib-dev $TP
unpack libstdc++-arm-none-eabi-dev $TP 2>/dev/null
# libstdc++ deb survived in my-project root
[ -f /home/z/my-project/libstdc++-arm-none-eabi-dev_*.deb ] && dpkg -x /home/z/my-project/libstdc++-arm-none-eabi-dev_*.deb $TP && echo "unpacked libstdc++-dev"

# mtools -> /tmp/mt
unpack mtools /tmp/mt

# ---- 3. unpack QEMU + deps ----
dpkg -x /home/z/my-project/qemu-system-arm_*.deb /tmp/qemu && echo "unpacked qemu-system-arm"
for p in qemu-system-common qemu-system-data ipxe-qemu libcapstone4 libcapstone5 \
         libpmem1 libdaxctl1 libkmod2 libslirp0 liburing2 libfdt1 libpixman-1-0 \
         libseccomp2 libnuma1; do
  unpack $p /tmp/qemu
done
# merged-usr split: merge /lib into /usr/lib
if [ -d /tmp/qemu/lib/x86_64-linux-gnu ]; then
  cp -an /tmp/qemu/lib/x86_64-linux-gnu/* /tmp/qemu/usr/lib/x86_64-linux-gnu/ 2>/dev/null || true
fi

# ---- 4. kconfiglib into venv ----
if ! /home/z/.venv/bin/python -c "import kconfiglib" 2>/dev/null; then
  /home/z/.venv/bin/pip install -q kconfiglib && echo "kconfiglib installed"
else
  echo "kconfiglib already present"
fi

# ---- 5. verify ----
echo "=== verify ==="
$TP/usr/bin/arm-none-eabi-gcc --version 2>/dev/null | head -1 || echo "MISSING gcc"
/tmp/mt/usr/bin/mformat --version 2>/dev/null | head -1 || echo "MISSING mformat"
LD_LIBRARY_PATH=/tmp/qemu/usr/lib/x86_64-linux-gnu:/tmp/qemu/usr/lib/x86_64-linux-gnu/qemu \
  ldd /tmp/qemu/usr/bin/qemu-system-arm 2>/dev/null | grep "not found" | sort -u || true
ls /tmp/qemu/usr/lib/x86_64-linux-gnu/qemu/ 2>/dev/null | grep -iE "virtio-gpu|roms" | head -5
/home/z/.venv/bin/python -c "import kconfiglib; print('kconfiglib OK', kconfiglib.__version__)" 2>/dev/null || echo "MISSING kconfiglib"
echo "INFRA PASS (rerun if anything above is MISSING)"
