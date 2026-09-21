package com.vela.simulator.util

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * 多线程分段下载器（v0.2.4 下载提速）：
 * - 对 >= SEGMENT_MIN_SIZE 且服务器支持 Range(206) 的文件，切成 SEGMENT_COUNT 段
 *   并行拉取（FileChannel positioned write 无锁落盘），大文件速度提升数倍；
 * - 不支持 Range 或小文件自动退化为单流下载；
 * - onBytes 回传【累计已接收字节数】（v0.2.7 修复：此前回传单块新增值，
 *   调用方误当累计值显示，UI 出现 "xxx 1KB" 的异常小数字）。
 * 全部方法阻塞 IO，必须在 Dispatchers.IO 中调用。
 */
object HttpDownloader {

    /** 超过该大小的文件才启用分段（小文件分段反而增加握手开销） */
    const val SEGMENT_MIN_SIZE: Long = 1536L * 1024L

    /** 单文件分段数 */
    const val SEGMENT_COUNT: Int = 4

    /**
     * 分段下载入口：探测 Range 支持 → 分段并行 / 单流退化。
     * expectedSize 仅为预估值（用于分段决策与校验），可为 0。
     */
    fun download(
        client: OkHttpClient,
        url: String,
        dest: File,
        expectedSize: Long,
        onBytes: (Long) -> Unit,
    ): Long {
        val ranged = if (expectedSize >= SEGMENT_MIN_SIZE) probeRangeSupport(client, url) else false
        return if (ranged) {
            downloadSegmented(client, url, dest, expectedSize, onBytes)
        } else {
            downloadSingle(client, url, dest, onBytes)
        }
    }

    /** Range 支持探测：发 bytes=0-0 探针，返回 206 即支持 */
    private fun probeRangeSupport(client: OkHttpClient, url: String): Boolean = runCatching {
        val req = Request.Builder().url(url).header("Range", "bytes=0-0").build()
        client.newCall(req).execute().use { it.code == 206 }
    }.getOrDefault(false)

    /** 单流下载（退化路径），返回接收总字节数 */
    fun downloadSingle(
        client: OkHttpClient,
        url: String,
        dest: File,
        onBytes: (Long) -> Unit,
    ): Long {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: $url")
            val body = resp.body ?: throw IOException("空响应体: $url")
            val contentLength = body.contentLength()
            var read = 0L
            dest.outputStream().use { out ->
                val src = body.byteStream()
                val buf = ByteArray(128 * 1024)
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    read += n
                    onBytes(read) // v0.2.7：累计值而非单块增量
                }
            }
            if (read == 0L) throw IOException("响应体为空: $url")
            // v0.2.7：半截响应完整性校验（连接中断/代理截断会得到残缺文件）
            if (contentLength > 0 && read != contentLength) {
                throw IOException("下载不完整: got $read, want $contentLength: $url")
            }
            return read
        }
    }

    /**
     * 分段并行下载：预分配文件长度，各段 RandomAccessFile Channel positioned write 落盘。
     * 任一段失败即抛异常（调用方负责删临时文件与换源重试）。
     */
    private fun downloadSegmented(
        client: OkHttpClient,
        url: String,
        dest: File,
        expectedSize: Long,
        onBytes: (Long) -> Unit,
    ): Long {
        val total = expectedSize.coerceAtLeast(1)
        val seg = total / SEGMENT_COUNT
        // v0.2.7：并发段原子累计，onBytes 回传全局累计进度。
        // 多线程直接回调时执行顺序可能与 addAndGet 返回值交错（进度短暂回跳），
        // 用水位+锁保证回调值严格单调。
        val received = AtomicLong(0)
        val reported = longArrayOf(-1L)
        val progressLock = Any()
        fun reportProgress() = synchronized(progressLock) {
            val r = received.get()
            if (r > reported[0]) {
                reported[0] = r
                onBytes(r)
            }
        }
        RandomAccessFile(dest, "rw").use { raf ->
            raf.setLength(total)
            val ch = raf.channel
            val errors = arrayOfNulls<Exception>(SEGMENT_COUNT)
            val threads = (0 until SEGMENT_COUNT).map { i ->
                val start = i * seg
                val end = if (i == SEGMENT_COUNT - 1) total - 1 else (i + 1) * seg - 1
                Thread {
                    try {
                        val req = Request.Builder().url(url).header("Range", "bytes=$start-$end").build()
                        client.newCall(req).execute().use { resp ->
                            if (resp.code != 206) throw IOException("分段下载返回 HTTP ${resp.code}")
                            val ins = resp.body!!.byteStream()
                            val buf = ByteArray(128 * 1024)
                            var pos = start
                            while (true) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                // v0.2.5 修复：positioned write 可能部分写入（返回值此前被忽略），
                                // 必须循环写满整个缓冲，否则后续数据错位导致文件损坏
                                val bb = ByteBuffer.wrap(buf, 0, n)
                                while (bb.hasRemaining()) pos += ch.write(bb, pos)
                                received.addAndGet(n.toLong())
                                reportProgress()
                            }
                            if (pos != end + 1) throw IOException("分段长度不符: got ${pos - start}, want ${end - start + 1}")
                        }
                    } catch (e: Exception) {
                        errors[i] = e
                    }
                }.also { it.start() }
            }
            threads.forEach { it.join() }
            errors.firstOrNull { it != null }?.let { throw it as Exception }
            // 收尾：确保最终回调恰好到达 total（覆盖极小概率的交错遗漏）
            reportProgress()
        }
        return total
    }
}
