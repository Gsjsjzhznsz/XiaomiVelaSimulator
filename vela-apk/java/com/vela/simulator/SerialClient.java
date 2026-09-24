package com.vela.simulator;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;

/** NSH console over qemu -serial tcp. Line-buffered in, raw write out. */
public class SerialClient {

    public interface Listener {
        void onData(String text);   // arbitrary chunk from serial
        void onClosed(String why);
    }

    private Socket sock;
    private Thread reader;
    private volatile boolean stop;
    private BufferedWriter out;

    public void connect(int port, final Listener l) {
        disconnect();
        stop = false;
        reader = new Thread(() -> {
            try {
                sock = new Socket();
                sock.connect(new InetSocketAddress("127.0.0.1", port), 8000);
                sock.setSoTimeout(15000);
                out = new BufferedWriter(new OutputStreamWriter(sock.getOutputStream()));
                BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()), 1 << 15);
                char[] buf = new char[2048];
                long lastData = 0;
                while (!stop) {
                    try {
                        int r = in.read(buf);
                        if (r < 0) break;
                        if (r > 0) {
                            String s = new String(buf, 0, r);
                            if (l != null) l.onData(s);
                            lastData = System.currentTimeMillis();
                        }
                    } catch (java.net.SocketTimeoutException te) {
                        // idle poll — also detect qemu death
                        if (System.currentTimeMillis() - lastData > 1000 && !sock.isConnected()) break;
                    }
                }
                if (!stop && l != null) l.onClosed("串口已断开");
            } catch (Exception e) {
                if (!stop && l != null) l.onClosed("串口连接失败: " + e.getMessage());
            }
        }, "serial");
        reader.setDaemon(true);
        reader.start();
    }

    /** Send a command line (appends \n). Safe from UI thread. */
    public void sendLine(final String line) {
        final Thread t = reader;
        if (t == null || !t.isAlive()) return;
        new Thread(() -> {
            try {
                synchronized (this) {
                    if (out != null) {
                        out.write(line);
                        out.write("\n");
                        out.flush();
                    }
                }
            } catch (IOException ignore) {}
        }, "serial-write").start();
    }

    public boolean isConnected() {
        Thread t = reader;
        return t != null && t.isAlive() && !stop;
    }

    public void disconnect() {
        stop = true;
        Thread t = reader;
        if (t != null) t.interrupt();
        reader = null;
        try { if (sock != null) sock.close(); } catch (Exception ignore) {}
        sock = null; out = null;
    }
}
