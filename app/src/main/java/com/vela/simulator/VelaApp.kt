package com.vela.simulator

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import com.vela.simulator.device.TemplateRepository
import com.vela.simulator.engine.ImageManager
import com.vela.simulator.engine.QemuRuntime
import com.vela.simulator.config.VelaConfig
import com.vela.simulator.util.CrashGuard
import com.vela.simulator.util.FileLogger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * 预测性返回手势开关（BandQQ / KernelSU 同款实现）：
 * - manifest 不写 android:enableOnBackInvokedCallback 静态开关（写了会覆盖运行时
 *   设置，导致主题设置里的开关无效）；
 * - Android 14+ 用隐藏 API ApplicationInfo.setEnableOnBackInvokedCallback 按用户
 *   设置动态开关，HiddenApiBypass 解除 hidden API 访问限制；
 * - 本应用 targetSdk 固定 28（W^X，QEMU 需在数据目录执行），系统级预测性返回
 *   的跟手进度流是否下发以设备实际行为为准；关闭/不支持的设备上返回退化为
 *   离散关闭，边缘手势兜底仍提供 HyperOS 风格跟手预览。
 */
class VelaApp : Application() {
    lateinit var qemuRuntime: QemuRuntime
        private set
    lateinit var imageManager: ImageManager
        private set
    lateinit var templateRepo: TemplateRepository
        private set
    lateinit var config: VelaConfig
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
            config = VelaConfig(this)
            qemuRuntime = QemuRuntime(this)
            imageManager = ImageManager(this)
            templateRepo = TemplateRepository(this)
        }.onFailure {
            FileLogger.e("app", "组件初始化失败", it)
        }
        applyPredictiveBackFlag()
    }

    /** 幂等：Application.onCreate 与 MainActivity.onCreate 都可调用（温启动恢复设置） */
    fun applyPredictiveBackFlag() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val enable = runBlocking {
            runCatching { config.observePredictiveBack().first() }.getOrDefault(true)
        }
        runCatching {
            HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/content/pm/ApplicationInfo;->setEnableOnBackInvokedCallback",
            )
            setEnableOnBackInvokedCallback(applicationInfo, enable)
        }.onFailure { FileLogger.w("app", "预测性返回开关设置失败: ${it.message}") }
    }

    companion object {
        /** 隐藏 API 不在公开 SDK 中，编译期只能反射调用（KernelSU 同款） */
        fun setEnableOnBackInvokedCallback(appInfo: ApplicationInfo, enable: Boolean) {
            runCatching {
                val method = ApplicationInfo::class.java.getDeclaredMethod(
                    "setEnableOnBackInvokedCallback", Boolean::class.javaPrimitiveType,
                )
                method.isAccessible = true
                method.invoke(appInfo, enable)
            }
        }
    }
}
