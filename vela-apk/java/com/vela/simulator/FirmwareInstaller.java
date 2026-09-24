package com.vela.simulator;

import android.content.Context;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;

/** Deploys bundled (or GitHub-hosted) firmware: nuttx.bin + data.img. */
public class FirmwareInstaller {

    public interface Progress { void onLine(String s); }

    private final File home;
    private final Progress cb;

    public FirmwareInstaller(Context ctx, Progress cb) {
        this.home = ctx.getFilesDir();
        this.cb = cb;
    }

    private void log(String s) { if (cb != null) cb.onLine(s); }

    public boolean isInstalled() {
        return Constants.fwBin(home).isFile() && Constants.fwData(home).isFile();
    }

    public String installedVersion() {
        try {
            BufferedReader r = new BufferedReader(new FileReader(new File(home, "firmware.version")));
            String v = r.readLine(); r.close();
            return v == null ? "?" : v.trim();
        } catch (Exception e) { return null; }
    }

    /** Copy firmware from APK assets (and refresh if asset version is newer). */
    public void installFromAssets(Context ctx) throws Exception {
        String ver = readAsset(ctx, Constants.FW_VERSION_AS).trim();
        if (isInstalled() && ver.equals(installedVersion())) {
            log("固件已是最新: v" + ver);
            return;
        }
        File dir = Constants.imagesDir(home);
        dir.mkdirs();
        log("刷入固件 v" + ver + " (从 APK 资产)...");
        copyAsset(ctx, Constants.FW_ASSET_DIR + "/" + Constants.KERN_BIN, Constants.fwBin(home));
        copyAsset(ctx, Constants.FW_ASSET_DIR + "/" + Constants.DATA_IMG, Constants.fwData(home));
        writeString(new File(home, "firmware.version"), ver);
        log("固件刷入完成: " + RuntimeInstaller.human(Constants.fwBin(home).length())
                + " elf + " + RuntimeInstaller.human(Constants.fwData(home).length()) + " data");
    }

    /** Optional online refresh from GitHub Releases (silent failure = keep assets). */
    public boolean tryUpdateFromGithub() {
        try {
            String verUrl = Constants.FW_RELEASE_BASE + "/firmware.version";
            String latest = fetchText(verUrl).trim();
            if (latest.equals(installedVersion())) { log("在线固件无更新 (v" + latest + ")"); return true; }
            log("发现在线固件 v" + latest + "，更新中...");
            downloadTo(Constants.FW_RELEASE_BASE + "/" + Constants.KERN_BIN, Constants.fwBin(home));
            downloadTo(Constants.FW_RELEASE_BASE + "/" + Constants.DATA_IMG, Constants.fwData(home));
            writeString(new File(home, "firmware.version"), latest);
            log("在线固件更新完成");
            return true;
        } catch (Exception e) {
            log("在线固件获取失败（使用本地资产）: " + e.getMessage());
            return false;
        }
    }

    // ---------------- io ----------------

    private void copyAsset(Context ctx, String asset, File out) throws IOException {
        InputStream in = ctx.getAssets().open(asset);
        FileOutputStream fo = new FileOutputStream(out);
        byte[] b = new byte[1 << 16]; int r;
        while ((r = in.read(b)) > 0) fo.write(b, 0, r);
        in.close(); fo.close();
    }

    private String readAsset(Context ctx, String asset) throws IOException {
        InputStream in = ctx.getAssets().open(asset);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[4096]; int r;
        while ((r = in.read(b)) > 0) bo.write(b, 0, r);
        in.close();
        return bo.toString("UTF-8");
    }

    private static String fetchText(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(15000);
        if (c.getResponseCode() >= 400) throw new IOException("HTTP " + c.getResponseCode());
        InputStream in = c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[8192]; int r;
        while ((r = in.read(b)) > 0) bo.write(b, 0, r);
        in.close();
        return bo.toString("UTF-8");
    }

    private static void downloadTo(String url, File out) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(30000);
        if (c.getResponseCode() >= 400) throw new IOException("HTTP " + c.getResponseCode());
        InputStream in = c.getInputStream();
        FileOutputStream fo = new FileOutputStream(out);
        byte[] b = new byte[1 << 16]; int r;
        while ((r = in.read(b)) > 0) fo.write(b, 0, r);
        in.close(); fo.close();
    }

    private static void writeString(File f, String s) throws IOException {
        FileWriter w = new FileWriter(f); w.write(s); w.close();
    }
}
