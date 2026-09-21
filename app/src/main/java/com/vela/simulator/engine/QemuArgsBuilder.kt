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
 *  - 串口 nsh  : -serial tcp:127.0.0.1:<port>,server（App 作为客户端连接）
 *  - VNC 画面  : -vnc 127.0.0.1:<display>（可选，画面视图，实际监听 display+5900）
 *
 * v0.3.0 修复（无画面根因，官方 VSCode 扩展有画面而本应用黑屏）:
 *  1) 固件原因: 此前使用的 qemu-armv7a:nsh 是纯控制台固件（无图形栈），
 *     VNC 连上也没有任何内容可渲染。已切换 openvela 官方 full 配置固件
 *     （LVGL + virtio-gpu + virtio-input），配合串口自动执行 lvgldemo 启动界面；
 *  2) 设备原因: -M virt 默认无虚拟显卡，VNC 只能输出黑帧。
 *     新增 -device virtio-gpu-device 接入 guest 的 virtio-gpu 帧缓冲驱动
 *     （mmio 版无 romfile 属性，勿加 romfile=，实测会报 Property not found 退出）；
 *  3) 触摸修正: NuttX 侧仅有 virtio-mmio 总线驱动（无 virtio-pci），
 *     virtio-tablet-pci 改为 virtio-tablet-device（mmio），否则 guest 看不到输入设备；
 *  4) ROM 兜底: 默认网络设备 virtio-net-pci 需要 efi-virtio.rom，Termux 可能不
 *     附带 → -nic none 关闭默认网卡但保留其余默认设备（VNC 输入控制台依赖，
 *     -nodefaults 会导致指针事件无法路由，实测复现）；
 *  5) open-vela 官方模拟器路线即虚拟显卡 + 触摸输入 + VNC（官方 FAQ: 手环/手表
 *     统一使用虚拟平台模拟），本修改对齐该体验。
 *
 * v0.2.7 修复（无画面/无输出根因，实测自用户日志 session-20260921-075607）：
 *  原 -serial ...,server,nowait 模式下，客户端连接前的串口输出直接丢失。
 *  Cortex-M（mps2）引导极快（毫秒级），nx_start 全部输出在 App 首次连接（500ms 后）
 *  之前就已发出 → 串口看起来“零输出/没反应”。去掉 nowait 后 QEMU 会阻塞等待
 *  串口客户端就绪才开始执行 guest，启动输出零丢失（对 Cortex-A virt 同样受益）。
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
                // 虚拟显卡（v0.3.0）: guest 的 virtio-gpu 帧缓冲驱动通过它渲染画面，
                // VNC 服务该帧缓冲。注意 mmio 版 virtio-gpu-device 没有 romfile 属性
                // （PCI 设备才有，实测加 romfile= 直接报 Property not found 退出）。
                // v0.3.1: 按设备模板屏幕参数注入 xres/yres —— 单一 full 固件服务
                // 全部机型（bandQQ 同款思路）：guest virtio-gpu 驱动经
                // VIRTIO_GPU_CMD_GET_DISPLAY_INFO 跟随宿主 scanout 尺寸，
                // LVGL/lvgldemo 再按 fb0 varinfo 自适应。不注入时 QEMU 用默认
                // 1024x768(4:3)，圆表 letterbox 后被裁成“椭圆”（真机反馈）。
                // 注入宽度向上对齐到 16 像素：QEMU VNC 服务器按脏矩形位图粒度
                // （VNC_DIRTY_PIXELS_PER_BIT=16，见 ui/vnc.h）上报表面宽度
                // （高度原样上报），fb 宽非 16 倍数（如 466）时 VNC 会报 480
                // 并右侧填充 → 触摸归一化链路产生 ~3% 偏差。宽度预对齐后
                // VNC 表面/guest 帧缓冲/触摸三者严格一致。
                val xres = template.screen.width.coerceIn(64, 4096)
                val yres = template.screen.height.coerceIn(64, 4096)
                val xr = (xres + 15) / 16 * 16
                args += listOf("-device", "virtio-gpu-device,xres=$xr,yres=$yres")
                // 触摸输入（v0.3.0 修正为 mmio 总线: NuttX 无 virtio-pci 驱动）
                // VNC PointerEvent(绝对坐标) → virtio-tablet → guest /dev/input0
                if (q.touchInput) args += listOf("-device", "virtio-tablet-device")
                // 关闭默认网络设备（v0.3.0）: 默认 virtio-net-pci 需要 efi-virtio.rom，
                // Termux headless 包可能不附带 pc-bios ROM → QEMU 启动即退出。
                // -nic none 保留其余默认设备（VNC 输入控制台依赖它们，-nodefaults 会让
                // VNC 指针事件无法路由，实测复现）
                args += listOf("-nic", "none")
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

        // 串口 → 本地 TCP（nsh 控制台）。
        // v0.2.7：去掉 nowait —— QEMU 阻塞等待串口客户端连接后才开始跑 guest，
        // 防 Cortex-M 毫秒级引导的 nx_start 输出在 App 连接前全部丢失
        args += listOf("-serial", "tcp:127.0.0.1:$serialPort,server")

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
