#!/bin/bash
# Idempotent resumable firmware build. Run repeatedly until "BUILD OK".
# Foreground-friendly: exits before the 10-min sandbox timeout.
source /home/z/my-project/scripts/build-env.sh

export LD_LIBRARY_PATH=/home/z/ov/tools-prefix/usr/lib/x86_64-linux-gnu:$LD_LIBRARY_PATH

# ensure kconfig shims on PATH (kconfiglib based)
export PATH=/home/z/.local/bin:/home/z/.venv/bin:$PATH

NPROC=$(nproc)
[ "$NPROC" -gt 2 ] && NPROC=2

echo "=== make -j$NPROC (resumable, 560s budget) ==="
timeout 560 make -j$NPROC 2>&1 | tail -35
RC=${PIPESTATUS[0]}
echo "=== make exit: $RC ==="
if [ "$RC" -eq 0 ] && [ -f nuttx.bin ]; then
  echo "BUILD OK"
  ls -la nuttx nuttx.bin nuttx.map 2>/dev/null
elif [ "$RC" -eq 124 ]; then
  echo "BUILD TIMEOUT — rerun this script to continue"
else
  echo "BUILD FAILED — see errors above"
  # dump the first error for quick diagnosis
  make 2>&1 | grep -m5 -E "Error|error:" || true
fi
