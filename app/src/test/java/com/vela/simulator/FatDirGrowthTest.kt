package com.vela.simulator

import com.vela.simulator.quickapp.FatLfnInjector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v2.2.8 回归锁：目录跨簇增长（重写上会话环境回收丢失的修复）。
 *
 * 背景：spc=1（512B 簇）出厂数据盘上，一个满目录（16 条目）注入 LFN 链后
 * 必然超出 512B —— 旧注入器直接抛「目录重排溢出」或静默截断（v2.2.6 22:17
 * 丢盘链路贡献因素之一）。新行为：从 FAT 分配新簇扩链、数据区填零、FAT
 * 双副本同步、链尾 EOC。
 */
class FatDirGrowthTest {

    private fun fatOff(n: Int) = SyntheticFat.RSVD * SyntheticFat.SEC + n * 4

    private fun fatRaw(img: ByteArray, n: Int): Int {
        val o = fatOff(n)
        return (img[o].toInt() and 0xFF) or ((img[o + 1].toInt() and 0xFF) shl 8) or
            ((img[o + 2].toInt() and 0xFF) shl 16) or ((img[o + 3].toInt() and 0xFF) shl 24)
    }

    /** 构造 /vapps 恰好满簇（. .. + 14 个短名条目 = 16 × 32B = 512B）的镜像 */
    private fun fullVappsImage(): ByteArray {
        val img = SyntheticFat.build()
        System.arraycopy(SyntheticFat.shortEntry("VAPPS", "", 0x10, 3), 0, img, SyntheticFat.clusOff(2), 32)
        val vapps = SyntheticFat.clusOff(3)
        System.arraycopy(SyntheticFat.shortEntry(".", "", 0x10, 3), 0, img, vapps, 32)
        System.arraycopy(SyntheticFat.shortEntry("..", "", 0x10, 2), 0, img, vapps + 32, 32)
        for (i in 0 until 14) {
            // FILE0..FILE9, FILEA..FILED —— 全部无 LFN 的 8.3 短条目
            val nm = "FILE" + "0123456789ABCD"[i]
            System.arraycopy(
                SyntheticFat.shortEntry(nm, "JS", 0x20, 4, 3), 0, img, vapps + 64 + i * 32, 32,
            )
        }
        // 簇 2/3/4 均 EOC（双副本同步）；app.js 数据在簇 4
        val data = "hi\n".toByteArray()
        System.arraycopy(data, 0, img, SyntheticFat.clusOff(4), data.size)
        for (c in 2..4) {
            System.arraycopy(SyntheticFat.u32(0x0FFFFFF8), 0, img, fatOff(c), 4)
            System.arraycopy(
                SyntheticFat.u32(0x0FFFFFF8), 0, img,
                (SyntheticFat.RSVD + SyntheticFat.FATSZ) * SyntheticFat.SEC + c * 4, 4,
            )
        }
        return img
    }

    private fun parseDirEntries(dirBytes: ByteArray): List<Pair<String, Int>> {
        // 返回 (短名, 是否有紧贴 LFN 链) —— 与注入器同规则解析
        val out = ArrayList<Pair<String, Int>>()
        var i = 0
        var pending = 0
        while (i + 32 <= dirBytes.size) {
            val e = dirBytes.copyOfRange(i, i + 32)
            val first = e[0].toInt() and 0xFF
            if (first == 0x00) break
            if (first == 0xE5) { pending = 0; i += 32; continue }
            if ((e[0x0B].toInt() and 0xFF) == 0x0F) { pending++; i += 32; continue }
            val name = e.copyOfRange(0, 8).toString(Charsets.US_ASCII).trimEnd(' ', '\u0000')
            val ext = e.copyOfRange(8, 11).toString(Charsets.US_ASCII).trimEnd(' ', '\u0000')
            out.add((name + if (ext.isNotEmpty()) ".$ext" else "") to pending)
            pending = 0
            i += 32
        }
        return out
    }

    @Test
    fun `满簇目录注入 LFN 触发跨簇增长且全部条目可解析`() {
        val f = File.createTempFile("grow", ".img").apply { writeBytes(fullVappsImage()) }
        val r = FatLfnInjector.injectVappsSubtree(f)
        // 根 vapps 1 条 + vapps 内 14 个文件条目 = 15
        assertEquals(15, r.injected)
        val out = f.readBytes()

        // /vapps 链（起簇 3）扩为 2 簇：3 → 新簇 → EOC
        val next = fatRaw(out, 3)
        assertTrue("FAT[3] 应链接到新簇（扩链）: $next", next in 2 until 0x0FFFFFF8)
        val tail = fatRaw(out, next)
        assertTrue("新簇应为 EOC: $tail", tail >= 0x0FFFFFF8)

        // 沿链重组目录体（簇3 + 增长簇），必须解析出 16 个条目，且除 ./.. 外全部带 LFN 链
        val dirBytes = out.copyOfRange(SyntheticFat.clusOff(3), SyntheticFat.clusOff(3) + 512) +
            out.copyOfRange(SyntheticFat.clusOff(next), SyntheticFat.clusOff(next) + 512)
        val ents = parseDirEntries(dirBytes)
        assertEquals(16, ents.size)
        val noDotDot = ents.filter { it.first != "." && it.first != ".." }
        assertEquals(14, noDotDot.size)
        assertTrue("文件条目必须全部带 LFN 链", noDotDot.all { it.second >= 1 })

        // 增长簇内目录体之后的剩余区必须清零（0x00 = 目录结束符语义）
        val usedInTail = 30 * 32 - 512   // 目录体 30 条目（. .. + 14文件 + 14 LFN）
        val afterEnd = out.copyOfRange(
            SyntheticFat.clusOff(next) + usedInTail,
            SyntheticFat.clusOff(next) + 512,
        )
        assertTrue("扩链簇剩余区必须填零", afterEnd.all { it.toInt() == 0 })

        // FAT 双副本一致（注入器写 FAT 必须同时镜像 FAT1/FAT2）
        val fatszBytes = SyntheticFat.FATSZ * SyntheticFat.SEC
        for (n in 2..5) {
            val f1 = out.copyOfRange(fatOff(n), fatOff(n) + 4)
            val f2 = out.copyOfRange(fatOff(n) + fatszBytes, fatOff(n) + fatszBytes + 4)
            assertTrue("FAT1/FAT2[$n] 必须一致", f1.contentEquals(f2))
        }
        f.delete()
    }

    @Test
    fun `增长不触碰既有文件数据与未用 FAT 表项`() {
        val img0 = fullVappsImage()
        val f = File.createTempFile("grow", ".img").apply { writeBytes(img0) }
        FatLfnInjector.injectVappsSubtree(f)
        val out = f.readBytes()

        // 文件数据簇 4 内容原样
        val d0 = img0.copyOfRange(SyntheticFat.clusOff(4), SyntheticFat.clusOff(4) + 3)
        val d1 = out.copyOfRange(SyntheticFat.clusOff(4), SyntheticFat.clusOff(4) + 3)
        assertTrue("文件数据不得被改写", d0.contentEquals(d1))

        // 未用 FAT 表项保持 0（不允许误分配）；已 EOC 的尾簇不得被改写
        for (c in 6 until 192) {
            assertEquals("FAT[$c] 应保持空闲", 0, fatRaw(out, c))
        }
        // FAT[3] 原值 EOC（0x0FFFFFF8），非增长注入不得改写字节（v2.2.5 兼容语义）
        // 注：本夹具 /vapps 满簇会增长，改用 FatLfnInjectorTestLike 的非满簇镜像验证
        val imgPlain = FatLfnInjectorTestLike.newImage()
        val f2 = File.createTempFile("plain2", ".img").apply { writeBytes(imgPlain) }
        FatLfnInjector.injectVappsSubtree(f2)
        val out2 = f2.readBytes()
        for (n in 2..4) {
            assertTrue(
                "非增长注入 FAT[$n] 必须字节原样",
                imgPlain.copyOfRange(fatOff(n), fatOff(n) + 4)
                    .contentEquals(out2.copyOfRange(fatOff(n), fatOff(n) + 4)),
            )
        }
        f2.delete()
        f.delete()
    }

    @Test
    fun `非满簇目录注入不触发FAT写入_兼容旧语义`() {
        // newImage() 的 /vapps 只有 3 个条目，注入后仍 < 512B —— 不得产生任何 FAT 变化
        val img0 = FatLfnInjectorTestLike.newImage()
        val f = File.createTempFile("plain", ".img").apply { writeBytes(img0) }
        FatLfnInjector.injectVappsSubtree(f)
        val out = f.readBytes()
        for (n in 2..4) {
            assertTrue(
                "FAT[$n] 必须原样",
                img0.copyOfRange(fatOff(n), fatOff(n) + 4)
                    .contentEquals(out.copyOfRange(fatOff(n), fatOff(n) + 4)),
            )
        }
        f.delete()
    }
}

/** 复用 QuickAppIdsAndLfnTest 的夹具（避免跨文件 internal 可见性问题） */
private object FatLfnInjectorTestLike {
    fun newImage(): ByteArray {
        val img = SyntheticFat.build()
        System.arraycopy(SyntheticFat.shortEntry("VAPPS", "", 0x10, 3), 0, img, SyntheticFat.clusOff(2), 32)
        val vapps = SyntheticFat.clusOff(3)
        System.arraycopy(SyntheticFat.shortEntry(".", "", 0x10, 3), 0, img, vapps, 32)
        System.arraycopy(SyntheticFat.shortEntry("..", "", 0x10, 2), 0, img, vapps + 32, 32)
        System.arraycopy(SyntheticFat.shortEntry("APP", "JS", 0x20, 4, 3), 0, img, vapps + 64, 32)
        val data = "hi\n".toByteArray()
        System.arraycopy(data, 0, img, SyntheticFat.clusOff(4), data.size)
        fun fo(n: Int) = SyntheticFat.RSVD * SyntheticFat.SEC + n * 4
        fun fo2(n: Int) = (SyntheticFat.RSVD + SyntheticFat.FATSZ) * SyntheticFat.SEC + n * 4
        for (c in 3..4) {
            System.arraycopy(SyntheticFat.u32(0x0FFFFFF8), 0, img, fo(c), 4)
            System.arraycopy(SyntheticFat.u32(0x0FFFFFF8), 0, img, fo2(c), 4)
        }
        return img
    }
}
