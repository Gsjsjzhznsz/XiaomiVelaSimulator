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
 *  - VNC 画面  : -vnc 127.0.0.1:<display>（可选，画面视图，实际监听 display+5900）
 *
 * v0.2.6 修复（无法启动根因，实测自用户日志 session-20260921-070622）：
 *  Termux 版 QEMU 的 -vnc 解析缺陷：无论单/双冒号，冒号后的数字都被当作 display 号，
 *  实际监听端口 = 5900 + 该数字；双冒号直连语法（host::port）反而会拼出畸形地址
 *  "host::(port+5900)" 导致 "address resolution failed" 直接退出（退出码 1）。
 *  因此统一改用单冒号 display 形式：欲监听端口 P（≥5900），传 "host:(P-5900)"，
 *  QEMU 实际绑定 P，VNC 客户端连接 P，两端一致。
 *
 * v0.2.5 修复：
 *  - QEMU 二进制路径不再硬编码 bin/qemu-system-arm，由运行时探测结果传入
 *    （headless 包安装后名为 qemu-system-arm-headless，硬编码导致 exec 失败）。
 */
object QemuArgsBuilder {

    /** QEMU VNC display 基准端口：-vnc host:N 实际监听 N+5900 */
    const val VNC_BASE_PORT = 5900

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
        qemuBin: File,
        enableVnc: Boolean = true,
    ): Plan {
        val q = template.qemu
        val kernel = File(imagesDir, q.kernel)
        check(kernel.exists()) { "内核镜像不存在: ${kernel.name}，请先在模板页下载或导入" }

        val serialPort = freePort()
        val vncPort = if (enableVnc) freeVncPort() else 0

        val args = mutableListOf(qemuBin.absolutePath)

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

        // VNC 帧缓冲：单冒号 display 形式，传 (P-5900)，QEMU 实际绑定 P
        // （详见类注释 v0.2.6：双冒号在 Termux 版 QEMU 上会直接启动失败）
        if (vncPort > 0) args += listOf("-vnc", "127.0.0.1:${vncPort - VNC_BASE_PORT}")

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

    /**
     * 取一个 ≥5900 的空闲端口作为 VNC 监听端口。
     * display 号必须 ≥0（端口 - 5900）且 ≤59635（5900+display ≤ 65535），
     * Linux 临时端口段默认 32768-60999，循环几十次内必命中。
     */
    private fun freeVncPort(): Int {
        repeat(64) {
            val p = freePort()
            if (p >= VNC_BASE_PORT && p - VNC_BASE_PORT <= 59635) return p
        }
        return VNC_BASE_PORT + 1000 // 极端兑底：固定 display=1000（冲突概率极低）
    }
}
