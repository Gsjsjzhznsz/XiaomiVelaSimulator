package com.vela.simulator.vnc

import android.graphics.Bitmap
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 极简 VNC (RFB 3.8) 客户端 —— 仅实现本模拟器需要的路径：
 *  - 握手 / 认证（None）
 *  - 设置像素格式 ARGB8888（小端 BG32）
 *  - 请求 Raw 编码整帧增量更新
 *  - 输入事件上行: PointerEvent(触摸/点击) + KeyEvent(按键)
 *  - 输出到 Bitmap 供 Compose 显示
 *
 * NuttX/openvela 的带帧缓冲镜像（如 lvgl demo + virtio-gpu）可通过该视图观察画面；
 * 无显示设备的镜像（纯 nsh）会显示空帧，属正常现象。
 */
class RfbClient {

    private var socket: Socket? = null
    private var din: DataInputStream? = null
    private var dout: DataOutputStream? = null
    private val running = AtomicBoolean(false)

    /** 写锁: 帧循环请求与输入事件共用一条输出流，必须串行化 */
    private val writeLock = Any()

    var framebuffer: Bitmap? = null
        private set

    @Volatile
    var frameReady: Boolean = false
        private set

    private var width = 0
    private var height = 0

    /** 连接并完成初始化；成功返回 true。onFrame 可选回调（UI 线程外） */
    fun connect(port: Int, expectW: Int, expectH: Int): Boolean {
        val s = Socket()
        s.connect(InetSocketAddress("127.0.0.1", port), 2000)
        s.tcpNoDelay = true
        socket = s
        din = DataInputStream(s.getInputStream().buffered())
        dout = DataOutputStream(s.getOutputStream().buffered())
        handshake()
        // 尺寸不一致时以服务器为准
        if (width != expectW || height != expectH) { /* 使用服务器尺寸 */ }
        running.set(true)
        return true
    }

    private fun handshake() {
        val di = din ?: error("no input")
        val doo = dout ?: error("no output")
        // ProtocolVersion
        val ver = ByteArray(12)
        di.readFully(ver)
        doo.write("RFB 003.008\n".toByteArray()); doo.flush()
        // SecTypes (u8 count + types)
        val n = di.read()
        if (n > 0) {
            val types = ByteArray(n); di.readFully(types)
            doo.write(1) // None
        } else {
            doo.write(1) // legacy
        }
        doo.flush()
        // SecurityResult
        val result = di.readInt()
        check(result == 0) { "VNC 认证失败 result=$result" }
        // ClientInit
        doo.write(1) // share
        doo.flush()
        // ServerInit
        val w = di.readUnsignedShort()
        val h = di.readUnsignedShort()
        width = w; height = h
        di.skipBytes(16) // pixelformat
        val nameLen = di.readInt()
        val name = ByteArray(nameLen); di.readFully(name)
        framebuffer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    }

    /** 设置像素格式为 ARGB8888 小端 + 请求整帧更新，开始读帧循环 */
    suspend fun frameLoop(onFrame: (Bitmap) -> Unit) {
        val di = din ?: error("not connected")
        val doo = dout ?: error("not connected")
        val w = width; val h = height

        // SetPixelFormat: BG32 little-endian (true-color)
        val pf = ByteArray(16)
        pf[0] = 0; pf[1] = 0; pf[2] = 0 // padding x2 + bigEndianFlag=0
        // bytes: [u8 pad][u8 bigendian? Actually layout: 1 pad,1 bigendian? RFB: 3 bytes = padding(1)+bigEndian(1)+trueColor(1)... ]
        // 正确布局: 1B padding, 1B big-endian-flag, 1B true-color-flag, 2B red-max, 2B green-max, 2B blue-max, 1B red-shift, 1B green-shift, 1B blue-shift, 3B padding
        pf[1] = 0; pf[2] = 1
        putU16(pf, 3, 255); putU16(pf, 5, 255); putU16(pf, 7, 255)
        pf[9] = 16; pf[10] = 8; pf[11] = 0
        doo.write(0)          // SetPixelFormat
        doo.write(pf)
        doo.flush()

        // SetEncodings: Raw only
        doo.write(2) // SetEncodings
        doo.write(0) // pad
        doo.writeShort(1)
        doo.writeInt(0) // encoding raw
        doo.flush()

        var bitmap = framebuffer ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        val rowBuf = ByteBuffer.allocate(w * 4)

        while (running.get()) {
            // FramebufferUpdateRequest: incremental=0 每次请求全量（低频，足够演示）
            synchronized(writeLock) {
                doo.write(3); doo.write(0)
                doo.writeShort(0); doo.writeShort(0); doo.writeShort(w); doo.writeShort(h)
                doo.flush()
            }

            val msgType = di.readUnsignedByte()
            when (msgType) {
                0 -> { // FramebufferUpdate
                    di.readUnsignedByte() // pad
                    di.readUnsignedShort() // rects
                    val rw = di.readUnsignedShort()
                    val rx = di.readUnsignedShort()
                    val ry = di.readUnsignedShort()
                    val rW = di.readUnsignedShort()
                    val rH = di.readUnsignedShort()
                    val enc = di.readInt()
                    if (enc == 0 && rW > 0 && rH > 0) { // Raw
                        val bpp = 4
                        val lineBytes = rW * bpp
                        val raw = ByteArray(lineBytes)
                        for (yy in 0 until rH) {
                            di.readFully(raw)
                            rowBuf.clear(); rowBuf.put(raw); rowBuf.flip()
                            // BG32 -> ARGB int
                            val destY = ry + yy
                            if (destY < h) {
                                val x0 = rx
                                for (xx in 0 until rW) {
                                    val dx = x0 + xx
                                    if (dx >= w) break
                                    val b = raw[xx * 4].toInt() and 0xFF
                                    val g = raw[xx * 4 + 1].toInt() and 0xFF
                                    val r = raw[xx * 4 + 2].toInt() and 0xFF
                                    pixels[destY * w + dx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                                }
                            }
                        }
                        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
                        framebuffer = bitmap
                        frameReady = true
                        onFrame(bitmap)
                    } else {
                        // 不支持的编码：断开（本客户端只请求 Raw）
                        close(); return
                    }
                    rx.toString() // no-op
                    rw.toString()
                }
                1 -> { // SetColourMapEntries
                    di.readUnsignedByte()
                    di.readUnsignedShort()
                    val n = di.readUnsignedShort()
                    repeat(n) { di.readUnsignedShort(); di.readUnsignedShort(); di.readUnsignedShort() }
                }
                2 -> { // Bell
                }
                3 -> { // ServerCutText
                    di.skipBytes(3)
                    val len = di.readInt()
                    if (len > 0 && len < 1 shl 20) di.skipBytes(len) else break
                }
                else -> { close(); return }
            }
        }
    }

    private fun putU16(arr: ByteArray, off: Int, v: Int) {
        arr[off] = (v shr 8).toByte(); arr[off + 1] = v.toByte()
    }

    /**
     * 发送指针事件（RFB PointerEvent, msg-type=4）—— 触摸/点击的上行通道。
     * QEMU 侧由 virtio-tablet 等绝对指针设备接收为绝对坐标。
     * buttonMask: bit0=左键(按下触摸)。
     */
    fun sendPointer(x: Int, y: Int, buttonMask: Int) {
        val d = dout ?: return
        synchronized(writeLock) {
            runCatching {
                d.write(4)
                d.write(buttonMask and 0xFF)
                d.writeShort(x.coerceIn(0, 65535))
                d.writeShort(y.coerceIn(0, 65535))
                d.flush()
            }
        }
    }

    /** 触摸按下/拖动/抬起 一行式调用: pressed=false 时发送 buttonMask=0 */
    fun sendTouch(x: Int, y: Int, pressed: Boolean) = sendPointer(x, y, if (pressed) 1 else 0)

    /**
     * 发送按键事件（RFB KeyEvent, msg-type=3），keysym 为 X11 keysym 编码。
     * down=false 表示松开。
     */
    fun sendKey(keysym: Int, down: Boolean) {
        val d = dout ?: return
        synchronized(writeLock) {
            runCatching {
                d.write(3)
                d.write(if (down) 1 else 0)
                d.writeShort(0)
                d.writeInt(keysym)
                d.flush()
            }
        }
    }

    companion object {
        // 常用 X11 keysym（供后续屏幕按键使用）
        const val KEY_ENTER = 0xFF0D
        const val KEY_BACKSPACE = 0xFF08
        const val KEY_ESCAPE = 0xFF1B
        const val KEY_TAB = 0xFF09
        const val KEY_SPACE = 0x0020
        const val KEY_UP = 0xFF52
        const val KEY_DOWN = 0xFF54
        const val KEY_LEFT = 0xFF51
        const val KEY_RIGHT = 0xFF53
    }

    fun close() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
    }
}
