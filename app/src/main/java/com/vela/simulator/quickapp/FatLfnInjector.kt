package com.vela.simulator.quickapp

/**
 * v2.2.4: FAT32 镜像 LFN 长名链注入器（宿主侧，无需 mtools/root）。
 *
 * 背景（桌面 e2e + guest 内逐级探测实证，配方见 scripts/fat_lfn_inject.py）：
 * 1. 固件 NuttX FAT 对 8.3 短名条目做「原始字节大小写敏感」比较（/data/RESOURCE 可查，
 *    /data/resource ENOENT），且不尊重 NTRes 小写标志位、拒绝小写字节的 8.3 条目；
 * 2. vapp_main.c 的解包树路径是小写的 /data/vapps/<pkg>/app.js，而 mtools 写出的
 *    VAPPS/APP.JS/PAGES 等短名条目全是大写字节 → 永远查不到 → vapp 每次首启都
 *    走解包写盘（路径#2）；
 * 3. 固件 FAT 写路径存在「半更新」缺陷（新分配簇链前两个表项高 16 位残留 EOC 的
 *    0xffff），每次解包写盘都会触发 → 数据盘亚损坏：mtools 宽容可读、NuttX 拒绝 →
 *    vapp 报 package not found → 用户陷入「重装也无效」的黑屏循环。
 *
 * 修复：给 /vapps 子树内所有「无 LFN 链的短名条目」注入小写 LFN 长名链（LFN 按
 * 大小写精确匹配，是 NuttX 唯一能命中小写名称的形态）。配合宿主预解包（install 时
 * 把 rpk 解包树写入 ::/vapps/<pkg>），vapp 命中路径#1 零写启动，guest 不再写数据盘，
 * FAT 损坏代码路径不可达（e2e 实证整周期镜像字节零变化）。
 *
 * LFN 链规范：条目 attr=0x0F，序号倒序（首条目 |0x40），checksum 为短名 11 字节
 * 循环校验，名称 UTF-16LE 以 0x0000 结尾、0xFFFF 填充。链必须紧贴短条目之前。
 * 注意：已有 LFN 链的条目组必须【原样保留全部字节】（否则 manifest.json 等长名
 * 条目的 LFN 丢失 → NuttX 无法再解析——python 版曾踩此坑，e2e 抓出）。
 */
object FatLfnInjector {

    private const val SEC = 512

    /** 注入结果：注入的 LFN 链条数（0 = 无需注入） */
    data class Result(val injected: Int)

    /**
     * 对镜像 file 的 /vapps 子树（含根目录中该子树自身的条目）注入 LFN 链。
     * 幂等：已有 LFN 的条目跳过。镜像结构异常时抛异常（调用方兜底回退路径#2）。
     */
    fun injectVappsSubtree(file: java.io.File, sub: String = "vapps"): Result {
        java.io.RandomAccessFile(file, "rw").use { raf -> return inject(raf, sub) }
    }

    private class Bpb(val spc: Int, val rsvd: Int, val nfats: Int, val fatsz: Int, val rootclus: Int) {
        val dataStart = rsvd + nfats * fatsz
    }

    private fun parseBpb(img: ByteArray): Bpb {
        fun u16(off: Int) = (img[off].toInt() and 0xFF) or ((img[off + 1].toInt() and 0xFF) shl 8)
        fun u32(off: Int): Int {
            val lo = u16(off); val hi = u16(off + 2)
            return (hi shl 16) or lo
        }
        require(u16(0x11) == 0 && u16(0x16) == 0) { "非 FAT32 镜像" }
        val b = Bpb(
            spc = img[0x0D].toInt() and 0xFF,
            rsvd = u16(0x0E),
            nfats = img[0x10].toInt() and 0xFF,
            fatsz = u32(0x24),
            rootclus = u32(0x2C),
        )
        require(b.spc > 0 && b.fatsz > 0) { "BPB 解析失败" }
        return b
    }

    private fun inject(raf: java.io.RandomAccessFile, sub: String): Result {
        val len = raf.length().toInt()
        val img = ByteArray(len)
        raf.seek(0); raf.readFully(img)
        val b = parseBpb(img)
        val maxclus = (len / SEC - b.dataStart) / b.spc

        fun fatEntry(n: Int): Int {
            if (n < 2 || n >= maxclus + 2) return -1
            val off = b.rsvd * SEC + n * 4
            return (img[off].toInt() and 0xFF) or
                ((img[off + 1].toInt() and 0xFF) shl 8) or
                ((img[off + 2].toInt() and 0xFF) shl 16) or
                ((img[off + 3].toInt() and 0xFF) shl 24) and 0x0FFFFFFF
        }

        fun clusOff(c: Int) = (b.dataStart + (c - 2) * b.spc) * SEC

        fun readChain(start: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            var c = start
            val seen = HashSet<Int>()
            while (c in 2 until maxclus + 2 && seen.add(c)) {
                out.write(img, clusOff(c), b.spc * SEC)
                c = fatEntry(c)
                if (c < 0 || c >= 0x0FFFFFF8) break
            }
            return out.toByteArray()
        }

        fun writeChain(start: Int, data: ByteArray) {
            var c = start
            var pos = 0
            val seen = HashSet<Int>()
            while (c in 2 until maxclus + 2 && seen.add(c)) {
                val n = minOf(b.spc * SEC, data.size - pos)
                if (n <= 0) break
                System.arraycopy(data, pos, img, clusOff(c), n)
                pos += n
                c = fatEntry(c)
                if (c < 0 || c >= 0x0FFFFFF8) break
            }
        }

        /** 短名 11 字节循环校验（LFN checksum） */
        fun checksum11(short11: ByteArray): Int {
            var s = 0
            for (c in short11) s = (((s and 1) shl 7) + (s ushr 1) + (c.toInt() and 0xFF)) and 0xFF
            return s
        }

        /** 为 name 生成倒序 LFN 条目块（seq N..1，首条 |0x40） */
        fun lfnBlocks(name: String, cksum: Int): List<ByteArray> {
            val units = ArrayList<Int>()
            name.forEach { units.add(it.code and 0xFFFF) }
            units.add(0x0000)
            while (units.size % 13 != 0) units.add(0xFFFF)
            val n = units.size / 13
            val blocks = ArrayList<ByteArray>()
            val pos = intArrayOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30)
            for (seq in n downTo 1) {
                val e = ByteArray(32)
                e[0] = (seq or (if (seq == n) 0x40 else 0)).toByte()
                e[11] = 0x0F; e[12] = 0
                e[13] = cksum.toByte(); e[26] = 0; e[27] = 0
                for (i in 0 until 13) {
                    val v = units[(n - seq) * 13 + i]
                    e[pos[i]] = (v and 0xFF).toByte()
                    e[pos[i] + 1] = ((v shr 8) and 0xFF).toByte()
                }
                blocks.add(e)
            }
            return blocks
        }

        class Ent(val short: String, val logical: String, val clus: Int, val attr: Int)

        var injected = 0

        /**
         * 重排目录：无 LFN 链的短条目（除 ./.. 与卷标）前注入小写 LFN 链；
         * 已有 LFN 链的组【原样保留全部字节】。返回 (新目录字节, 条目表)。
         */
        fun rebuild(data: ByteArray, prefix: String): Pair<ByteArray, List<Ent>> {
            val out = java.io.ByteArrayOutputStream()
            val map = ArrayList<Ent>()
            var pendingLfn = ArrayList<ByteArray>()
            var off = 0
            while (off + 32 <= data.size) {
                val e = data.copyOfRange(off, off + 32)
                val first = e[0].toInt() and 0xFF
                if (first == 0x00) break
                if (first == 0xE5) { pendingLfn = ArrayList(); off += 32; continue }
                if ((e[0x0B].toInt() and 0xFF) == 0x0F) { pendingLfn.add(e); off += 32; continue }

                // 短条目
                val name = e.copyOfRange(0, 8).toString(Charsets.US_ASCII).trimEnd(' ')
                val ext = e.copyOfRange(8, 11).toString(Charsets.US_ASCII).trimEnd(' ')
                val short = name + (if (ext.isNotEmpty()) ".$ext" else "")
                val attr = e[0x0B].toInt() and 0xFF
                val clus = (e[0x1A].toInt() and 0xFF) or ((e[0x1B].toInt() and 0xFF) shl 8) or
                    ((e[0x14].toInt() and 0xFF) shl 16) or ((e[0x15].toInt() and 0xFF) shl 24)

                var logical: String
                if (pendingLfn.isNotEmpty()) {
                    // 已有 LFN：原样保留全部字节
                    pendingLfn.forEach { out.write(it) }
                    val sb = StringBuilder()
                    outer@ for (p in pendingLfn) {
                        for (i in intArrayOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30)) {
                            val lo = p[i].toInt() and 0xFF; val hi = p[i + 1].toInt() and 0xFF
                            if ((lo == 0x00 && hi == 0x00) || (lo == 0xFF && hi == 0xFF)) break@outer
                            sb.append(((hi shl 8) or lo).toChar())
                        }
                    }
                    logical = sb.toString().lowercase()
                } else if (short != "." && short != ".." && (attr and 0x08) == 0) {
                    // 无 LFN：注入小写长名链
                    lfnBlocks(prefix + short.lowercase(), checksum11(e.copyOfRange(0, 11))).forEach { out.write(it) }
                    injected++
                    logical = short.lowercase()
                } else {
                    logical = short.lowercase()
                }
                out.write(e)
                map.add(Ent(short, logical, clus, attr))
                pendingLfn = ArrayList()
                off += 32
            }
            val body = out.toByteArray()
            require(body.size <= data.size) { "目录重排溢出（条目过多）" }
            val res = data.copyOf()
            System.arraycopy(body, 0, res, 0, body.size)
            java.util.Arrays.fill(res, body.size, res.size, 0.toByte())
            return res to map
        }

        fun walk(baseClus: Int, prefix: String) {
            val data = readChain(baseClus)
            val (newData, map) = rebuild(data, prefix)
            writeChain(baseClus, newData)
            for (ent in map) {
                if (ent.short != "." && ent.short != ".." &&
                    (ent.attr and 0x10) != 0 && ent.clus >= 2
                ) {
                    walk(ent.clus, "$prefix/${ent.logical}")
                }
            }
        }

        // 根目录：注入（含子树自身条目的小写 LFN），再递归子树
        val rootData = readChain(b.rootclus)
        val (newRoot, rootMap) = rebuild(rootData, "")
        writeChain(b.rootclus, newRoot)
        var found = false
        for (ent in rootMap) {
            if (ent.logical == sub && (ent.attr and 0x10) != 0 && ent.clus >= 2) {
                walk(ent.clus, "/$sub")
                found = true
                break
            }
        }
        if (!found) return Result(0)

        raf.seek(0); raf.write(img)
        return Result(injected)
    }
}
