package com.vela.simulator.util

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局文件日志：
 *
 * 位置：<app外部专属目录>/logs/vela.log（无需任何存储权限，用户可通过
 * 系统文件管理器或"设置-分享日志"直接获取，路径形如
 * /storage/emulated/0/Android/data/com.vela.simulator/files/logs/vela.log）。
 *
 * - 超过 2MB 自动滚动为 vela.log.old
 * - 未捕获异常额外写入 logs/crash-<时间戳>.txt（tombstone）
 * - QEMU 会话输出同步落盘到 logs/session-*.log
 */
object FileLogger {

    private const val TAG = "VelaLog"
    private const val MAX_SIZE = 2_000_000L
    private const val MAX_CRASH_FILES = 5

    private val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileTs = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val lock = Any()
    private var logDirFile: File? = null
    private var bootId: String = fileTs.format(Date())

    val logDir: File?
        get() = logDirFile

    val logFile: File?
        get() = logDirFile?.let { File(it, "vela.log") }

    /** 初始化：尽早调用（Application.attachBaseContext） */
    fun init(context: Context) {
        if (logDirFile != null) return
        runCatching {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            val dir = File(base, "logs").apply { mkdirs() }
            logDirFile = dir
            rotateIfNeeded()
            // 同步 old 崩溃文件数量
            runCatching {
                dir.listFiles { f -> f.name.startsWith("crash-") }
                    ?.sortedByDescending { it.name }
                    ?.drop(MAX_CRASH_FILES)
                    ?.forEach { it.delete() }
            }
            i("app", "===== VELA Simulator 日志会话开始 $bootId =====")
            i("app", "device=${Build.MANUFACTURER} ${Build.MODEL} android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} abi=${Build.SUPPORTED_ABIS.firstOrNull()}")
        }
    }

    fun i(tag: String, msg: String) = write("I", tag, msg, null)
    fun w(tag: String, msg: String, tr: Throwable? = null) = write("W", tag, msg, tr)
    fun e(tag: String, msg: String, tr: Throwable? = null) = write("E", tag, msg, tr)

    private fun write(level: String, tag: String, msg: String, tr: Throwable?) {
        val line = "${ts.format(Date())} $level/$tag: ${msg.take(2000)}"
        when (level) {
            "I" -> Log.i(TAG, "[$tag] $msg")
            "W" -> Log.w(TAG, "[$tag] $msg", tr)
            else -> Log.e(TAG, "[$tag] $msg", tr)
        }
        synchronized(lock) {
            val f = logFile ?: return
            runCatching {
                FileWriter(f, true).use { w ->
                    w.append(line).append('\n')
                    if (tr != null) {
                        w.append(android.util.Log.getStackTraceString(tr)).append('\n')
                    }
                }
            }
        }
    }

    private fun rotateIfNeeded() {
        val f = logFile ?: return
        if (f.length() > MAX_SIZE) {
            val old = File(f.parentFile, "vela.log.old")
            old.delete()
            f.renameTo(old)
        }
    }

    // ---- 崩溃 ----

    /** 未捕获异常写入独立 tombstone，返回文件；始终返回非 null（失败时写入 vela.log） */
    fun saveCrash(thread: Thread, throwable: Throwable): File? {
        val dir = logDirFile
        val name = "crash-${fileTs.format(Date())}.txt"
        return runCatching {
            val f = if (dir != null) File(dir, name) else null
            val body = buildString {
                appendLine("===== CRASH $bootId =====")
                appendLine("time=${ts.format(Date())}")
                appendLine("thread=${thread.name}")
                appendLine("device=${Build.MANUFACTURER} ${Build.MODEL} android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
                appendLine()
                appendLine(Log.getStackTraceString(throwable))
            }
            if (f != null) f.writeText(body) else null
            e("crash", "未捕获异常 thread=${thread.name}", throwable)
            f
        }.getOrNull()
    }

    fun lastCrashFile(): File? =
        logDirFile?.listFiles { f -> f.name.startsWith("crash-") }
            ?.maxByOrNull { it.name }

    /** 已展示过的崩溃标记为已读（文件保留在日志目录） */
    fun dismissCrash(f: File) {
        runCatching { f.renameTo(File(f.parentFile, f.name + ".dismissed")) }
    }

    /** 读取最近崩溃摘要（首条异常行） */
    fun lastCrashSummary(): Pair<String, String>? {
        val f = lastCrashFile() ?: return null
        return runCatching {
            val lines = f.readLines()
            val cause = lines.firstOrNull { it.contains("Exception") || it.contains("Error") } ?: lines.lastOrNull() ?: ""
            f.name to cause.take(180)
        }.getOrNull()
    }

    // ---- QEMU 会话日志 ----

    private var sessionFile: File? = null

    fun beginSessionLog(templateName: String): File? {
        val dir = logDirFile ?: return null
        return runCatching {
            File(dir, "session-${fileTs.format(Date())}.log").also {
                it.writeText("===== 会话: $templateName =====\n")
                sessionFile = it
                i("session", "会话日志: ${it.name}")
            }
        }.getOrNull()
    }

    fun sessionLine(line: String) {
        synchronized(lock) {
            val f = sessionFile ?: return
            runCatching { FileWriter(f, true).use { it.append(line).append('\n') } }
        }
    }

    fun endSessionLog() {
        i("session", "会话结束")
        synchronized(lock) { sessionFile = null }
    }

    // ---- 读取 / 清理 ----

    /** 读最近 maxLines 行（供应用内查看） */
    fun readTail(maxLines: Int = 400): String {
        val f = logFile ?: return "（日志文件尚未创建）"
        return runCatching {
            val lines = f.readLines()
            if (lines.size <= maxLines) lines.joinToString("\n")
            else lines.takeLast(maxLines).joinToString("\n", prefix = "…（仅显示最近 $maxLines 行）\n")
        }.getOrDefault("（读取失败）")
    }

    fun clear() {
        synchronized(lock) {
            val dir = logDirFile ?: return
            runCatching {
                dir.listFiles()?.forEach { if (it.name != "vela.log") it.delete() }
                logFile?.writeText("")
            }
            i("app", "日志已清空")
        }
    }
}
