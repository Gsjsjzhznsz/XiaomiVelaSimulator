package com.vela.simulator.quickapp

/**
 * v2.2.5: FAT32 镜像 LFN 长名链注入器（宿主侧，无需 mtools/root）。
 *
 * 背景（桌面 e2e + guest 内逐级探测实证，配方见 scripts/repro_v224/）：
 * 1. 固件 NuttX FAT 对 8.3 短名条目做「原始字节大小写敏感」比较（/data/RESOURCE 可查，
 *    /data/resource ENOENT），且不尊重 NTRes 小写标志位、拒绝小写字节的 8.3 条目；
 *    小写查找（vapp 的 /data/vapps/<pkg>、app.js 等）只能靠 LFN 长名链命中；
 * 2. v2.2.5 新实证（真机日志 + 桌面 20+ 组对照实验）：固件 LFN 打开存在
 *    「绝对路径长度 ≥48 字符即绑定错误 dirent（起始簇=0）→ 首读 EIO」缺陷，
 *    与目录深度/名长/簇位无关（47 字符通过、48 字符失败，单变量二分实锤）。
 *    因此 App 侧同时用「安全短 ID」（≤12 字符，最长路径 ≤39 字符）远离该边界，
 *    本注入器只处理 1 段 LFN（≤13 字符文件名）的补链，全部在已实证安全区。
 * 3. 固件 FAT 写路径存在「半更新」缺陷（新分配簇链前两个表项高 16 位残留 EOC 的
 *    0xffff），guest 解包写盘会触发 → 数据盘亚损坏（mtools 宽容可读、NuttX 拒绝）。
 *    预解包树 + LFN 注入让 vapp 命中路径#1 零写启动，写盘路径不可达。
 *
 * v2.2.4 版注入器三缺陷（本轮字节级取证实锤，全部修复）：
 * ① LFN 块序颠倒——seq N|0x40（盘上首条）必须持名字【结尾】13 字符、seq 1（紧贴
 *   短条目）持【开头】13 字符（mcopy 写的链 guest 100% 可解析 = 铁证），旧实现相反；
 * ② 逻辑名组装按盘序遇 0x0000 终止符即 break → 长名目录的 logical 被截断
 *   （bandqq → "qq"）→ 子树前缀变垃圾；
 * ③ 注入名混入完整路径前缀（'/vapps/qqcommon'）——FAT 文件名不含 '/'。
 *
 * LFN 链规范：条目 attr=0x0F，盘序 = seq N|0x40（末段）… seq 1（首段）紧贴短条目，
 * checksum 为短名 11 字节循环校验，名称 UTF-16LE 以 0x0000 结尾、0xFFFF 填充。
 * 已有 LFN 链的条目组必须【原样保留全部字节】（mcopy 写的链是正确性基准）。
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
            return ((img[off].toInt() and 0xFF) or
                ((img[off + 1].toInt() and 0xFF) shl 8) or
                ((img[off + 2].toInt() and 0xFF) shl 16) or
                ((img[off + 3].toInt() and 0xFF) shl 24)) and 0x0FFFFFFF
        }

        /**
         * v2.2.8: 写 FAT 表项（同时镜像全部 FAT 副本）。
         * 旧实现从不写 FAT（纯重写既有簇），目录增长必须分配新簇 → 需要写表。
         * 高 4 位保留位恒清零（0），与 mtools/出厂镜像一致，避免固件半更新式
         * 的 0xffff 高位残留。
         */
        fun fatEntrySet(n: Int, v: Int) {
            if (n < 2 || n >= maxclus + 2) return
            val val32 = v and 0x0FFFFFFF
            for (fi in 0 until b.nfats) {
                val off = (b.rsvd + fi * b.fatsz) * SEC + n * 4
                img[off] = (val32 and 0xFF).toByte()
                img[off + 1] = ((val32 shr 8) and 0xFF).toByte()
                img[off + 2] = ((val32 shr 16) and 0xFF).toByte()
                img[off + 3] = ((val32 shr 24) and 0xFF).toByte()
            }
        }

        fun clusOff(c: Int) = (b.dataStart + (c - 2) * b.spc) * SEC

        /** v2.2.8: 分配一个空闲簇（FAT=0），置 EOC 并返回簇号；无空闲抛异常 */
        fun allocCluster(): Int {
            for (n in 2 until maxclus + 2) {
                if (fatEntry(n) == 0) {
                    fatEntrySet(n, 0x0FFFFFFF)
                    return n
                }
            }
            throw IllegalStateException("FAT 无空闲簇（目录增长失败）")
        }

        /** v2.2.8: 链尾簇号与总容量（字节） */
        fun chainTailAndCapacity(start: Int): Pair<Int, Int> {
            var c = start
            var bytes = 0
            var last = start
            val seen = HashSet<Int>()
            while (c in 2 until maxclus + 2 && seen.add(c)) {
                bytes += b.spc * SEC
                last = c
                c = fatEntry(c)
                if (c < 0 || c >= 0x0FFFFFF8) break
            }
            return last to bytes
        }

        /**
         * v2.2.8: 目录跨簇增长（重写上会话丢失的修复）。
         * spc=1（512B 簇）出厂盘上，注入 LFN 后目录体几乎必然超出既有簇链容量
         * （512B = 16 条目），必须扩链：从 FAT 分配新簇接到链尾、数据区填零
         * （0x00 = 目录结束符语义）、FAT 双副本同步。不扩链时注入器会在大目录上
         * 报「目录重排溢出」或静默截断（v2.2.6 22:17 丢盘链路的贡献因素之一）。
         */
        fun growChain(start: Int, needed: Int) {
            var (tail, have) = chainTailAndCapacity(start)
            while (have < needed) {
                val nc = allocCluster()
                fatEntrySet(tail, nc)
                fatEntrySet(nc, 0x0FFFFFFF)
                java.util.Arrays.fill(img, clusOff(nc), clusOff(nc) + b.spc * SEC, 0.toByte())
                tail = nc
                have += b.spc * SEC
            }
        }

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
                val cap = b.spc * SEC
                val n = minOf(cap, data.size - pos).coerceAtLeast(0)
                if (n > 0) System.arraycopy(data, pos, img, clusOff(c), n)
                if (n < cap) {
                    // v2.2.8: 目录体写完后剩余字节清零（0x00=目录结束）+ 尾簇置 EOC，
                    // 防负偏移/防旧残留条目被 guest 当有效项（上会话总结中的「尾簇填零」修复）。
                    // 已是 EOC 的簇不重写 —— 保持 v2.2.5「非增长注入零 FAT 写」的兼容语义
                    java.util.Arrays.fill(img, clusOff(c) + n, clusOff(c) + cap, 0.toByte())
                    val cur = fatEntry(c)
                    if (cur < 0x0FFFFFF8) fatEntrySet(c, 0x0FFFFFFF)
                    return
                }
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

        /**
         * v2.2.5 修复①：为 name 生成盘序正确的 LFN 条目块。
         * 盘序 = seq N|0x40 持【末段】… seq 1 持【首段】（紧贴短条目）。
         */
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
                // seq=s 持第 s 段（1-based）：units[(s-1)*13 .. s*13-1]
                for (i in 0 until 13) {
                    val v = units.getOrElse((seq - 1) * 13 + i) { 0xFFFF }
                    e[pos[i]] = (v and 0xFF).toByte()
                    e[pos[i] + 1] = ((v shr 8) and 0xFF).toByte()
                }
                blocks.add(e)
            }
            return blocks
        }

        class Ent(val short: String, val logical: String, val clus: Int, val attr: Int)

        var injected = 0

        /** v2.2.5 修复②：按 seq 语义序（1=首段…N=末段）拼长名，而非盘序遇到终止符即停 */
        fun assembleLfn(pendingLfn: List<ByteArray>): String {
            // pendingLfn 为盘序（seq N|0x40 … seq 1）；反转后即 seq 1…N = 名字顺序
            val sb = StringBuilder()
            for (p in pendingLfn.reversed()) {
                for (i in intArrayOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30)) {
                    val lo = p[i].toInt() and 0xFF; val hi = p[i + 1].toInt() and 0xFF
                    if (lo == 0x00 && hi == 0x00) return sb.toString()
                    if (lo == 0xFF && hi == 0xFF) continue
                    sb.append(((hi shl 8) or lo).toChar())
                }
            }
            return sb.toString()
        }

        /**
         * 重排目录：无 LFN 链的短条目（除 ./.. 与卷标）前注入小写 LFN 链；
         * 已有 LFN 链的组【原样保留全部字节】。返回 (新目录体, 条目表)。
         * v2.2.8: 返回紧凑 body（不再填充到原容量），由调用方按需扩链后写回 ——
         * 移除旧的「目录重排溢出」硬失败（spc=1 大目录必然超容量，扩链即合法）。
         */
        fun rebuild(data: ByteArray, pathPrefix: String): Pair<ByteArray, List<Ent>> {
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

                // 短条目（8.3 名以空格填充；防御性同时去除 NUL 填充）
                val name = e.copyOfRange(0, 8).toString(Charsets.US_ASCII).trimEnd(' ', '\u0000')
                val ext = e.copyOfRange(8, 11).toString(Charsets.US_ASCII).trimEnd(' ', '\u0000')
                val short = name + (if (ext.isNotEmpty()) ".$ext" else "")
                val attr = e[0x0B].toInt() and 0xFF
                val clus = (e[0x1A].toInt() and 0xFF) or ((e[0x1B].toInt() and 0xFF) shl 8) or
                    ((e[0x14].toInt() and 0xFF) shl 16) or ((e[0x15].toInt() and 0xFF) shl 24)

                var logical: String
                if (pendingLfn.isNotEmpty()) {
                    // 已有 LFN：原样保留全部字节（mcopy 写的链是正确性基准）
                    pendingLfn.forEach { out.write(it) }
                    logical = assembleLfn(pendingLfn).lowercase()
                } else if (short != "." && short != ".." && (attr and 0x08) == 0) {
                    // v2.2.5 修复③：注入名 = 短名小写（不带任何路径前缀）
                    val lfnName = short.lowercase()
                    lfnBlocks(lfnName, checksum11(e.copyOfRange(0, 11))).forEach { out.write(it) }
                    injected++
                    logical = lfnName
                } else {
                    logical = short.lowercase()
                }
                out.write(e)
                map.add(Ent(short, logical, clus, attr))
                pendingLfn = ArrayList()
                off += 32
            }
            val body = out.toByteArray()
            return body to map
        }

        fun walk(baseClus: Int, prefix: String) {
            val data = readChain(baseClus)
            val (newData, map) = rebuild(data, prefix)
            // v2.2.8: 目录跨簇增长 —— 注入后超出既有链容量则先扩链再写回
            if (newData.size > data.size) growChain(baseClus, newData.size)
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
        if (newRoot.size > rootData.size) growChain(b.rootclus, newRoot.size)
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
