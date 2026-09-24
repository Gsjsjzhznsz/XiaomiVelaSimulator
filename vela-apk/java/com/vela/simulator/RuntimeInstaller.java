package com.vela.simulator;

import android.content.Context;
import android.os.PowerManager;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

/**
 * One-click runtime installer: mirrors race -> Packages index -> dependency
 * closure -> deb download (cached/resumable) -> ar + tar.xz extraction into
 * qemu-prefix. Pure Java, no root, mirrors Termux layout under filesDir.
 */
public class RuntimeInstaller {

    public interface Progress { void onLine(String s); }

    private final File home;
    private final Progress cb;
    private final PowerManager.WakeLock wl;

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private String wonBase;

    public RuntimeInstaller(Context ctx, Progress cb) {
        this.home = ctx.getFilesDir();
        this.cb = cb;
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        this.wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vela:runtime");
        this.wl.setReferenceCounted(false);
    }

    public void cancel() { cancelled.set(true); }

    private void log(String s) { if (cb != null) cb.onLine(s); }

    /** @return true when qemu binary is ready to exec. */
    public boolean install() throws Exception {
        cancelled.set(false);
        wl.acquire(30 * 60 * 1000L);
        try {
            // 1. mirror race on the Packages index (winner's body is reused — no second fetch)
            byte[] idx = raceMirrors();
            if (idx == null) throw new IOException("所有软件源均不可用");

            // 2. parse index (body already fetched during mirror race)
            Map<String, Pkg> repo = parseIndex(idx);
            log("软件源索引: " + repo.size() + " 个包 (" + hostOf(wonBase) + ")");

            // 3. dependency closure
            List<Pkg> order = resolve(repo, Constants.RUNTIME_ROOTS);
            long total = 0;
            for (Pkg p : order) total += p.size;
            log("需要下载 " + order.size() + " 个包, 共 " + human(total));

            // 4. download (cache dir, resume by size match)
            File cache = Constants.debCache(home);
            cache.mkdirs();
            int i = 0;
            for (Pkg p : order) {
                if (cancelled.get()) throw new IOException("已取消");
                i++;
                File f = new File(cache, p.name + ".deb");
                boolean ok = f.isFile() && f.length() == p.size;
                if (!ok) {
                    log("[" + i + "/" + order.size() + "] 下载 " + p.name + " (" + human(p.size) + ")");
                    download(wonBase + "/" + p.filename, f, p.size);
                } else {
                    log("[" + i + "/" + order.size() + "] 缓存命中 " + p.name);
                }
            }

            // 5. extract all debs into qemu-prefix
            File prefix = new File(home, "qemu-prefix");
            log("解包部署中...");
            for (Pkg p : order) {
                if (cancelled.get()) throw new IOException("已取消");
                File f = new File(cache, p.name + ".deb");
                extractDeb(f, prefix);
            }
            makeExec(new File(prefix, "usr/bin"));

            File bin = Constants.qemuBin(home);
            if (!bin.isFile()) throw new IOException("解包后未找到 qemu-system-arm");
            log("QEMU 运行环境就绪: " + human(bin.length()) + " qemu-system-arm");
            return true;
        } finally {
            if (wl.isHeld()) wl.release();
        }
    }

    // ---------------- mirror race ----------------

    private byte[] raceMirrors() {
        for (String m : Constants.MIRRORS) {
            if (cancelled.get()) return null;
            try {
                log("探测软件源 " + hostOf(m) + " ...");
                byte[] idx = httpGet(m + "/" + Constants.DIST, 15000);
                if (idx != null && idx.length > 10000) { wonBase = m; return idx; }
                log("软件源 " + hostOf(m) + " 索引异常: " + (idx == null ? "null" : idx.length + "B"));
            } catch (Exception e) {
                log("软件源 " + hostOf(m) + " 不可用: " + brief(e));
            }
        }
        return null;
    }

    // ---------------- Packages index ----------------

    static final class Pkg {
        String name; String filename; long size; List<String> depends = new ArrayList<>();
    }

    static Map<String, Pkg> parseIndex(byte[] data) throws IOException {
        Map<String, Pkg> map = new HashMap<>();
        BufferedReader r = new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8));
        Pkg cur = null;
        String line;
        while ((line = r.readLine()) != null) {
            if (line.startsWith("Package: ")) {
                cur = new Pkg(); cur.name = line.substring(9).trim();
            } else if (cur != null) {
                if (line.startsWith("Filename: ")) cur.filename = line.substring(10).trim();
                else if (line.startsWith("Size: ")) { try { cur.size = Long.parseLong(line.substring(6).trim()); } catch (NumberFormatException ignore) {} }
                else if (line.startsWith("Depends: ")) {
                    for (String d : line.substring(9).split(",")) {
                        String x = d.trim();
                        int sp = x.indexOf(' ');
                        if (sp > 0) x = x.substring(0, sp);
                        if (x.contains("|")) { x = x.split("\\|")[0].trim(); }
                        if (!x.isEmpty()) cur.depends.add(x);
                    }
                }
                else if (line.isEmpty() && cur.filename != null) { map.put(cur.name, cur); cur = null; }
            }
        }
        if (cur != null && cur.filename != null) map.put(cur.name, cur);
        return map;
    }

    /** BFS dependency closure, stable download order (deps first). */
    static List<Pkg> resolve(Map<String, Pkg> repo, String[] roots) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        ArrayDeque<String> q = new ArrayDeque<>();
        for (String r0 : roots) q.add(r0);
        List<String> post = new ArrayList<>(); // insertion order approximates deps-first
        Set<String> inPost = new HashSet<>();
        while (!q.isEmpty()) {
            String n = q.poll();
            if (n == null || !repo.containsKey(n) || !seen.add(n)) continue;
            for (String d : repo.get(n).depends) if (!seen.contains(d)) q.add(d);
            if (inPost.add(n)) post.add(n);
        }
        List<Pkg> out = new ArrayList<>();
        for (String n : post) out.add(repo.get(n));
        return out;
    }

    // ---------------- deb (ar + tar.xz) ----------------

    private void extractDeb(File deb, File prefix) throws IOException {
        byte[] all = readFile(deb);
        ByteBuffer bb = ByteBuffer.wrap(all);
        byte[] magic = new byte[8];
        bb.get(magic);
        if (!"!<arch>\n".equals(new String(magic, StandardCharsets.US_ASCII)))
            throw new IOException("非 ar 归档: " + deb.getName());
        while (bb.remaining() > 60) {
            byte[] h = new byte[60];
            bb.get(h);
            String name = new String(h, 0, 16, StandardCharsets.US_ASCII).trim();
            if (name.endsWith("/")) name = name.substring(0, name.length() - 1);   // dpkg ar long-name style: "data.tar.xz/"
            long size = Long.parseLong(new String(h, 48, 10, StandardCharsets.US_ASCII).trim());
            if (size > Integer.MAX_VALUE - 8) throw new IOException("ar entry too large");
            byte[] payload = new byte[(int) size];
            bb.get(payload);
            if ((size & 1) == 1 && bb.hasRemaining()) bb.get(); // 2-byte alignment
            if ("data.tar.xz".equals(name)) {
                untarXz(payload, prefix);
                return;
            } else if ("data.tar.gz".equals(name)) {
                untarGz(payload, prefix);
                return;
            }
        }
        throw new IOException("deb 中未找到 data.tar.xz: " + deb.getName());
    }

    private void untarXz(byte[] xz, File prefix) throws IOException {
        try (TarArchiveInputStream ti = new TarArchiveInputStream(
                new XZCompressorInputStream(new ByteArrayInputStream(xz)))) {
            untar(ti, prefix);
        }
    }

    private void untarGz(byte[] gz, File prefix) throws IOException {
        try (TarArchiveInputStream ti = new TarArchiveInputStream(
                new GZIPInputStream(new ByteArrayInputStream(gz)))) {
            untar(ti, prefix);
        }
    }

    private void untar(TarArchiveInputStream ti, File prefix) throws IOException {
        // Termux debs:  ./data/data/com.termux/files/usr/bin/x
        // Debian debs:  ./usr/bin/x  or  usr/bin/x
        // Normalize everything to "usr/..." inside our qemu-prefix.
        File root = prefix.getParentFile(); // filesDir
        File pfx = new File(root, "qemu-prefix");
        TarArchiveEntry e;
        byte[] buf = new byte[1 << 16];
        while ((e = ti.getNextTarEntry()) != null) {
            String n = e.getName();
            if (n.startsWith("./")) n = n.substring(2);
            String rel;
            int i = n.indexOf("files/usr/");
            if (i >= 0) rel = "usr/" + n.substring(i + "files/usr/".length());
            else if (n.equals("data/data/com.termux/files/usr")) rel = "usr";
            else if (n.equals("usr") || n.startsWith("usr/")) rel = n;
            else continue;
            File out = new File(pfx, rel);
            if (e.isDirectory()) { out.mkdirs(); continue; }
            if (e.isSymbolicLink()) {
                out.getParentFile().mkdirs();
                String target = e.getLinkName();
                if (target.startsWith("/data/data/com.termux/files/usr/")) {
                    target = new File(pfx, "usr/" + target.substring("/data/data/com.termux/files/usr/".length())).getAbsolutePath();
                } else if (target.startsWith("/")) {
                    continue; // external absolute target — leave to system resolution
                }
                try {
                    out.delete();
                    android.system.Os.symlink(target, out.getAbsolutePath());
                } catch (Throwable t) {
                    File real = new File(pfx, "usr/" + target.replaceAll("^\\./", ""));
                    if (real.isFile()) copyFile(real, out); // best effort fallback
                }
                continue;
            }
            out.getParentFile().mkdirs();
            FileOutputStream fo = new FileOutputStream(out);
            int r; long total = 0;
            while ((r = ti.read(buf)) > 0) { fo.write(buf, 0, r); total += r; }
            fo.close();
            int mode = e.getMode() & 0777;
            if (mode != 0) { out.setReadable(true); out.setWritable(true); }
            if ((mode & 0111) != 0) out.setExecutable(true, false);
        }
    }

    private void makeExec(File binDir) {
        File[] fs = binDir.listFiles();
        if (fs == null) return;
        for (File f : fs) if (f.isFile()) f.setExecutable(true, false);
    }

    // ---------------- http ----------------

    private void download(String url, File out, long expect) throws IOException {
        File tmp = new File(out.getParentFile(), out.getName() + ".part");
        HttpURLConnection c = open(url, 30000);
        c.setRequestProperty("User-Agent", "VelaSimulator/2.0");   // BEFORE the request is made
        int code = c.getResponseCode();
        if (code >= 400) throw new IOException("HTTP " + code);
        InputStream in = new BufferedInputStream(c.getInputStream(), 1 << 16);
        FileOutputStream fo = new FileOutputStream(tmp);
        byte[] buf = new byte[1 << 16];
        long done = 0; int r; long lastLog = 0;
        while ((r = in.read(buf)) > 0) {
            if (cancelled.get()) { in.close(); fo.close(); throw new IOException("已取消"); }
            fo.write(buf, 0, r); done += r;
            if (done - lastLog > 4 << 20) { lastLog = done; log("  ... " + human(done) + (expect > 0 ? " / " + human(expect) : "")); }
        }
        in.close(); fo.close();
        if (expect > 0 && done != expect) throw new IOException("大小不匹配: " + done + " vs " + expect);
        if (!tmp.renameTo(out)) { copyFile(tmp, out); tmp.delete(); }
    }

    private byte[] httpGet(String url, int timeoutMs) throws IOException {
        HttpURLConnection c = open(url, timeoutMs);
        c.setRequestProperty("User-Agent", "VelaSimulator/2.0");   // BEFORE the request is made
        int code = c.getResponseCode();
        if (code >= 400) throw new IOException("HTTP " + code);
        InputStream in = c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        int r;
        while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
        in.close();
        return bo.toByteArray();
    }

    /** Configure-only: NEVER touches the response here — getResponseCode() would
     *  fire the request and lock the headers ("Cannot set request property"). */
    private HttpURLConnection open(String url, int timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setInstanceFollowRedirects(true);
        return c;
    }

    // ---------------- misc ----------------

    private static void copyFile(File src, File dst) throws IOException {
        FileInputStream fi = new FileInputStream(src);
        FileOutputStream fo = new FileOutputStream(dst);
        byte[] b = new byte[1 << 16]; int r;
        while ((r = fi.read(b)) > 0) fo.write(b, 0, r);
        fi.close(); fo.close();
    }

    private static byte[] readFile(File f) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream((int) f.length());
        FileInputStream fi = new FileInputStream(f);
        byte[] b = new byte[1 << 16]; int r;
        while ((r = fi.read(b)) > 0) bo.write(b, 0, r);
        fi.close();
        return bo.toByteArray();
    }

    private static String brief(Exception e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }

    static String hostOf(String url) {
        String s = url;
        s = s.substring(s.indexOf("://") + 3);
        int i = s.indexOf('/');
        return i < 0 ? s : s.substring(0, i);
    }

    static String human(long n) {
        if (n < 1024) return n + " B";
        if (n < 1048576) return String.format("%.0f KB", n / 1024.0);
        return String.format("%.1f MB", n / 1048576.0);
    }
}
