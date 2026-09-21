package com.vela.simulator

import com.vela.simulator.engine.ImageManager
import com.vela.simulator.util.HttpDownloader
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * v0.2.7 下载链路与镜像健康校验回归测试：
 * 1. HttpDownloader.onBytes 必须回传【累计】字节数（修复 UI 显示 "xxx 1KB" 异常）；
 * 2. 单流路径对 Content-Length 不符的半截响应必须抛错（防残缺文件落盘）；
 * 3. ELF 魔数/最小体积校验拦截错误页与损坏内核（修复"假就绪 → 无画面"）。
 *
 * HTTP 服务用原生 ServerSocket 手写（com.sun.net.httpserver 会被 android.jar stub 遮蔽）。
 */
class DownloadLogicTest {

    private lateinit var serverSocket: ServerSocket
    private var port: Int = 0
    private lateinit var tmpDir: File
    private val closed = AtomicBoolean(false)

    /** 4MB 测试载荷（≥ SEGMENT_MIN_SIZE 覆盖分段路径） */
    private val payload = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }

    /** 1KB 错误页（模拟反代对失效资源返回 200 + 小响应体） */
    private val errorPage = ByteArray(1024)

    @Before
    fun setUp() {
        tmpDir = File(System.getProperty("java.io.tmpdir"), "vela-dl-test-${System.nanoTime()}").apply { mkdirs() }
        serverSocket = ServerSocket(0, 64, java.net.InetAddress.getByName("127.0.0.1"))
        port = serverSocket.localPort
        val acceptLoop = Thread {
            while (!closed.get()) {
                val sock = runCatching { serverSocket.accept() }.getOrNull() ?: break
                Thread { handle(sock) }.start()
            }
        }
        acceptLoop.isDaemon = true
        acceptLoop.start()
    }

    /** 极简 HTTP/1.1 响应：支持 Range(206) / 全量(200) / 截断 三种路径，按请求路径路由 */
    private fun handle(sock: Socket) {
        sock.use { s ->
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII))
            val first = reader.readLine() ?: return
            // 读取其余请求头，保留 Range 值
            var rangeHeader: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) rangeHeader = line.substringAfter(':').trim()
            }
            val path = first.split(" ").getOrNull(1) ?: return
            when {
                path.startsWith("/range") -> {
                    val m = rangeHeader?.let { Regex("bytes=(\\d+)-(\\d+)").find(it) }
                    if (m != null) {
                        val (startS, endS) = m.destructured
                        respond206(s, payload, startS.toInt(), endS.toInt())
                    } else {
                        respondFull(s, payload)
                    }
                }
                path.startsWith("/truncated") -> {
                    // 声明完整 Content-Length 但只发一半（模拟代理截断）
                    val head = "HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
                    s.getOutputStream().write(head.toByteArray(StandardCharsets.US_ASCII))
                    s.getOutputStream().write(payload, 0, payload.size / 2)
                    s.getOutputStream().flush()
                }
                path.startsWith("/error-page") -> {
                    val head = "HTTP/1.1 200 OK\r\nContent-Length: ${errorPage.size}\r\nConnection: close\r\n\r\n"
                    s.getOutputStream().write(head.toByteArray(StandardCharsets.US_ASCII))
                    s.getOutputStream().write(errorPage)
                    s.getOutputStream().flush()
                }
                else -> { // /full → 200 全量
                    val head = "HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
                    s.getOutputStream().write(head.toByteArray(StandardCharsets.US_ASCII))
                    s.getOutputStream().write(payload)
                    s.getOutputStream().flush()
                }
            }
        }
    }

    private fun respond206(s: Socket, data: ByteArray, start: Int, end: Int) {
        val slice = data.copyOfRange(start, end + 1)
        val head = "HTTP/1.1 206 Partial Content\r\n" +
            "Content-Range: bytes $start-$end/${data.size}\r\n" +
            "Content-Length: ${slice.size}\r\nConnection: close\r\n\r\n"
        s.getOutputStream().write(head.toByteArray(StandardCharsets.US_ASCII))
        s.getOutputStream().write(slice)
        s.getOutputStream().flush()
    }

    private fun respondFull(s: Socket, data: ByteArray) {
        val head = "HTTP/1.1 200 OK\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n"
        s.getOutputStream().write(head.toByteArray(StandardCharsets.US_ASCII))
        s.getOutputStream().write(data)
        s.getOutputStream().flush()
    }

    @After
    fun tearDown() {
        closed.set(true)
        runCatching { serverSocket.close() }
        tmpDir.deleteRecursively()
    }

    private fun client() = OkHttpClient.Builder().followRedirects(true).build()

    @Test
    fun `segmented download reports cumulative progress`() {
        val dest = File(tmpDir, "seg.elf")
        val last = AtomicLong(0)
        val prev = AtomicLong(-1)
        val total = HttpDownloader.download(client(), "http://127.0.0.1:$port/range", dest, payload.size.toLong()) { read ->
            // v0.2.7 修复点：回调值必须单调不减（累计语义），不再出现 1KB 式回落小值
            assertTrue("onBytes 应单调不减: $read < ${prev.get()}", read >= prev.get())
            prev.set(read)
            last.set(read)
        }
        assertEquals(payload.size.toLong(), total)
        assertEquals(payload.size.toLong(), last.get())
        assertEquals(payload.size.toLong(), dest.length())
    }

    @Test
    fun `single-stream download reports cumulative progress`() {
        val dest = File(tmpDir, "single.elf")
        val last = AtomicLong(0)
        val total = HttpDownloader.download(client(), "http://127.0.0.1:$port/full", dest, 0L) { read -> last.set(read) }
        assertEquals(payload.size.toLong(), total)
        assertEquals(payload.size.toLong(), last.get())
        assertEquals(payload.size.toLong(), dest.length())
    }

    @Test
    fun `truncated response is rejected`() {
        val dest = File(tmpDir, "trunc.elf")
        val ex = assertThrows(IOException::class.java) {
            HttpDownloader.downloadSingle(client(), "http://127.0.0.1:$port/truncated", dest) { }
        }
        // OkHttp 对 Content-Length 不足抛 "unexpected end of stream"，
        // 或由我们的完整性校验抛 "下载不完整" —— 两者都证明截断被拦截
        val msg = ex.message ?: ""
        assertTrue("应报下载不完整: $msg", msg.contains("下载不完整") || msg.contains("unexpected end of stream"))
    }

    @Test
    fun `elf magic detection`() {
        val good = File(tmpDir, "good.elf")
        good.writeBytes(byteArrayOf(0x7F) + "ELF".toByteArray() + ByteArray(16))
        val bad = File(tmpDir, "bad.elf")
        bad.writeBytes("<html>404</html>".toByteArray())
        val tiny = File(tmpDir, "tiny.elf")
        tiny.writeBytes(byteArrayOf(0x7F, 'E'.code.toByte()))

        assertTrue(ImageManager.isElfFile(good))
        assertFalse(ImageManager.isElfFile(bad))
        assertFalse(ImageManager.isElfFile(tiny))
        assertFalse(ImageManager.isElfFile(File(tmpDir, "missing.elf")))
    }
}
