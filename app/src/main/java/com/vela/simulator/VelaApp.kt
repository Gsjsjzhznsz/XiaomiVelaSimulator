package com.vela.simulator

import android.app.Application
import android.content.Context
import com.vela.simulator.device.TemplateRepository
import com.vela.simulator.engine.ImageManager
import com.vela.simulator.engine.QemuRuntime
import com.vela.simulator.util.CrashGuard
import com.vela.simulator.util.FileLogger

class VelaApp : Application() {
    lateinit var qemuRuntime: QemuRuntime
        private set
    lateinit var imageManager: ImageManager
        private set
    lateinit var templateRepo: TemplateRepository
        private set

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // 尽早初始化文件日志 + 崩溃兜底（在业务初始化之前）
        CrashGuard.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        FileLogger.i("app", "VelaApp.onCreate")
        runCatching {
            qemuRuntime = QemuRuntime(this)
            imageManager = ImageManager(this)
            templateRepo = TemplateRepository(this)
        }.onFailure {
            FileLogger.e("app", "组件初始化失败", it)
        }
    }
}
