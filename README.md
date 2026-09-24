# XiaomiVelaSimulator

An Android "Vela simulator" that runs the OpenVela/NuttX vapp firmware under
QEMU inside Termux, loads real QuickApp `.rpk` packages and renders them onto
a VNC display (virtio-gpu + LVGL).

- **APK**: prebuilt in GitHub Releases (`XiaomiVelaSimulator-v2.0-vapp.apk`).
  The firmware is bundled inside the APK (`assets/images/`) and can also be
  fetched from Releases.
- **Runtime**: the app resolves `qemu-system-arm-headless` (aarch64) from the
  Termux mirrors on first launch (mirror race: SJTU/TUNA/BFSU/termux.dev),
  unpacks the deb closure into `qemu-prefix/` and boots the VM with:

      qemu-system-arm -nodefaults -M virt,gic-version=2 -cpu cortex-a15 \
        -smp 1 -m 512M \
        -device loader,file=images/nuttx.bin,addr=0x600000 \
        -device loader,addr=0x6002e0,cpu-num=0 \
        -drive file=images/data.img,if=none,format=raw,id=hd0 \
        -device virtio-blk-device,drive=hd0 \
        -device virtio-gpu-device -display none \
        -vnc 127.0.0.1:N -serial tcp:127.0.0.1:P,server,nowait

- **End-to-end verified**: NSH boots, mounts the FAT data image, unpacks
  `com.vela.demo.rpk` (zlib inflate, chunked FAT reads), runs the QuickJS
  engine (ESM + factory convention of aiot-toolkit bundles), renders the
  virtual DOM tree onto LVGL/virtio-gpu and keeps JS timers alive.

## Repository layout
- `vela-apk/`      Android app sources (Termux runtime bootstrap, VNC, serial console)
- `recipes/`       Full firmware build/replay scripts for an x86_64 Debian sandbox
- `patches/`       Every source modification, persisted (apply_all.sh + presets.sh)
- `docs/`          Build notes

## Firmware build (x86_64 Debian, no sudo)
    patches/apply_all.sh      # replay all tree modifications
    patches/presets.sh        # libcxx/libcxxabi 17.0.6 + openamp pinned + patches
    recipes/replay_config.sh          # configure + 86-symbol fragment (kconfiglib)
    make -j2                          # in /home/z/ov/nuttx

## GitHub release assets
    XiaomiVelaSimulator-v2.0-vapp.apk   signed APK (firmware bundled)
    vela-firmware-v2.0-vapp.zip         images/nuttx.bin + images/data.img + version
