package com.vela.simulator

import com.vela.simulator.device.DeviceTemplate
import com.vela.simulator.engine.QemuSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 内置模板一致性回归测试。
 *
 * v0.3.3：统一 virt 机器 + 图形固件（修复「手环模板用 mps2 纯控制台固件 →
 * VNC 永远 Display output is not active」）。
 * v2.1.0：新增内置 vapp 演示机（raw 引导自有 nuttx.bin）。
 * v2.2.0：旧 openvela ELF 固件链路全部下线，16 款内置模板统一 raw 引导
 * 内置 vapp 固件（NuttX + QuickJS + LVGL），自动 mount 数据盘并启动 vapp。
 *
 * 单一通用固件 + 按模板注入分辨率 = 「一个镜像模拟全部设备」思路。
 */
class TemplateConsistencyTest {

    private val templatesDir = File("src/main/assets/templates")

    private fun loadTemplates(): List<DeviceTemplate> {
        val files = templatesDir.listFiles { f -> f.extension == "json" }.orEmpty()
        assertTrue("内置模板目录不存在: ${templatesDir.absolutePath}", files.isNotEmpty())
        return files.mapNotNull { f ->
            DeviceTemplate.fromJson(f.readText()) ?: error("模板解析失败: ${f.name}")
        }
    }

    @Test
    fun `all builtin templates use unified vapp raw boot firmware`() {
        val templates = loadTemplates()
        assertTrue("内置模板数量异常（期望 16）: ${templates.size}", templates.size == 16)
        val bad = templates.filter {
            it.qemu.machine != DeviceTemplate.QemuSpec.MACHINE_VIRT ||
                it.qemu.bootMode != DeviceTemplate.QemuSpec.BOOT_RAW ||
                it.qemu.kernel != "nuttx.bin" ||
                it.qemu.dataImg != "data.img" ||
                it.qemu.entryAddr != 0x6002e0L ||
                !it.qemu.autoCommand.contains("vapp hap://app/")
        }
        assertTrue(
            "以下模板未使用内置 vapp 固件链路（raw @0x6002e0 + data.img + vapp 启动命令）: " +
                bad.joinToString { "${it.id}(${it.qemu.bootMode}/${it.qemu.kernel}/0x${it.qemu.entryAddr.toString(16)})" },
            bad.isEmpty(),
        )
    }

    @Test
    fun `raw boot entry pc matches firmware verified value`() {
        // v2.1.2 教训：entryAddr 写错（0x6010e0）= CPU 空转，串口/VNC 全静默永久黑屏。
        // 桌面 A/B 实证唯一正确值 0x6002e0（固件 sha256 79efedee…）。
        val bad = loadTemplates().filter { it.qemu.entryAddr != 0x6002e0L }
        assertTrue(
            "以下模板 raw 引导入口 PC 不是固件实测的 0x6002e0: " +
                bad.joinToString { "${it.id}(0x${it.qemu.entryAddr.toString(16)})" },
            bad.isEmpty(),
        )
    }

    @Test
    fun `effectiveAutoCommand replaces vapp package id only`() {
        // v2.2 工坊启动任意 rpk：仅替换 vapp URL 包名，mount 命令原样保留
        val cmd = "mount -t vfat /dev/virtblk0 /data; vapp hap://app/com.vela.demo"
        assertEquals(
            "mount -t vfat /dev/virtblk0 /data; vapp hap://app/com.example.game",
            QemuSession.effectiveAutoCommand(cmd, "com.example.game"),
        )
        // 未指定包 = 原样返回（含 null 与空白）
        assertEquals(cmd, QemuSession.effectiveAutoCommand(cmd, null))
        assertEquals(cmd, QemuSession.effectiveAutoCommand(cmd, ""))
        // 模板命令不含 vapp URL 时原样返回（旧式 lvgldemo 自定义模板）
        assertEquals("lvgldemo", QemuSession.effectiveAutoCommand("lvgldemo", "com.x.y"))
        // 包名含连字符/下划线合法
        assertEquals(
            "vapp hap://app/com.foo-bar.baz_q1",
            QemuSession.effectiveAutoCommand("vapp hap://app/com.vela.demo", "com.foo-bar.baz_q1"),
        )
    }

    @Test
    fun `all builtin templates auto launch vapp`() {
        val noCmd = loadTemplates().filter { it.qemu.autoCommand.isBlank() }
        assertTrue(
            "以下模板未配置自动启动命令（画面将停留在 nsh 提示符）: " +
                noCmd.joinToString { it.id },
            noCmd.isEmpty(),
        )
        val noVapp = loadTemplates().filter { !it.qemu.autoCommand.contains("vapp hap://app/") }
        assertTrue(
            "以下内置模板未自动启动 vapp 快应用: " + noVapp.joinToString { it.id },
            noVapp.isEmpty(),
        )
    }

    @Test
    fun `band resolutions survive width alignment`() {
        // 宽度对齐（仅 ELF 引导模板适用，raw 引导已跳过对齐）；对齐后不得超出合理范围
        // （否则 VncDisplayView 变形/触摸归一化失真）
        val wrong = loadTemplates().filter {
            val aligned = (it.screen.width + 15) / 16 * 16
            aligned - it.screen.width >= 16 || it.screen.height !in 64..4096
        }
        assertTrue(
            "以下模板分辨率对齐后偏差过大: " + wrong.joinToString { it.id },
            wrong.isEmpty(),
        )
    }
}
