#!/bin/bash
# replay_config.sh — full config replay: configure -> expand -> fragment -> verify
set -e
N=/home/z/ov/nuttx
export PATH=/home/z/.venv/bin:/home/z/ov/tools-prefix/usr/bin:$PATH
export LD_LIBRARY_PATH=/home/z/ov/tools-prefix/usr/lib/x86_64-linux-gnu:$LD_LIBRARY_PATH
export BINDIR=$N APPSBINDIR=/home/z/ov/apps APPSDIR=/home/z/ov/apps EXTERNALDIR=dummy
export SRCTREE=$N ARCH=arm SRCARCH=arm

cd $N

echo "=== [1] configure qemu-armv7a:nsh ==="
if [ ! -f .config ]; then
  tools/configure.sh -l qemu-armv7a:nsh 2>&1 | tail -3
else
  echo ".config exists, skip configure"
fi

echo "=== [2] expand via olddefconfig ==="
olddefconfig 2>&1 | tail -2 || make -s olddefconfig 2>&1 | tail -2
grep -c "^CONFIG_" .config || true

echo "=== [3] fragment apply (3-pass) ==="
/home/z/.venv/bin/python /home/z/my-project/scripts/patches/frag_apply.py \
  /home/z/my-project/scripts/vapp_fragment.txt 3

echo "=== [4] final olddefconfig settle ==="
olddefconfig 2>&1 | tail -1 || true

echo "=== [5] verify again after settle ==="
/home/z/.venv/bin/python /home/z/my-project/scripts/patches/frag_apply.py \
  /home/z/my-project/scripts/vapp_fragment.txt 1

echo "=== [6] key symbols ==="
grep -E "^CONFIG_(QUICKAPP|USE_QUICKJS|QUICKAPP_VAPP|GRAPHICS_LVGL|LIBUV|INTERPRETERS_QUICKJS|LIB_PNG|LIB_FREETYPE|LIB_YOGA|PROTOBUF_C|LIB_CURL|UTILS_CURL|LIB_ZLIB|LIBCXX|LIBCXXABI|TLS_NELEM|DEVICE_TREE|DRIVERS_VIRTIO|FS_FAT|FAT_LFN|QUICKAPP_RPK_DIR|ARCH_SETJMP_H|CXX_STANDARD|LIBCXX_VERSION)=" .config
echo "CONFIG REPLAY DONE"
