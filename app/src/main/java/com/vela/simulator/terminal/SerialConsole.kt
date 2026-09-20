package com.vela.simulator.terminal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 串口控制台：通过本地 TCP 连接 QEMU 虚拟串口（nsh shell）。
 * 独立于 QEMU 进程存在，便于重建连接。
 */
class SerialConsole {

    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: OutputStream? = null

    val isConnected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    /** 收到的数据回调（原始字节，可能按行分割不完整） */
    @Volatile
    var onText: ((String) -> Unit)? = null

    private var pumpThread: Thread? = null

    fun connect(port: Int): Boolean = runCatching {
        close()
        val s = Socket()
        s.connect(InetSocketAddress("127.0.0.1", port), 1500)
        s.tcpNoDelay = true
        socket = s
        writer = s.getOutputStream()
        reader = s.getInputStream().bufferedReader()
        startPump(s.getInputStream())
        true
    }.getOrDefault(false)

    private fun startPump(ins: InputStream) {
        pumpThread?.interrupt()
        pumpThread = Thread {
            val buf = CharArray(512)
            try {
                val r = reader ?: return@Thread
                while (!Thread.currentThread().isInterrupted && isConnected) {
                    val n = r.read(buf)
                    if (n < 0) break
                    val text = String(buf, 0, n)
                    onText?.invoke(text)
                }
            } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
    }

    fun send(text: String) {
        runCatching { writer?.write(text.toByteArray(Charsets.US_ASCII)); writer?.flush() }
    }

    /** 发送一行命令（nsh） */
    fun sendLine(cmd: String) = send(cmd.trim() + "\r\n")

    fun close() {
        runCatching { socket?.close() }
        socket = null; reader = null; writer = null
    }
}
