package com.vela.simulator;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Boots the Vela VM: qemu-system-arm -M virt with virtio-gpu + virtio-blk. */
public class QemuRunner {

    public interface Listener {
        void onStarted(int vncDisplay, int serialPort);
        void onExit(int code, String tail);
        void onLine(String s);
    }

    private Process proc;
    private final List<String> tail = new ArrayList<>();
    private int vncDisplay = -1, serialPort = -1;
    private volatile Listener cb;

    /** Launches qemu; watcher thread reports lifecycle. Returns immediately. */
    public synchronized void start(File home, int vncDisplay, int serialPort, final Listener cb) throws IOException {
        stop();
        this.cb = cb;
        this.vncDisplay = vncDisplay;
        this.serialPort = serialPort;

        File bin = Constants.qemuBin(home);
        File lib = Constants.qemuLib(home);
        File share = Constants.qemuShare(home);
        File fbin = Constants.fwBin(home);
        File img = Constants.fwData(home);
        if (!bin.isFile()) throw new IOException("qemu-system-arm 不存在，请先安装运行环境");
        if (!fbin.isFile()) throw new IOException("固件 nuttx.bin 不存在，请先刷入固件");

        List<String> cmd = new ArrayList<>();
        cmd.add(bin.getAbsolutePath());
        cmd.add("-nodefaults");
        cmd.add("-M"); cmd.add("virt,gic-version=2");
        cmd.add("-cpu"); cmd.add("cortex-a15");
        cmd.add("-smp"); cmd.add("1");
        cmd.add("-m");  cmd.add("512M");
        cmd.add("-device"); cmd.add("loader,file=" + fbin.getAbsolutePath() + ",addr=0x600000");
        cmd.add("-device"); cmd.add("loader,addr=0x6002e0,cpu-num=0");
        cmd.add("-drive");  cmd.add("file=" + img.getAbsolutePath() + ",if=none,format=raw,id=hd0");
        cmd.add("-device"); cmd.add("virtio-blk-device,drive=hd0");
        cmd.add("-device"); cmd.add("virtio-gpu-device");
        cmd.add("-display"); cmd.add("none");
        cmd.add("-vnc");    cmd.add("127.0.0.1:" + vncDisplay);
        cmd.add("-serial"); cmd.add("tcp:127.0.0.1:" + serialPort + ",server,nowait");
        cmd.add("-monitor"); cmd.add("none");
        if (share.isDirectory()) { cmd.add("-L"); cmd.add(share.getAbsolutePath()); }

        final ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("LD_LIBRARY_PATH", lib.getAbsolutePath());
        pb.environment().put("TMPDIR", home.getAbsolutePath() + "/cache");
        pb.environment().put("HOME", home.getAbsolutePath());
        new File(home, "cache").mkdirs();
        pb.redirectErrorStream(true);

        synchronized (tail) { tail.clear(); }
        proc = pb.start();
        final Process p = proc;
        log("qemu 已启动 pid=" + p.hashCode());

        Thread t = new Thread(() -> {
            StringBuilder line = new StringBuilder();
            byte[] b = new byte[4096];
            try (java.io.InputStream in = p.getInputStream()) {
                int r;
                while ((r = in.read(b)) > 0) {
                    for (int i = 0; i < r; i++) {
                        char ch = (char) (b[i] & 0xff);
                        if (ch == '\n') {
                            String s = line.toString(); line.setLength(0);
                            synchronized (tail) {
                                tail.add(s);
                                if (tail.size() > 40) tail.remove(0);
                            }
                            log("[qemu] " + s);
                        } else if (ch != '\r') line.append(ch);
                    }
                }
            } catch (Exception ignore) {}
            try {
                int code = p.waitFor();
                if (cb != null) cb.onExit(code, tailSnapshot());
            } catch (InterruptedException ignore) {}
        }, "qemu-watch");
        t.setDaemon(true);
        t.start();
    }

    public synchronized boolean isRunning() {
        return proc != null && proc.isAlive();
    }

    public synchronized void stop() {
        Process p = proc;
        if (p != null) {
            try { p.destroy(); } catch (Exception ignore) {}
            try { p.waitFor(); } catch (InterruptedException ignore) {}
            proc = null;
        }
    }

    public int getVncDisplay() { return vncDisplay; }
    public int getSerialPort() { return serialPort; }

    private String tailSnapshot() {
        synchronized (tail) {
            StringBuilder sb = new StringBuilder();
            for (String s : tail) sb.append(s).append('\n');
            return sb.toString();
        }
    }

    private void log(String s) { if (cb != null) cb.onLine(s); }
}
