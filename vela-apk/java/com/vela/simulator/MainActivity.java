package com.vela.simulator;

import android.app.Activity;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity implements RuntimeInstaller.Progress, FirmwareInstaller.Progress {

    private TextView stRuntime, stFirmware, stVm, console;
    private Button btnRuntime, btnFirmware, btnStart, btnStop;
    private CheckBox cbAutoVapp;
    private VncView vnc;
    private EditText cmd;
    private SerialClient serial;

    private RuntimeInstaller installer;
    private FirmwareInstaller firmware;
    private final QemuRunner qemu = new QemuRunner();

    private int serialPort = Constants.SERIAL_PORT_MIN;
    private int vncDisplay = Constants.VNC_BASE_PORT;

    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final AtomicBoolean autoSent = new AtomicBoolean(false);

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        stRuntime = findViewById(R.id.st_runtime);
        stFirmware = findViewById(R.id.st_firmware);
        stVm = findViewById(R.id.st_vm);
        console = findViewById(R.id.console);
        console.setMovementMethod(new ScrollingMovementMethod());
        btnRuntime = findViewById(R.id.btn_runtime);
        btnFirmware = findViewById(R.id.btn_firmware);
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        cbAutoVapp = findViewById(R.id.cb_autovapp);
        vnc = findViewById(R.id.vnc);
        cmd = findViewById(R.id.cmd);

        installer = new RuntimeInstaller(this, this);
        firmware = new FirmwareInstaller(this, this);
        serial = new SerialClient();

        btnRuntime.setOnClickListener(v -> asyncTask("安装运行环境", () -> {
            boolean ok = installer.install();
            runOnUiThread(() -> refreshStatus());
            return ok ? "运行环境安装完成" : "安装失败";
        }));

        btnFirmware.setOnClickListener(v -> asyncTask("刷入固件", () -> {
            firmware.tryUpdateFromGithub();          // best effort online refresh
            firmware.installFromAssets(this);        // fallback / first install
            runOnUiThread(() -> refreshStatus());
            return "固件就绪";
        }));

        btnStart.setOnClickListener(v -> startVm());
        btnStop.setOnClickListener(v -> stopVm());
        findViewById(R.id.btn_send).setOnClickListener(v -> sendCmd());

        refreshStatus();
        log("[vela] 欢迎使用 Xiaomi Vela 模拟器。步骤：安装运行环境 → 刷入固件 → 启动虚拟机");
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        qemu.stop();
        serial.disconnect();
    }

    // ---------------- actions ----------------

    private void startVm() {
        if (!Constants.qemuBin(getFilesDir()).isFile()) {
            toast("请先一键下载运行环境"); return;
        }
        if (!firmware.isInstalled()) {
            toast("请先刷入固件"); return;
        }
        try {
            autoSent.set(false);
            vncDisplay = Constants.VNC_BASE_PORT;
            serialPort = nextFreePort();
            qemu.start(getFilesDir(), vncDisplay, serialPort, new QemuRunner.Listener() {
                @Override public void onStarted(int vncD, int serP) {}
                @Override public void onExit(int code, String t) {
                    runOnUiThread(() -> {
                        log("[vela] 虚拟机退出 code=" + code);
                        refreshStatus();
                        vnc.disconnect();
                    });
                }
                @Override public void onLine(String s) { log(s); }
            });
            log("[vela] QEMU 启动中... vnc=:" + (5900 + vncDisplay) + " serial=" + serialPort);
            vnc.connect(vncDisplay);
            connectSerial();
            refreshStatus();
        } catch (Exception e) {
            toast("启动失败: " + e.getMessage());
            log("[vela] 启动失败: " + e.getMessage());
        }
    }

    private void stopVm() {
        qemu.stop();
        serial.disconnect();
        vnc.disconnect();
        log("[vela] 虚拟机已停止");
        refreshStatus();
    }

    private void connectSerial() {
        serial.connect(serialPort, new SerialClient.Listener() {
            @Override public void onData(String text) {
                runOnUiThread(() -> {
                    log(text);
                    if (cbAutoVapp.isChecked() && !autoSent.get() && text.contains("nsh>")) {
                        autoSent.set(true);
                        sendAutoBoot();
                    }
                });
            }
            @Override public void onClosed(String why) { runOnUiThread(() -> log("[serial] " + why)); }
        });
    }

    private void sendAutoBoot() {
        log("[vela] 检测到 NSH，自动执行 vapp 启动序列");
        new Thread(() -> {
            try {
                for (int i = 0; i < Constants.AUTO_BOOT.length; i++) {
                    Thread.sleep(2500);
                    String c = Constants.AUTO_BOOT[i];
                    serial.sendLine(c);
                    final String line = c;
                    runOnUiThread(() -> log("[auto] " + line));
                }
            } catch (InterruptedException ignore) {}
        }, "autoboot").start();
    }

    private void sendCmd() {
        String c = cmd.getText().toString().trim();
        if (c.isEmpty()) return;
        cmd.setText("");
        serial.sendLine(c);
        log("> " + c);
    }

    private int nextFreePort() {
        return serialPort + ((int) (System.currentTimeMillis() / 1000) % 7) + 1;
    }

    // ---------------- ui helpers ----------------

    private void refreshStatus() {
        boolean rt = Constants.qemuBin(getFilesDir()).isFile();
        stRuntime.setText(rt ? "● QEMU 运行环境：已安装 ✓" : "● QEMU 运行环境：未安装");
        stRuntime.setTextColor(rt ? 0xff9fdf9f : 0xffcccccc);

        String fv = firmware.installedVersion();
        boolean fw = firmware.isInstalled();
        stFirmware.setText(fw ? "● Vela 固件：已刷入 v" + fv + " ✓" : "● Vela 固件：未刷入");
        stFirmware.setTextColor(fw ? 0xff9fdf9f : 0xffcccccc);

        boolean run = qemu.isRunning();
        stVm.setText(run ? "● 虚拟机：运行中 (VNC :" + (5900 + vncDisplay) + ")" : "● 虚拟机：未运行");
        stVm.setTextColor(run ? 0xffffb366 : 0xffcccccc);

        btnStart.setEnabled(!run);
        btnStop.setEnabled(run);
    }

    private void log(final String s) {
        runOnUiThread(() -> {
            console.append(s.endsWith("\n") ? s : s + "\n");
            if (console.getLineCount() > 800) console.setText("");
            // autoscroll
            final android.text.Layout lay = console.getLayout();
            if (lay != null) {
                int scroll = console.getScrollY();
                int dy = (console.getLineCount() - 1) * console.getLineHeight() - console.getHeight() + console.getPaddingTop() + console.getPaddingBottom();
                if (dy > scroll) console.scrollTo(0, dy);
            }
        });
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private interface Task { String run() throws Exception; }

    private void asyncTask(String title, Task t) {
        if (!busy.compareAndSet(false, true)) { toast("有任务进行中"); return; }
        log("[vela] " + title + " ...");
        btnRuntime.setEnabled(false); btnFirmware.setEnabled(false);
        new Thread(() -> {
            try {
                String r = t.run();
                log("[vela] " + r);
            } catch (Exception e) {
                log("[vela] 失败: " + e.getMessage());
            } finally {
                busy.set(false);
                runOnUiThread(() -> { btnRuntime.setEnabled(true); btnFirmware.setEnabled(true); });
            }
        }, "task-" + title).start();
    }

    // RuntimeInstaller.Progress (worker thread)
    @Override public void onLine(String s) { log(s); }
}
