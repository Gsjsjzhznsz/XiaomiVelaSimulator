package com.vela.simulator.util

import android.content.Context

/**
 * 崩溃兜底：任何未捕获异常都会写入日志目录（tombstone），
 * 下次启动时首页会展示"上次异常退出"提示，便于用户反馈问题。
 */
object CrashGuard {

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        FileLogger.init(context)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { FileLogger.saveCrash(thread, throwable) }
            // 交给系统默认处理（弹崩溃框 / 记录 logcat），保持行为一致
            prev?.uncaughtException(thread, throwable)
        }
        FileLogger.i("app", "CrashGuard 已安装")
    }
}
