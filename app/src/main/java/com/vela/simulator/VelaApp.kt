package com.vela.simulator

import android.app.Application
import com.vela.simulator.engine.ImageManager
import com.vela.simulator.engine.QemuRuntime
import com.vela.simulator.device.TemplateRepository

class VelaApp : Application() {
    lateinit var qemuRuntime: QemuRuntime
        private set
    lateinit var imageManager: ImageManager
        private set
    lateinit var templateRepo: TemplateRepository
        private set

    override fun onCreate() {
        super.onCreate()
        qemuRuntime = QemuRuntime(this)
        imageManager = ImageManager(this)
        templateRepo = TemplateRepository(this)
    }
}
