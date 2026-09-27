package com.vela.simulator

import com.vela.simulator.quickapp.RpkInstaller
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.2.2 DiskDoctor：FAT 损坏签名判定锁定。
 * 样本取自用户真机日志（09-27 17:03 session，Xiaomi 22041216C）：
 * 出厂数据盘重建后 guest 解包写盘中断，FAT[6] 半更新为 0x0fff0013，
 * mtools 报 "Cluster # at 6 too big" / "Error reading FAT"，此后所有
 * list/install 永久失败 —— 判定函数是自动修复链路的触发开关，误报/漏报都会
 * 让修复失效，故以真实样本锁定。
 */
class RpkDiskTest {

    @Test
    fun `fat corrupt signatures detected from real device output`() {
        val real = """
            读取数据盘失败: Cluster # at 6 too big(0xfff0013)
            Probably non MS-DOS disk
            Error reading FAT
            Cannot initialize '::'
        """.trimIndent()
        assertTrue(RpkInstaller.isFatCorruptOutput(real))
        // 安装路径的复合错误（末尾还带误导性的 ": Success" 行）
        assertTrue(
            RpkInstaller.isFatCorruptOutput(
                "mcopy 写入失败: Cluster # at 6 too big(0xfff0013) Probably non MS-DOS disk " +
                    "Cannot initialize '::' ::/resource/package/io.github.gsjsjzhznsz.bandqq.rpk: Success",
            ),
        )
        // 大小写不敏感
        assertTrue(RpkInstaller.isFatCorruptOutput("ERROR READING FAT"))
    }

    @Test
    fun `normal mtools failures are not treated as fat corruption`() {
        assertFalse(RpkInstaller.isFatCorruptOutput("mcopy 写入失败: No space left on device"))
        assertFalse(RpkInstaller.isFatCorruptOutput("数据盘镜像不存在，请先在设备页部署内置镜像"))
        assertFalse(RpkInstaller.isFatCorruptOutput("mcopy: file 'x.rpk' not found"))
        assertFalse(RpkInstaller.isFatCorruptOutput(""))
        // rpk 解析类错误与磁盘无关
        assertFalse(RpkInstaller.isFatCorruptOutput("rpk 解析失败: manifest.json 缺失或非法"))
    }
}
