package com.vela.simulator

import com.vela.simulator.quickapp.FatLfnInjector
import com.vela.simulator.quickapp.QuickAppIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.experimental.or

/**
 * v2.2.5 回归锁：
 * 1. QuickAppIds.safeId —— 磁盘层短 ID 的确定性/长度/格式约束
 *    （vapp 查找路径总长必须 ≤47 字符，固件 LFN 路径长度缺陷见 QuickAppIds 注释）
 * 2. FatLfnInjector —— v2.2.4 三缺陷（块序颠倒/逻辑名截断/路径前缀混入）的
 *    字节级回归锁：合成 FAT32 镜像上验证 LFN 链的盘序/checksum/命名/幂等
 */
class QuickAppIdsTest {

    @Test
    fun `safeId 确定性且格式正确`() {
        val id = QuickAppIds.safeId("io.github.gsjsjzhznsz.bandqq")
        assertEquals(12, id.length)
        assertTrue("必须 qa 前缀: $id", id.startsWith("qa"))
        assertTrue("必须全小写 hex: $id", id.drop(2).all { it in "0123456789abcdef" })
        assertEquals(id, QuickAppIds.safeId("io.github.gsjsjzhznsz.bandqq"))
    }

    @Test
    fun `demo 包保持原 id（内置镜像预解包树兼容）`() {
        assertEquals("com.vela.demo", QuickAppIds.safeId("com.vela.demo"))
    }

    @Test
    fun `不同包名映射不同 safeId`() {
        val a = QuickAppIds.safeId("com.example.a")
        val b = QuickAppIds.safeId("com.example.b")
        assertTrue(a != b)
    }

    @Test
    fun `safeId 使 vapp 两条查找路径都远离 48 字符固件缺陷边界`() {
        val pkg = "io.github.gsjsjzhznsz.bandqq"
        val sid = QuickAppIds.safeId(pkg)
        // 路径#1 /data/vapps/<safeId>/app.js 与 路径#2 /data/RESOURCE/PACKAGE/<safeId>.rpk
        val p1 = "/data/vapps/$sid/app.js"
        val p2 = "/data/RESOURCE/PACKAGE/$sid.rpk"
        assertTrue("路径#1 必须远离 48 边界: $p1", p1.length <= 39)
        assertTrue("路径#2 必须远离 48 边界: $p2", p2.length <= 39)
        // 对照：真实包名深处失败区（回归说明）
        assertTrue("/data/RESOURCE/PACKAGE/$pkg.rpk".length >= 48)
    }

    @Test
    fun `空包名返回空串`() {
        assertEquals("", QuickAppIds.safeId(""))
    }
}

/** 合成最小 FAT32 镜像工具（仅覆盖注入器需要的结构） */
object SyntheticFat {
    const val SEC = 512
    const val SPB = 1            // 每簇扇区
    const val RSVD = 32
    const val NFATS = 2
    const val FATSZ = 16         // 每 FAT 扇区数
    const val TOTAL = 256        // 总扇区
    const val DATA_START = RSVD + NFATS * FATSZ  // 64
    const val ROOTCLUS = 2

    fun u16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    fun u32(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())

    fun build(): ByteArray {
        val img = ByteArray(TOTAL * SEC)
        // BPB
        img[0x0B] = 0; img[0x0C] = 2                       // bytes/sector = 512
        img[0x0D] = SPB.toByte()
        System.arraycopy(u16(RSVD), 0, img, 0x0E, 2)
        img[0x10] = NFATS.toByte()
        System.arraycopy(u16(0), 0, img, 0x11, 2)          // root entries = 0 (FAT32)
        System.arraycopy(u32(TOTAL), 0, img, 0x20, 4)      // totsec32
        System.arraycopy(u16(0), 0, img, 0x16, 2)          // fatsz16 = 0
        System.arraycopy(u32(FATSZ), 0, img, 0x24, 4)
        System.arraycopy(u32(ROOTCLUS), 0, img, 0x2C, 4)
        img[0x42] = 0x29                                   // FAT32 扩展引导标志在 0x42（0x26 是 fatsz32 内部！）
        // FAT：EOC 标记簇 2（双副本同步 —— 真实镜像 FAT1==FAT2）
        fun fatOff(n: Int) = RSVD * SEC + n * 4
        fun fatOff2(n: Int) = (RSVD + FATSZ) * SEC + n * 4
        System.arraycopy(u32(0x0FFFFFF8), 0, img, fatOff(2), 4)
        System.arraycopy(u32(0x0FFFFFF8), 0, img, fatOff2(2), 4)
        return img
    }

    fun clusOff(c: Int) = (DATA_START + (c - ROOTCLUS) * SPB) * SEC

    /** 短名 11 字节目录项（无 LFN） */
    fun shortEntry(name8: String, ext3: String, attr: Int, clus: Int, size: Int = 0): ByteArray {
        val e = ByteArray(32)
        val base = name8.padEnd(8).take(8)
        val ext = ext3.padEnd(3).take(3)
        for (i in 0 until 8) e[i] = base[i].uppercaseChar().code.toByte()
        for (i in 0 until 3) e[8 + i] = ext[i].uppercaseChar().code.toByte()
        e[0x0B] = attr.toByte()
        System.arraycopy(u16(clus and 0xFFFF), 0, e, 0x1A, 2)
        System.arraycopy(u32(size), 0, e, 0x1C, 4)
        return e
    }

    fun lfnChecksum(short11: ByteArray): Int {
        var s = 0
        for (c in short11) s = (((s and 1) shl 7) + (s ushr 1) + (c.toInt() and 0xFF)) and 0xFF
        return s
    }
}

class FatLfnInjectorTest {

    private fun newImage(): ByteArray {
        val img = SyntheticFat.build()
        // 根目录（簇2）：一个 VAPPS 目录（无 LFN，模拟 mformat/mmd 形态）
        System.arraycopy(SyntheticFat.shortEntry("VAPPS", "", 0x10, 3), 0, img, SyntheticFat.clusOff(2), 32)
        // /vapps（簇3）：. .. + 无 LFN 的 app.js 短条目（模拟 mcopy 写的 8.3 小写文件）
        val vapps = SyntheticFat.clusOff(3)
        System.arraycopy(SyntheticFat.shortEntry(".", "", 0x10, 3), 0, img, vapps, 32)
        System.arraycopy(SyntheticFat.shortEntry("..", "", 0x10, 2), 0, img, vapps + 32, 32)
        System.arraycopy(SyntheticFat.shortEntry("APP", "JS", 0x20, 4, 3), 0, img, vapps + 64, 32)
        // 簇4：app.js 内容
        val data = "hi\n".toByteArray()
        System.arraycopy(data, 0, img, SyntheticFat.clusOff(4), data.size)
        // FAT 链：2/3/4 均 EOC（双副本同步）
        fun fatOff(n: Int) = SyntheticFat.RSVD * SyntheticFat.SEC + n * 4
        fun fatOff2(n: Int) = (SyntheticFat.RSVD + SyntheticFat.FATSZ) * SyntheticFat.SEC + n * 4
        for (c in 3..4) {
            System.arraycopy(SyntheticFat.u32(0x0FFFFFF8), 0, img, fatOff(c), 4)
            System.arraycopy(SyntheticFat.u32(0x0FFFFFF8), 0, img, fatOff2(c), 4)
        }
        return img
    }

    private fun fatEntry(img: ByteArray, n: Int): Int {
        val o = SyntheticFat.RSVD * SyntheticFat.SEC + n * 4
        return (img[o].toInt() and 0xFF) or ((img[o + 1].toInt() and 0xFF) shl 8) or
            ((img[o + 2].toInt() and 0xFF) shl 16) or ((img[o + 3].toInt() and 0xFF) shl 24)
    }

    @Test
    fun `注入根目录与 vapps 子树的 1 段 LFN 链`() {
        val img = newImage()
        val f = File.createTempFile("lfn", ".img").apply { writeBytes(img) }
        val r = FatLfnInjector.injectVappsSubtree(f)
        // vapps（根）+ app.js（vapps 内）各 1 条
        assertEquals(2, r.injected)
        val out = f.readBytes()

        // 根目录：LFN 链紧贴 VAPPS 短条目之前，seq=0x41，checksum 匹配
        val rootOff = SyntheticFat.clusOff(2)
        val lfn = out.copyOfRange(rootOff, rootOff + 32)
        assertEquals(0x0F, lfn[0x0B].toInt() and 0xFF)
        assertEquals(0x41, lfn[0].toInt() and 0xFF)
        val short = out.copyOfRange(rootOff + 32, rootOff + 64)
        assertEquals(SyntheticFat.lfnChecksum(short.copyOfRange(0, 11)), lfn[13].toInt() and 0xFF)
        // 名字 = 短名小写 "vapps"（UTF-16LE，0x0000 结尾）
        val nameChars = (0 until 5).map { i -> lfn[1 + i * 2].toInt() or ((lfn[2 + i * 2].toInt() and 0xFF) shl 8) }
        assertEquals("vapps", nameChars.map { it.toChar() }.joinToString(""))
        // 终止符在第 6 个 UTF-16 单元（字节 14,15 —— LFN 字符位不连续，勿用 1+i*2）
        assertEquals(0, lfn[14].toInt() and 0xFF)
        assertEquals(0, lfn[15].toInt() and 0xFF)
        // 短条目原样保留
        assertTrue(short.contentEquals(SyntheticFat.shortEntry("VAPPS", "", 0x10, 3)))
        // FAT 未被触碰（簇 2..4 仍 EOC）
        for (c in 2..4) assertTrue("FAT[$c] 应保持 EOC", fatEntry(out, c) >= 0x0FFFFFF8)
        f.delete()
    }

    @Test
    fun `幂等 —— 二次注入为 0 且字节不变`() {
        val f = File.createTempFile("lfn", ".img").apply { writeBytes(newImage()) }
        FatLfnInjector.injectVappsSubtree(f)
        val once = f.readBytes()
        val r2 = FatLfnInjector.injectVappsSubtree(f)
        assertEquals(0, r2.injected)
        assertTrue(once.contentEquals(f.readBytes()))
        f.delete()
    }

    @Test
    fun `已有 mcopy LFN 链的条目组原样保留（不被改写）`() {
        val img = newImage()
        // 在 /vapps 里放一个带 2 段 mcopy LFN 的 manifest.json 短条目（MANIFE~1.JSO）
        val mcopyLfn1 = ByteArray(32).apply {
            this[0] = 0x42; this[0x0B] = 0x0F; this[13] = 0x42
            // 末段 ".json" + NUL + pad（盘序首条持末段）
            val chars = intArrayOf('.'.code, 'j'.code, 's'.code, 'o'.code, 'n'.code, 0x0000)
            val pos = intArrayOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30)
            for (i in chars.indices) { this[pos[i]] = (chars[i] and 0xFF).toByte(); this[pos[i] + 1] = ((chars[i] shr 8) and 0xFF).toByte() }
        }
        val mcopyLfn2 = ByteArray(32).apply {
            this[0] = 0x01; this[0x0B] = 0x0F; this[13] = 0x42
            val chars = "manifest.json".map { it.code } + listOf(0x0000)
            val pos = intArrayOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30)
            for (i in 0 until 13) { this[pos[i]] = (chars[i] and 0xFF).toByte(); this[pos[i] + 1] = ((chars[i] shr 8) and 0xFF).toByte() }
        }
        val short = SyntheticFat.shortEntry("MANIFE~1", "JSO", 0x20, 5, 205)
        short[13] = 0x42 // 与 LFN 链一致的假校验位（本测试只验证保留语义）
        val vapps = SyntheticFat.clusOff(3)
        System.arraycopy(mcopyLfn1, 0, img, vapps + 64, 32)
        System.arraycopy(mcopyLfn2, 0, img, vapps + 96, 32)
        System.arraycopy(short, 0, img, vapps + 128, 32)
        val f = File.createTempFile("lfn", ".img").apply { writeBytes(img) }
        val r = FatLfnInjector.injectVappsSubtree(f)
        // 只注入根 vapps 与（若需）其他条目；manifest.json 组必须原样
        val out = f.readBytes()
        val group = out.copyOfRange(vapps + 64, vapps + 160)
        assertTrue("mcopy LFN 组必须字节级保留",
            group.copyOfRange(0, 32).contentEquals(mcopyLfn1) &&
            group.copyOfRange(32, 64).contentEquals(mcopyLfn2) &&
            group.copyOfRange(64, 96).contentEquals(short))
        assertTrue(r.injected >= 1)
        f.delete()
    }
}
