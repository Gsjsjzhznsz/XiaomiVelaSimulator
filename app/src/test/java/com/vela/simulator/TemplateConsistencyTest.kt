package com.vela.simulator

import com.vela.simulator.device.DeviceTemplate
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 内置模板一致性回归测试（v0.3.3）：
 *
 * 锁定「手环类模板使用 mps2-an500 纯控制台固件 → VNC 永远显示
 * 'Display output is not active.'（用户反馈：这些镜像没有系统）」的修复：
 * 全部 15 款内置模板必须统一使用 virt 机器 + qemu-armv7a-full 图形固件
 * （LVGL + virtio-gpu + 触摸），并自动执行 lvgldemo 启动界面。
 *
 * 单一通用图形固件 + 按模板注入分辨率 = bandQQ 同款「一个镜像模拟全部设备」思路。
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
    fun `all builtin templates use unified graphics firmware`() {
        val templates = loadTemplates()
        assertTrue("内置模板数量异常（期望 15）: ${templates.size}", templates.size == 15)
        val bad = templates.filter {
            it.qemu.machine != DeviceTemplate.QemuSpec.MACHINE_VIRT ||
                it.qemu.kernel != DeviceTemplate.QemuSpec.KERNEL_OPENVELA_ARMV7A_FULL
        }
        assertTrue(
            "以下模板未使用 virt + full 图形固件（VNC 将永远无画面）: " +
                bad.joinToString { "${it.id}(${it.qemu.machine}/${it.qemu.kernel})" },
            bad.isEmpty(),
        )
    }

    @Test
    fun `all builtin templates auto launch lvgldemo`() {
        val noCmd = loadTemplates().filter { it.qemu.autoCommand.isBlank() }
        assertTrue(
            "以下模板未配置自动启动命令（画面将停留在 nsh 提示符）: " +
                noCmd.joinToString { it.id },
            noCmd.isEmpty(),
        )
    }

    @Test
    fun `band resolutions survive width alignment`() {
        // QemuArgsBuilder 将宽度向上对齐到 16 像素；对齐后不得超出合理范围
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
