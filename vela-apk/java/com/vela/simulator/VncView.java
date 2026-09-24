package com.vela.simulator;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Minimal RFB 3.8 client: None auth, 32bpp LE pixel format, Raw encoding,
 * DesktopResize, pointer events from touch. Sized for localhost QEMU.
 */
public class VncView extends View {

    public interface Status { void onLine(String s); void onConnected(boolean ok); }

    private final Status cb;
    private Thread worker;
    private volatile boolean stop;

    private Bitmap fb;
    private final Paint paint = new Paint();
    private volatile int fbW = 0, fbH = 0;
    private volatile boolean fbReady = false;

    // pending pointer event (written by UI thread, flushed by client loop)
    private volatile boolean ptrDirty = false;
    private volatile int ptrX, ptrY, ptrMask;

    public VncView(Context c, Status cb) {
        super(c);
        this.cb = cb;
        paint.setFilterBitmap(false);
    }

    /** qemu -vnc 127.0.0.1:N  ->  tcp port 5900 + N */
    public void connect(int display) {
        disconnect();
        stop = false;
        final int port = 5900 + display;
        worker = new Thread(() -> runClient("127.0.0.1", port), "vnc-client");
        worker.setDaemon(true);
        worker.start();
    }

    public void disconnect() {
        stop = true;
        Thread t = worker;
        if (t != null) t.interrupt();
        worker = null;
        fbReady = false;
        postInvalidate();
    }

    // ---------------- RFB protocol ----------------

    private void runClient(String host, int port) {
        Socket sock = null;
        DataOutputStream out = null;
        try {
            sock = new Socket();
            sock.connect(new InetSocketAddress(host, port), 5000);
            sock.setTcpNoDelay(true);
            sock.setSoTimeout(15000);
            DataInputStream in = new DataInputStream(new BufferedInputStream(sock.getInputStream(), 1 << 17));
            out = new DataOutputStream(new BufferedOutputStream(sock.getOutputStream(), 1 << 15));

            // 1. version handshake (echo server version)
            byte[] pv = new byte[12];
            in.readFully(pv);
            out.write(pv); out.flush();

            // 2. security: require None(1)
            int nsec = in.readUnsignedByte();
            if (nsec == 0) throw new Exception("连接被拒绝");
            int secType = -1;
            for (int i = 0; i < nsec; i++) { int t = in.readUnsignedByte(); if (t == 1) secType = 1; }
            if (secType != 1) throw new Exception("VNC 需要认证，请使用无密码模式");
            out.writeByte(1); out.flush();
            if (in.readUnsignedByte() != 0) throw new Exception("安全握手失败");

            // 3. ClientInit(shared) / ServerInit
            out.writeByte(1); out.flush();
            fbW = in.readUnsignedShort(); fbH = in.readUnsignedShort();
            in.skipBytes(16);                       // server pixel format (overridden below)
            int nameLen = in.readInt();
            byte[] nb = new byte[nameLen]; in.readFully(nb);
            status("VNC 已连接 " + fbW + "x" + fbH);

            // 4. SetPixelFormat (msg 0, total 20B):
            //    type(1) pad(2) bpp(1) depth(1) endian(1) truecolor(1)
            //    rmax(2) gmax(2) bmax(2) rsh(1) gsh(1) bsh(1) pad(3)
            out.writeByte(0);
            out.writeShort(0);
            out.writeByte(32); out.writeByte(24);   // bpp, depth
            out.writeByte(0);                       // little-endian
            out.writeByte(1);                       // true colour
            out.writeShort(255); out.writeShort(255); out.writeShort(255);
            out.writeByte(16); out.writeByte(8); out.writeByte(0);
            out.writeByte(0); out.writeByte(0); out.writeByte(0);
            out.flush();

            // 5. SetEncodings: Raw(0), DesktopResize(-223)
            out.writeByte(2); out.writeByte(0); out.writeShort(2);
            out.writeInt(0); out.writeInt(-223);
            out.flush();

            fb = Bitmap.createBitmap(fbW, fbH, Bitmap.Config.ARGB_8888);
            fbReady = true;
            requestFbUpdate(out, true);
            status("VNC 就绪");

            // 6. event loop
            while (!stop) {
                int msg = in.readUnsignedByte();
                switch (msg) {
                    case 0: { // FramebufferUpdate
                        in.skipBytes(1);
                        int rects = in.readUnsignedShort();
                        for (int i = 0; i < rects; i++) {
                            int x = in.readUnsignedShort(), y = in.readUnsignedShort();
                            int w = in.readUnsignedShort(), h = in.readUnsignedShort();
                            int enc = in.readInt();
                            if (enc == -223) { // DesktopResize
                                fbW = w; fbH = h;
                                fb = Bitmap.createBitmap(fbW, fbH, Bitmap.Config.ARGB_8888);
                                requestFbUpdate(out, true);
                                continue;
                            }
                            if (enc != 0) throw new Exception("不支持的编码 " + enc);
                            int n = w * h;
                            if (n <= 0) continue;
                            byte[] px = new byte[n * 4];
                            in.readFully(px);
                            int[] argb = new int[n];
                            for (int k = 0; k < n; k++) {
                                int b0 = px[k*4] & 0xff, b1 = px[k*4+1] & 0xff, b2 = px[k*4+2] & 0xff;
                                argb[k] = 0xff000000 | (b2 << 16) | (b1 << 8) | b0;
                            }
                            fb.setPixels(argb, 0, w, x, y, w, h);
                        }
                        postInvalidate();
                        requestFbUpdate(out, false);
                        break;
                    }
                    case 1: { // SetColourMapEntries
                        in.skipBytes(5);
                        int cnt = in.readUnsignedShort();
                        in.skipBytes(cnt * 6);
                        break;
                    }
                    case 2: in.skipBytes(4); break;          // Bell
                    case 3: { // ServerCutText
                        in.skipBytes(3);
                        int len = in.readInt();
                        if (len > 0 && len < (1 << 20)) in.skipBytes(len);
                        break;
                    }
                    default:
                        throw new Exception("未知消息类型 " + msg);
                }
            }
        } catch (Exception e) {
            if (!stop) status("VNC 断开: " + e.getMessage());
        } finally {
            try { if (sock != null) sock.close(); } catch (Exception ignore) {}
            fbReady = false;
            postInvalidate();
        }
    }

    private void requestFbUpdate(DataOutputStream out, boolean full) throws Exception {
        flushPointer(out);                       // piggyback pending pointer events
        out.writeByte(3);
        out.writeByte(full ? 0 : 1);
        out.writeShort(0); out.writeShort(0);
        out.writeShort(fbW); out.writeShort(fbH);
        out.flush();
    }

    private void flushPointer(DataOutputStream out) throws Exception {
        if (ptrDirty) {
            ptrDirty = false;
            out.writeByte(4);
            out.writeByte(ptrMask);
            out.writeShort(ptrX); out.writeShort(ptrY);
            out.flush();
        }
    }

    private void status(String s) { if (cb != null) cb.onLine(s); }

    // ---------------- rendering & touch ----------------

    @Override protected void onDraw(Canvas c) {
        c.drawColor(0xff000000);
        if (fbReady && fb != null && fbW > 0 && fbH > 0) {
            float s = Math.min(getWidth() / (float) fbW, getHeight() / (float) fbH);
            int dw = (int) (fbW * s), dh = (int) (fbH * s);
            int dx = (getWidth() - dw) / 2, dy = (getHeight() - dh) / 2;
            c.drawBitmap(fb, null, new RectF(dx, dy, dx + dw, dy + dh), paint);
        } else {
            Paint p = new Paint();
            p.setColor(0xff555555); p.setTextSize(28f); p.setTextAlign(Paint.Align.CENTER);
            p.setAntiAlias(true);
            c.drawText("虚拟机画面（启动后显示）", getWidth() / 2f, getHeight() / 2f, p);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent ev) {
        if (!fbReady || fbW == 0 || fbH == 0) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                sendPtr(ev, 1); return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                sendPtr(ev, 0); return true;
            case MotionEvent.ACTION_MOVE: return true;
            default: return true;
        }
    }

    private void sendPtr(MotionEvent ev, int mask) {
        float s = Math.min(getWidth() / (float) fbW, getHeight() / (float) fbH);
        int dw = (int) (fbW * s), dh = (int) (fbH * s);
        int dx = (getWidth() - dw) / 2, dy = (getHeight() - dh) / 2;
        int x = (int) ((ev.getX() - dx) / s);
        int y = (int) ((ev.getY() - dy) / s);
        if (x < 0) x = 0; if (y < 0) y = 0;
        if (x >= fbW) x = fbW - 1; if (y >= fbH) y = fbH - 1;
        ptrX = x; ptrY = y; ptrMask = mask; ptrDirty = true;
    }
}
