package com.vela.simulator.device

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 设备模板 —— 描述一台 VELA 可穿戴设备的完整参数，
 * 同时包含用于 QEMU 仿真的映射参数。全部字段均可编辑，
 * 用户可另存为自定义模板。
 */
@Serializable
data class DeviceTemplate(
    val id: String,
    val name: String,
    val brand: String = "Xiaomi",
    val category: String = CATEGORY_WATCH, // watch | band
    val releaseYear: Int = 2024,
    val os: String = "Xiaomi VELA OS",
    val screen: ScreenSpec,
    val hardware: HardwareSpec,
    val features: List<String> = emptyList(),
    val qemu: QemuSpec,
    val ui: UiSpec = UiSpec(),
    /** 镜像清单条目 id（v2.1）：非空时设备详情页优先使用该固件条目（如 APK 内置的 vapp 演示固件） */
    val imageId: String = "",
    /** 参数说明（例如"公开资料整理的可编辑预设"） */
    val note: String = "",
) {
    @Serializable
    data class ScreenSpec(
        /** round | rect */
        val shape: String = SHAPE_ROUND,
        val sizeInch: Double = 1.43,
        val width: Int = 466,
        val height: Int = 466,
        val dpi: Int = 326,
        /** 表身边框粗细（渲染预览用, px 相对屏幕宽） */
        val bezelPx: Int = 10,
        val bezelColor: String = "#1C1C1E",
    ) {
        companion object { const val SHAPE_ROUND = "round"; const val SHAPE_RECT = "rect" }
        val isRound get() = shape == SHAPE_ROUND
        val aspect get() = width.toFloat() / height
    }

    @Serializable
    data class HardwareSpec(
        /** 真机 CPU 架构类别描述（展示用） */
        val cpuArch: String = "Cortex-A7",
        val ramMb: Int? = null,     // 未公开时为 null，UI 显示"未公开"
        val storageMb: Int? = null,
        val batteryMah: Int? = null,
    )

    @Serializable
    data class QemuSpec(
        /** QEMU 机器: virt (Cortex-A) | mps2-an500 (Cortex-M7) | mps2-an521 (Cortex-M33) */
        val machine: String = MACHINE_VIRT,
        val cpu: String = "cortex-a7",
        val smp: Int = 1,
        /** 虚拟机内存（仅 virt 支持自定义），MPS2 机器内存固定 */
        val memoryMb: Int = 256,
        /** 镜像文件名（位于应用 images 目录），与 manifest 中的 defaultKernel 对应 */
        val kernel: String = KERNEL_OPENVELA_ARMV7A_FULL,
        /** 附加 QEMU 参数（原样追加） */
        val extraArgs: String = "",
        /** 触摸输入: virt 机器挂载 virtio-tablet-device 绝对指针设备（virtio-mmio），画面视图可触摸 */
        val touchInput: Boolean = true,
        /** NSH 提示符出现后自动执行的命令（如 lvgldemo 启动图形界面，空 = 不自动执行）；多条命令用 ";" 分隔 */
        val autoCommand: String = "",
        /**
         * 引导模式（v2.1，vapp 固件新增）：
         *  - BOOT_ELF: 经 -kernel 直接引导 ELF（旧 openvela 固件，默认，行为不变）；
         *  - BOOT_RAW: 原始 bin 经 -device loader 装载（QEMU 10/11 的 -kernel ELF
         *    loader 对 vapp 固件静默失败，实测必须用 raw bin + 显式设 PC 入口）。
         */
        val bootMode: String = BOOT_ELF,
        /** BOOT_RAW 引导入口 PC（如 vapp 固件 0x6002e0）；0 = 不显式设 PC */
        val entryAddr: Long = 0,
        /** 数据盘镜像文件名（位于 images 目录，非空则挂载 virtio-blk-device；vapp 固件资源包在此） */
        val dataImg: String = "",
    ) {
        companion object {
            const val MACHINE_VIRT = "virt"
            const val MACHINE_MPS2_AN500 = "mps2-an500"
            const val MACHINE_MPS2_AN521 = "mps2-an521"
            const val BOOT_ELF = "elf"
            const val BOOT_RAW = "raw"
            const val KERNEL_OPENVELA_ARMV7A_NSH = "openvela-qemu-armv7a-nsh.elf"
            const val KERNEL_OPENVELA_ARMV7A_FULL = "openvela-qemu-armv7a-full.elf"
            const val KERNEL_OPENVELA_MPS2_AN500_NSH = "openvela-mps2-an500-nsh.elf"
            const val KERNEL_OPENVELA_MPS2_AN521_NSH = "openvela-mps2-an521-nsh.elf"
        }
        val isCortexM get() = machine.startsWith("mps2")
    }

    @Serializable
    data class UiSpec(
        val accentColor: String = "#FF6900",
        val watchfaceStyle: String = "digital", // digital | analog | minimal
    )

    /** 特性 key -> 中文名 */
    val featureLabels: List<String>
        get() = features.map { FEATURE_LABELS[it] ?: it }

    /**
     * 胶囊形判定（v0.2.4）：非圆形且屏幕高宽比 >= 1.8 的竖长屏
     * （小米手环 9/10/11、Redmi Band 等均为胶囊/长条屏，观感为全圆角胶囊）。
     * 渲染层据此把表身画成胶囊、屏幕画成大圆角。
     */
    val isCapsule: Boolean
        get() = !screen.isRound &&
            screen.height.toFloat() / screen.width.toFloat() >= 1.8f

    /** 屏幕形状中文描述（圆形 / 胶囊 / 方形） */
    val shapeLabel: String
        get() = when {
            screen.isRound -> "圆形"
            isCapsule -> "胶囊"
            else -> "方形"
        }

    /** 适合的默认镜像清单条目 id（模板可用 imageId 显式指定；缺省按机器类型推导） */
    val suggestedImageId: String
        get() = when {
            imageId.isNotBlank() -> imageId
            qemu.machine == QemuSpec.MACHINE_VIRT -> "openvela-armv7a-full"
            else -> "openvela-${qemu.machine}-nsh"
        }

    companion object {
        const val CATEGORY_WATCH = "watch"
        const val CATEGORY_BAND = "band"

        val FEATURE_LABELS = mapOf(
            "nfc" to "NFC", "esim" to "eSIM", "gps" to "GPS", "bluetooth" to "蓝牙",
            "wifi" to "WLAN", "heart_rate" to "心率", "spo2" to "血氧", "speaker" to "扬声器",
            "microphone" to "麦克风", "compass" to "罗盘", "altimeter" to "气压高度计",
            "ambient_light" to "环境光", "biometric" to "生物识别", "temperature" to "体温",
        )

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }

        fun fromJson(text: String): DeviceTemplate? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
    }

    fun toJson(): String = json.encodeToString(serializer(), this)
}
