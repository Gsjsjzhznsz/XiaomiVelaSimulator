package com.vela.simulator;

import java.io.File;

/** Global paths & knobs. All runtime state lives under context.getFilesDir(). */
public final class Constants {

    // ---- Termux mirror candidates (raced in order; first live one wins) ----
    public static final String[] MIRRORS = {
            "https://mirror.sjtu.edu.cn/termux/apt/termux-main",
            "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main",
            "https://mirrors.bfsu.edu.cn/termux/apt/termux-main",
            "https://packages.termux.dev/apt/termux-main",
    };
    public static final String DIST = "dists/stable/main/binary-aarch64/Packages";

    // Root packages the runtime needs (dependency closure resolved at install time)
    public static final String[] RUNTIME_ROOTS = { "qemu-system-arm-headless" };

    // ---- Firmware (bundled in APK assets; also hosted on GitHub Releases) ----
    public static final String FW_ASSET_DIR  = "images";
    public static final String FW_VERSION_AS = "images/firmware.version";
    public static final String FW_RELEASE_BASE =
            "https://github.com/vela-simulator/XiaomiVelaSimulator/releases/latest/download";

    public static final String KERN_BIN = "nuttx.bin";   // raw bin for -device loader (ELF loader silently fails on QEMU 10)
    public static final String DATA_IMG = "data.img";

    // ---- VM ports ----
    public static final int VNC_BASE_PORT   = 5;     // display :N  -> tcp 5900+N
    public static final int SERIAL_PORT_MIN = 22000; // qemu -serial tcp server

    // ---- Auto boot script (sent after NSH prompt detected) ----
    public static final String[] AUTO_BOOT = {
            "mount -t vfat /dev/virtblk0 /data",
            "ls /data/resource/package",
            "vapp hap://app/com.vela.demo",
    };

    public static File qemuBin(File home)  { return new File(home, "qemu-prefix/usr/bin/qemu-system-arm"); }
    public static File qemuLib(File home)  { return new File(home, "qemu-prefix/usr/lib"); }
    public static File qemuShare(File home){ return new File(home, "qemu-prefix/usr/share/qemu"); }
    public static File imagesDir(File home){ return new File(home, "images"); }
    public static File fwBin(File home)    { return new File(imagesDir(home), KERN_BIN); }
    public static File fwData(File home)   { return new File(imagesDir(home), DATA_IMG); }
    public static File debCache(File home) { return new File(home, "deb-cache"); }

    private Constants() {}
}
