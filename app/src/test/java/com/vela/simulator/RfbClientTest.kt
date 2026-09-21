package com.vela.simulator.vnc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.DataInputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * RFB 客户端协议回归测试（v0.3.2）：
 *
 * 锁定两个真机实测致命缺陷的修复：
 *  1) PointerEvent 必须是消息类型 5 —— 此前误写为 4（=KeyEvent），真机上每个触摸
 *     点都被 QEMU 解析成键盘事件（session-20260921-212344 的
 *     "no scancode found for keysym 1024" 警告即为该缺陷的铁证），触摸完全失效；
 *  2) KeyEvent 必须是消息类型 4 —— 此前误写为 3（=FramebufferUpdateRequest），
 *     按键会拼出带垃圾坐标的帧更新请求污染帧循环。
 *
 * 另覆盖 frameLoop 对 Raw 矩形的解析（矩形头 x,y,w,h,enc 正序）与
 * frameVersion/frameHasContent 驱动逻辑。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RfbClientTest {

    /** 极简 RFB 3.8 服务器：完成握手后返回原始 socket 供用例操纵 */
    private fun serve(block: (Socket) -> Unit): Pair<ServerSocket, Thread> {
        val server = ServerSocket(0)
        val t = thread {
            val c = server.accept()
            c.tcpNoDelay = true
            block(c)
        }
        return server to t
    }

    private fun readFully(ins: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = ins.read(buf, off, buf.size - off)
            check(n > 0) { "EOF" }
            off += n
        }
    }

    /** 完成 RFB 3.8 握手的服务端侧；返回后可读取客户端上行消息 */
    private fun serverHandshake(c: Socket): DataInputStream {
        val out = java.io.DataOutputStream(java.io.BufferedOutputStream(c.getOutputStream()))
        val ins = DataInputStream(c.getInputStream())
        out.write("RFB 003.008\n".toByteArray()); out.flush()
        val ver = ByteArray(12); readFully(ins, ver)
        out.write(byteArrayOf(1, 1)); out.flush() // 1 个安全类型: None
        out.writeInt(0); out.flush()               // SecurityResult = OK
        ins.read()                                  // ClientInit
        // ServerInit: 200x100, pixelformat(16), name "T"
        out.write(java.nio.ByteBuffer.allocate(2 + 2 + 16 + 4 + 1)
            .putShort(200).putShort(100)
            .put(byteArrayOf(32, 32, 0, 1)).putShort(255).putShort(255).putShort(255)
            .put(byteArrayOf(16, 8, 0, 0, 0, 0))
            .putInt(1).put(byteArrayOf('T'.code.toByte())).array())
        out.flush()
        return ins
    }

    @Test
    fun `PointerEvent uses message type 5`() {
        val (server, t) = serve { c ->
            val ins = serverHandshake(c)
            // 读取客户端首条上行消息（应为 PointerEvent）
            val type = ins.readUnsignedByte()
            assertEquals("PointerEvent 消息类型必须是 5（4=KeyEvent，v0.3.1 及之前写错）", 5, type)
            val buttonMask = ins.readUnsignedByte()
            assertEquals(1, buttonMask)
            val x = ins.readUnsignedShort(); val y = ins.readUnsignedShort()
            assertEquals(15, x); assertEquals(25, y)
            c.close()
        }
        val client = RfbClient()
        assertTrue(client.connect(server.localPort, 100, 100))
        client.sendTouch(15, 25, pressed = true)
        t.join(3000)
        server.close()
    }

    @Test
    fun `KeyEvent uses message type 4`() {
        val (server, t) = serve { c ->
            val ins = serverHandshake(c)
            val type = ins.readUnsignedByte()
            assertEquals("KeyEvent 消息类型必须是 4（3=FramebufferUpdateRequest，v0.3.1 及之前写错）", 4, type)
            ins.read() // down-flag
            ins.readUnsignedShort() // pad
            val keysym = ins.readInt()
            assertEquals(0x0020, keysym)
            c.close()
        }
        val client = RfbClient()
        assertTrue(client.connect(server.localPort, 100, 100))
        client.sendKey(RfbClient.KEY_SPACE, down = true)
        t.join(3000)
        server.close()
    }

    @Test
    fun `frameLoop parses raw update and bumps frameVersion`() {
        val (server, t) = serve { c ->
            val ins = serverHandshake(c)
            // 读 SetPixelFormat(20B) + SetEncodings(8B) + FBU 请求(10B)
            val buf = ByteArray(20); readFully(ins, buf)
            assertEquals(0, buf[0].toInt()) // SetPixelFormat
            assertEquals(20, buf.size)
            val enc = ByteArray(8); readFully(ins, enc)
            assertEquals(2, enc[0].toInt()) // SetEncodings
            val fbu = ByteArray(10); readFully(ins, fbu)
            assertEquals(3, fbu[0].toInt()) // FramebufferUpdateRequest
            // 回一个 1 矩形 Raw 全屏更新（蓝色像素）
            val w = 200; val h = 100
            val out = java.io.DataOutputStream(java.io.BufferedOutputStream(c.getOutputStream()))
            out.write(byteArrayOf(0, 0)); out.writeShort(1) // FramebufferUpdate, 1 rect
            out.writeShort(0); out.writeShort(0); out.writeShort(w); out.writeShort(h)
            out.writeInt(0) // Raw
            val px = ByteArray(w * h * 4)
            for (i in 0 until w * h) {
                px[i * 4] = 0x11.toByte(); px[i * 4 + 1] = 0x22.toByte(); px[i * 4 + 2] = 0xAA.toByte()
            }
            out.write(px); out.flush()
            // 停在那里等客户端关闭
            Thread.sleep(800)
            c.close()
        }
        val client = RfbClient()
        assertTrue(client.connect(server.localPort, 100, 100))
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeout(5000) {
                client.frameLoop(onFrame = { })
            }
        }
        assertTrue(client.frameVersion.get() > 0)
        assertTrue("帧内容检测：蓝像素应判为有内容", client.frameHasContent)
        val fb = client.framebuffer
        org.junit.Assert.assertNotNull(fb)
        assertEquals(200, fb!!.width)
        assertEquals(100, fb.height)
        t.join(3000)
        server.close()
    }
}
