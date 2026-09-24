package com.vela.simulator;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

/** JVM validation of the deb ar+tar.xz extraction path with the exact jars bundled in the APK. */
public class TestExtract {
    public static void main(String[] args) throws Exception {
        byte[] all = Files.readAllBytes(Paths.get(args[0]));
        File out = new File(args[1]);
        ByteBuffer bb = ByteBuffer.wrap(all);
        byte[] magic = new byte[8];
        bb.get(magic);
        if (!"!<arch>\n".equals(new String(magic, StandardCharsets.US_ASCII))) throw new IOException("not ar");
        int files = 0, dirs = 0, links = 0; boolean gotData = false;
        while (bb.remaining() > 60) {
            byte[] h = new byte[60];
            bb.get(h);
            String name = new String(h, 0, 16, StandardCharsets.US_ASCII).trim();
            if (name.endsWith("/")) name = name.substring(0, name.length() - 1);
            long size = Long.parseLong(new String(h, 48, 10, StandardCharsets.US_ASCII).trim());
            if (size > Integer.MAX_VALUE - 8) throw new IOException("ar entry too large");
            byte[] payload = new byte[(int) size];
            bb.get(payload);
            if ((size & 1) == 1 && bb.hasRemaining()) bb.get();
            if (!name.startsWith("data.tar")) continue;
            gotData = true;
            InputStream src = name.equals("data.tar.xz")
                    ? new XZCompressorInputStream(new ByteArrayInputStream(payload))
                    : new GZIPInputStream(new ByteArrayInputStream(payload));
            TarArchiveInputStream ti = new TarArchiveInputStream(src);
            TarArchiveEntry e;
            byte[] buf = new byte[1 << 16];
            while ((e = ti.getNextTarEntry()) != null) {
                String n = e.getName();
                if (n.startsWith("./")) n = n.substring(2);
                String rel;
                int ix = n.indexOf("files/usr/");
                if (ix >= 0) rel = "usr/" + n.substring(ix + "files/usr/".length());
                else if (n.equals("data/data/com.termux/files/usr")) rel = "usr";
                else if (n.equals("usr") || n.startsWith("usr/")) rel = n;
                else continue;
                File f = new File(out, rel);
                if (e.isDirectory()) { f.mkdirs(); dirs++; continue; }
                if (e.isSymbolicLink()) {
                    f.getParentFile().mkdirs();
                    try { f.delete(); java.nio.file.Files.createSymbolicLink(f.toPath(), java.nio.file.Paths.get(e.getLinkName())); links++; } catch (Exception ignore) {}
                    continue;
                }
                f.getParentFile().mkdirs();
                FileOutputStream fo = new FileOutputStream(f);
                int r; while ((r = ti.read(buf)) > 0) fo.write(buf, 0, r);
                fo.close();
                int mode = e.getMode() & 0777;
                if ((mode & 0111) != 0) f.setExecutable(true, false);
                files++;
            }
            ti.close();
            break;
        }
        if (!gotData) throw new AssertionError("no data.tar.* in deb");
        System.out.println("extracted files=" + files + " dirs=" + dirs + " links=" + links + " -> " + out.getAbsolutePath());
        if (files == 0) throw new AssertionError("nothing extracted");
        System.out.println("EXTRACT TEST OK");
    }
}
