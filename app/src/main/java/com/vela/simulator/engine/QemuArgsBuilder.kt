package com.vela.simulator.engine

import com.vela.simulator.device.DeviceTemplate
import java.io.File
import java.net.ServerSocket

/**
 * 根据设备模板构建 QEMU 命令行。
 *
 * 两类机器：
 *  - virt          : Cortex-A 手表/大屏设备，-M virt -cpu cortex-a7，内存可调
 *  - mps2-an500/521: Cortex-M7/M33 手环类 MCU，内存固定（不可用 -m）
 *
 * 输出通道：
 *  - 串口 nsh  : -serial tcp:127.0.0.1:<port>,server,nowait（App 作为客户端连接）
 *  - VNC 画面  : -vnc 127.0.0.1:<port>（可选，画面视图）
 */
object QemuArgsBuilder {

    data class Plan(
        val command: List<String>,
        val serialPort: Int,
        val vncPort: Int,   // 0 = 未启用
        val kernelPath: File,
        val displayMachine: String,
    )

    fun build(
        template: DeviceTemplate,
        imagesDir: File,
        runtimePrefixUsr: File,
        enableVnc: Boolean = true,
    ): Plan {
        val q = template.qemu
        val kernel = File(imagesDir, q.kernel)
        check(kernel.exists()) { "内核镜像不存在: ${kernel.name}，请先在模板页下载或导入" }

        val serialPort = freePort()
        val vncPort = if (enableVnc) freePort() else 0

        val args = mutableListOf(File(runtimePrefixUsr, "bin/qemu-system-arm").absolutePath)

        when (q.machine) {
            DeviceTemplate.QemuSpec.MACHINE_VIRT -> {
                args += listOf("-M", "virt", "-cpu", q.cpu)
                args += listOf("-smp", q.smp.coerceIn(1, 8).toString())
                args += listOf("-m", q.memoryMb.coerceIn(64, 2048).toString() + "M")
                // 触摸输入: 绝对指针平板设备（VNC PointerEvent -> virtio input -> guest）
                if (q.touchInput) args += listOf("-device", "virtio-tablet-pci")
            }
            DeviceTemplate.QemuSpec.MACHINE_MPS2_AN500 -> {
                args += listOf("-M", "mps2-an500", "-cpu", "cortex-m7")
            }
            DeviceTemplate.QemuSpec.MACHINE_MPS2_AN521 -> {
                args += listOf("-M", "mps2-an521", "-cpu", "cortex-m33")
            }
            else -> args += listOf("-M", q.machine, "-cpu", q.cpu)
        }

        // 内核（ELF 直接引导）
        args += listOf("-kernel", kernel.absolutePath)

        // 无图形界面，显示走 VNC
        args += listOf("-display", "none", "-monitor", "none")

        // 串口 → 本地 TCP（nsh 控制台）
        args += listOf("-serial", "tcp:127.0.0.1:$serialPort,server,nowait")

        // VNC 帧缓冲
        if (vncPort > 0) args += listOf("-vnc", "127.0.0.1:$vncPort")

        // QEMU 数据文件搜索路径（roms / keymaps），重定向到我们的 prefix
        args += listOf("-L", File(runtimePrefixUsr, "share/qemu").absolutePath)

        // 附加参数
        if (q.extraArgs.isNotBlank()) args += q.extraArgs.trim().split(' ').filter { it.isNotBlank() }

        return Plan(
            command = args,
            serialPort = serialPort,
            vncPort = vncPort,
            kernelPath = kernel,
            displayMachine = q.machine,
        )
    }

    private fun freePort(): Int =
        ServerSocket(0).use { it.localPort }
}
