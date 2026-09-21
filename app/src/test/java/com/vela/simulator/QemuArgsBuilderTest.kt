package com.vela.simulator.engine

import com.vela.simulator.device.DeviceTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * QemuArgsBuilder 单元测试（v0.3.0）：
 * 锁定「有画面 + 有触控」的两个关键 QEMU 参数 —— virtio-gpu 显示设备与
 * virtio-mmio 触摸设备（此前 virtio-tablet-pci 因 guest 无 PCI 总线驱动而失效）。
 */
class QemuArgsBuilderTest {

    private fun virtTemplate(kernel: String = "openvela-qemu-armv7a-full.elf") =
        DeviceTemplate(
            id = "t1",
            name = "测试手表",
            screen = DeviceTemplate.ScreenSpec(),
            hardware = DeviceTemplate.HardwareSpec(),
            qemu = DeviceTemplate.QemuSpec(
                machine = DeviceTemplate.QemuSpec.MACHINE_VIRT,
                cpu = "cortex-a7",
                memoryMb = 512,
                kernel = kernel,
            ),
        )

    private fun mps2Template(machine: String) = DeviceTemplate(
        id = "t2",
        name = "测试手环",
        screen = DeviceTemplate.ScreenSpec(),
        hardware = DeviceTemplate.HardwareSpec(),
        qemu = DeviceTemplate.QemuSpec(
            machine = machine,
            kernel = "openvela-mps2-an500-nsh.elf",
            touchInput = false,
        ),
    )

    private fun newImagesDir(kernel: String): File {
        val dir = Files.createTempDirectory("images").toFile()
        // 伪造有效内核（≥1MB 含 ELF 魔数）：builder 只检查存在性
        val f = File(dir, kernel).apply { writeBytes(ByteArray(64)) }
        assertTrue(f.exists())
        return dir
    }

    @Test
    fun `virt machine includes virtio gpu with empty romfile`() {
        val images = newImagesDir("openvela-qemu-armv7a-full.elf")
        val plan = QemuArgsBuilder.build(
            virtTemplate(), images,
            runtimePrefixUsr = File("/tmp"), qemuBin = File("/tmp/qemu-system-arm"),
        )
        val idx = plan.command.indexOf("-device")
        assertTrue("缺少 -device 参数", idx >= 0)
        assertEquals("virtio-gpu-device", plan.command[idx + 1])
    }

    @Test
    fun `virt machine disables default nic without nodefaults`() {
        val images = newImagesDir("openvela-qemu-armv7a-full.elf")
        val plan = QemuArgsBuilder.build(
            virtTemplate(), images,
            runtimePrefixUsr = File("/tmp"), qemuBin = File("/tmp/qemu-system-arm"),
        )
        // 默认网卡 virtio-net-pci 依赖 efi-virtio.rom（Termux 可能缺失）→ -nic none 关闭
        val i = plan.command.indexOf("-nic")
        assertTrue("缺少 -nic none（默认网卡 ROM 依赖会导致启动失败）", i > 0)
        assertEquals("none", plan.command[i + 1])
        // mmio 版 virtio-gpu-device 没有 romfile 属性（PCI 才有），加了会直接退出
        assertFalse(
            "virtio-gpu-device 禁止带 romfile 属性",
            plan.command.any { it.startsWith("virtio-gpu-device,") },
        )
        // -nodefaults 会让 VNC 输入控制台无绑定，指针事件无法路由 → 必须禁用
        assertFalse(
            "禁止 -nodefaults（会破坏 VNC 输入路由）",
            plan.command.contains("-nodefaults"),
        )
    }

    @Test
    fun `virt machine uses mmio tablet for touch`() {
        val images = newImagesDir("openvela-qemu-armv7a-full.elf")
        val plan = QemuArgsBuilder.build(
            virtTemplate(), images,
            runtimePrefixUsr = File("/tmp"), qemuBin = File("/tmp/qemu-system-arm"),
        )
        val tablet = plan.command.indexOf("virtio-tablet-device")
        assertTrue("触摸设备必须是 virtio-mmio 总线的 virtio-tablet-device", tablet > 0)
        assertFalse(
            "禁止 PCI 总线触摸设备（NuttX 无 virtio-pci 驱动）",
            plan.command.contains("virtio-tablet-pci"),
        )
    }

    @Test
    fun `vnc display uses single colon display form`() {
        val images = newImagesDir("openvela-qemu-armv7a-full.elf")
        val plan = QemuArgsBuilder.build(
            virtTemplate(), images,
            runtimePrefixUsr = File("/tmp"), qemuBin = File("/tmp/qemu-system-arm"),
        )
        val i = plan.command.indexOf("-vnc")
        assertTrue(i > 0)
        val display = plan.command[i + 1]
        assertFalse("禁用双冒号端口形式（Termux QEMU 解析缺陷）", display.contains("::"))
        val d = display.substringAfterLast(":").toInt()
        assertEquals(plan.vncPort, QemuArgsBuilder.VNC_BASE_PORT + d)
    }

    @Test
    fun `mps2 machines skip display and touch devices`() {
        val images = newImagesDir("openvela-mps2-an500-nsh.elf")
        val plan = QemuArgsBuilder.build(
            mps2Template(DeviceTemplate.QemuSpec.MACHINE_MPS2_AN500), images,
            runtimePrefixUsr = File("/tmp"), qemuBin = File("/tmp/qemu-system-arm"),
        )
        assertFalse(plan.command.contains("virtio-gpu-device"))
        assertFalse(plan.command.contains("virtio-tablet-device"))
        assertEquals(0, plan.vncPort % 1) // vncPort 有效
    }
}
