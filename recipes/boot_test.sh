#!/bin/bash
# Automated boot test: boots the vapp firmware, drives NSH over a serial
# pipe, mounts the FAT data image, launches the vapp runtime and captures
# console log + framebuffer screendump.
#
#   boot_test.sh <nuttx.elf> <data.img> <logdir> [seconds]
set -e

ELF=$1
IMG=$2
OUTDIR=$3
SECS=${4:-40}
PKG=${5:-com.vela.demo}

QEMU=/tmp/qemu/usr/bin/qemu-system-arm
export LD_LIBRARY_PATH=/tmp/qemu/usr/lib/x86_64-linux-gnu:/tmp/qemu/usr/lib/x86_64-linux-gnu/qemu
export QEMU_MODULE_DIR=/tmp/qemu/usr/lib/x86_64-linux-gnu/qemu

mkdir -p "$OUTDIR"
LOG=$OUTDIR/console.log
rm -f "$LOG" "$OUTDIR"/nsh.in "$OUTDIR"/nsh.out "$OUTDIR"/monitor.in "$OUTDIR"/monitor.out "$OUTDIR"/frame.ppm
mkfifo "$OUTDIR"/nsh.in "$OUTDIR"/nsh.out "$OUTDIR"/monitor.in "$OUTDIR"/monitor.out 2>/dev/null || true

# drain monitor output to avoid blocking
cat "$OUTDIR"/monitor.out > /dev/null 2>&1 &
MON_DRAIN=$!

# start qemu
timeout $SECS "$QEMU" \
  -nodefaults \
  -machine virt,gic-version=2 \
  -cpu cortex-a15 \
  -smp 1 -m 512M \
  -device loader,file="$ELF",addr=0x600000 \
  -device loader,addr=0x6002e0,cpu-num=0 \
  -drive file="$IMG",if=none,format=raw,id=hd0 \
  -device virtio-blk-device,drive=hd0 \
  -device virtio-gpu-device \
  -display none \
  -monitor pipe:"$OUTDIR"/monitor \
  -serial pipe:"$OUTDIR"/nsh \
  > "$OUTDIR"/qemu.err 2>&1 &
QPID=$!

# capture serial output
cat "$OUTDIR"/nsh.out > "$LOG" 2>/dev/null &
CAT_PID=$!

# keep the fifo open
exec 3>"$OUTDIR"/nsh.in

sleep 6

{
  echo ""
  echo "cat /proc/mounts"
  sleep 2
  echo "mount -t vfat /dev/virtblk0 /data"
  sleep 3
  echo "cat /proc/mounts"
  sleep 2
  echo "ls /data"
  sleep 2
  echo "ls /data/VAPPS"
  sleep 2
  echo "ls /data/VAPPS/com.vela.demo"
  sleep 2
  echo "cat /data/VAPPS/com.vela.demo/manifest.json"
  sleep 2
  echo "ls /data/vapps/com.vela.demo"
  sleep 2
  echo "ls /data/resource/package"
  sleep 2
  echo "cat /data/RESOURCE/PACKAGE/com.vela.demo.rpk"
  sleep 3
  echo "vapp hap://app/$PKG"
  sleep 14
  echo "ls /data/VAPPS"
  sleep 2
  echo "ls /data/VAPPS/com.vela.demo"
  sleep 2
  echo "ls /data/VAPPS/com.vela.demo/app.js"
  sleep 2
  echo "screendump placeholder"
} >&3 2>/dev/null &

sleep 26
echo "screendump $OUTDIR/frame.ppm" > "$OUTDIR"/monitor.in &
sleep 2
echo "quit" > "$OUTDIR"/monitor.in 2>/dev/null &
sleep 2

kill $QPID 2>/dev/null || true
kill $CAT_PID $MON_DRAIN 2>/dev/null || true
wait 2>/dev/null || true

echo "=== console tail ==="
tail -40 "$LOG" 2>/dev/null || echo "(no console output)"
ls -la "$OUTDIR"/frame.ppm 2>/dev/null || echo "(no screendump)"
