package com.vela.simulator.quickapp

import com.vela.simulator.engine.QemuRuntime
import com.vela.simulator.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v2.2: rpk 包 → 虚拟机数据盘 安装器。
 *
 * 旧工坊只能解析 + 外形模拟预览（JS 业务逻辑无法在 App 内执行）；v2.1 起
 * 内置 vapp 固件在 QEMU 虚拟机内自带完整快应用运行时（QuickJS + VDOM +
 * LVGL），因此 rpk 的正确执行方式是：装入数据盘镜像 → vapp 运行时解包执行。
 *
 * 数据盘为 FAT32 镜像（mformat 4KB 簇， guest 内挂载到 /data），vapp 运行时
 * 约定从 /resource/package/<pkg>.rpk 解析包（见 openrt vapp_main.c：
 * RPK_DIR=/resource/package，hap://app/<pkg> → <pkg>.rpk）。本模块用
 * mtools（mcopy/mdel）直接读写镜像文件，无需 root、无需挂载、无需虚机运行。
 *
 * mtools 来自 Termux 软件源（约 140KB，依赖 libandroid-support/libiconv，
 * qemu 依赖树已覆盖大部分）：首次使用时按需安装到运行时前缀。
 *
 * v2.2.2 数据盘自愈（DiskDoctor）：真机实锤 guest 解包写盘中断/固件 FAT 写路径
 * 半更新（FAT[6]=0x0fff0013 = 新低 16bit + 旧高位残留）可损坏数据盘，
 * 后续所有 mtools 操作永久失败。防线：① 停止会话前向 guest 发 sync；
 * ② 会话退出后主动健康探测（mdir），命中 FAT 损坏签名立即自动修复
 * （重部署内置干净 data.img + 从 files/quickapps 备份恢复用户包）；
 * ③ 安装/读取/卸载失败同样触发自动修复。
 *
 * v2.2.4 根因闭环（预解包 + LFN 注入）在真机仍失效。v2.2.5 决定性定位
 * （桌面 20+ 组对照实验 + 真机日志，详见 worklog v2.2.5-fix）：固件 NuttX FAT
 * 的 LFN 长名打开存在「绝对路径长度 ≥48 字符即绑定错误 dirent（起始簇=0）→
 * 首读 EIO」缺陷。vapp 两条包查找路径都带固定前缀（/data/RESOURCE/PACKAGE/ =
 * 23 字符），真实包名 28 字符 → 路径 55 字符深处失败区 → package not found。
 * 另：Java 版 FatLfnInjector 有三缺陷（块序颠倒/逻辑名截断/路径前缀混入），
 * 已修复（v2.2.4 桌面 e2e 验证的是已丢失的 python 配方，出货 Java 版从未
 * 在真实镜像上验证过 —— 教训：配方验证≠出货运的代码验证）。
 * v2.2.5 修复：磁盘层身份改用 QuickAppIds.safeId（12 字符，最长路径 39 字符，
 * 远离 48 边界）+ 修复版 FatLfnInjector（1 段 LFN 注入，全在已实证安全区）。
 * install = mdel 先删 + mcopy 写 ::/resource/package/<safeId>.rpk + 宿主预解包
 * 到 ::/vapps/<safeId> + LFN 注入 → vapp 命中路径#1 零写启动（e2e 实证双轮
 * 启动整周期镜像字节零变化，JS 工厂被调用）。
 */
object RpkInstaller {

    /** v2.2.2: mtools 输出中判定 FAT 已损坏的特征串（真机实锤：
     *  "Cluster # at 6 too big(0xfff0013)" / "Error reading FAT" 等）。
     *  纯函数（单测锁定） */
    fun isFatCorruptOutput(out: String): Boolean = listOf(
        "error reading fat", "cannot initialize", "non ms-dos disk", "too big(",
    ).any { out.lowercase().contains(it) }

    /** 数据盘中解析出的 vapp 包（packageId = manifest 真实包名，safeId = 磁盘层短 ID） */
    data class VmPackage(
        val packageId: String,
        val safeId: String,
        val name: String,
        val versionName: String,
        val sizeBytes: Long,
        val iconFile: String?,
        val fileName: String,
    )

    private fun bin(runtime: QemuRuntime, name: String) = File(runtime.prefixUsr, "bin/$name")

    fun toolsReady(runtime: QemuRuntime): Boolean =
        listOf("mcopy", "mdel", "mdeltree", "mdir").all { name ->
            bin(runtime, name).let { it.exists() && it.canExecute() }
        }

    /**
     * 确保 mtools 可用：不存在则从 Termux 软件源按需安装（竞速选源 + 依赖闭包）。
     */
    suspend fun ensureTools(runtime: QemuRuntime, progress: QemuRuntime.Progress? = null): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!toolsReady(runtime)) {
                    runtime.ensurePackage("mtools", progress)
                    listOf("mcopy", "mdel", "mtype", "mdir", "mdeltree", "mmd", "mformat").forEach {
                        bin(runtime, it).setExecutable(true, false)
                    }
                }
                check(toolsReady(runtime)) { "mtools 安装后仍不可用" }
            }
        }

    /** 在虚拟机停止时写入（数据盘镜像挂attach在运行中的 QEMU 上会有写竞争） */
    fun exec(
        runtime: QemuRuntime,
        command: List<String>,
    ): Pair<Int, String> {
        val pb = ProcessBuilder(command).redirectErrorStream(true)
        runtime.execEnvironment().forEach { (k, v) -> pb.environment()[k] = v }
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return p.exitValue() to out
    }

    /**
     * 安装 rpk 到数据盘（磁盘层用 safeId，同名覆盖 = 升级，每次都是干净重写）。
     * 返回 safeId（启动 auto-command、卸载、launch_apps 记录都用它）。
     *
     * 流程：
     *  ① mdel 旧 <safeId>.rpk + mdeltree 旧解包树（忽略不存在错误）——先删后写，
     *     规避 mcopy 覆盖行为随 mtools 版本漂移；
     *  ② mcopy 写 ::/resource/package/<safeId>.rpk（vapp 路径#2 备用 + 工坊列表用，
     *     mcopy 自动写的 2 段 LFN 在已实证安全区）；
     *  ③ 宿主解包到临时目录 → mcopy -s 整树到 ::/vapps/<safeId>（vapp 路径#1）；
     *  ④ FatLfnInjector（v2.2.5 修复版）给 /vapps 子树内 8.3 小写条目注入 1 段
     *     LFN 长名链（NuttX 大小写敏感，无 LFN 则小写查找永不命中）→ vapp 零写
     *     启动，guest 不再写盘；
     *  ⑤ 回读校验 manifest 的 package 字段一致才算成功。
     */
    suspend fun install(
        runtime: QemuRuntime,
        dataDisk: File,
        rpkFile: File,
        progress: QemuRuntime.Progress? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            ensureTools(runtime, progress).getOrThrow()
            check(dataDisk.isFile) { "数据盘镜像不存在，请先在设备页部署内置镜像" }
            val parsed = RpkManager.parse(rpkFile).getOrElse {
                throw IllegalStateException("rpk 解析失败: ${it.message}", it)
            }
            val pkgId = parsed.packageId
            check(pkgId.isNotBlank()) { "manifest.json 缺少 package 字段（包名）" }

            // v2.2.5: 磁盘层身份 = 安全短 ID（远离固件 LFN 路径长度缺陷）
            val safeId = QuickAppIds.safeId(pkgId)
            progress?.onStage("清理旧版本 $pkgId…")
            exec(
                runtime,
                listOf(bin(runtime, "mdel").absolutePath, "-i", dataDisk.absolutePath,
                    "::/resource/package/$safeId.rpk"),
            )
            exec(
                runtime,
                listOf(bin(runtime, "mdeltree").absolutePath, "-i", dataDisk.absolutePath,
                    "::/vapps/$safeId"),
            )

            progress?.onStage("写入 $pkgId.rpk 到数据盘…")
            val dest = "::/resource/package/$safeId.rpk"
            val (code, out) = exec(
                runtime,
                listOf(bin(runtime, "mcopy").absolutePath, "-i", dataDisk.absolutePath, "-m", rpkFile.absolutePath, dest),
            )
            check(code == 0) { "mcopy 写入失败: ${out.trim().take(300)}" }

            // 宿主预解包 → ::/vapps/<pkg>（vapp 路径#1 零写启动）
            progress?.onStage("预解包 $pkgId（虚拟机内零写启动）…")
            val unpackDir = File(runtime.tmpDir, "rpk-unpack").apply { deleteRecursively(); mkdirs() }
            unzipTo(rpkFile, File(unpackDir, safeId))
            val (c2, o2) = exec(
                runtime,
                listOf(bin(runtime, "mcopy").absolutePath, "-i", dataDisk.absolutePath, "-n", "-s",
                    File(unpackDir, safeId).absolutePath, "::/vapps/$safeId"),
            )
            check(c2 == 0) { "预解包写入失败: ${o2.trim().take(300)}" }
            unpackDir.deleteRecursively()

            // LFN 长名链注入（NuttX 小写查找唯一可命中形态）；失败仅降级为
            // 路径#2 解包启动（旧行为），不阻断安装
            runCatching { FatLfnInjector.injectVappsSubtree(dataDisk) }
                .onSuccess { FileLogger.i("rpk", "LFN 注入完成: ${it.injected} 条") }
                .onFailure { FileLogger.w("rpk", "LFN 注入失败（降级为解包启动）: ${it.message}") }

            // 回读校验：从镜像内读出的包能解析出一致 packageId
            val back = exportRpk(runtime, dataDisk, "$safeId.rpk")
            check(back != null) { "写入后回读失败（数据盘空间不足或镜像损坏）" }
            val backParsed = RpkManager.parse(back).getOrNull()
            check(backParsed?.packageId == pkgId) { "回读包校验不一致，安装失败" }
            progress?.onStage("安装完成: $pkgId")
            safeId
        }
    }

    /** 解包 rpk（zip）到目标目录（App 宿主侧，java.util.zip） */
    private fun unzipTo(zip: File, dest: File) {
        dest.mkdirs()
        java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val out = File(dest, e.name.normalizePath())
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
    }

    private fun String.normalizePath(): String {
        val parts = split('/').filter { it.isNotEmpty() && it != "." }
        val out = ArrayList<String>(parts.size)
        for (p in parts) {
            if (p == "..") out.removeLastOrNull() else out.add(p)
        }
        return out.joinToString("/")
    }

    /**
     * v2.2.2: 数据盘健康探测（mdir 读根目录）。
     * 返回 (healthy, 原始输出) —— 输出供 isFatCorruptOutput 判定。
     */
    fun diskHealthProbe(
        runtime: QemuRuntime,
        dataDisk: File,
    ): Pair<Boolean, String> {
        if (!dataDisk.isFile) return true to ""
        if (!bin(runtime, "mdir").let { it.exists() && it.canExecute() }) return true to ""
        val (code, out) = exec(
            runtime,
            listOf(bin(runtime, "mdir").absolutePath, "-i", dataDisk.absolutePath, "-b", "::/"),
        )
        return (code == 0) to out
    }

    /** 从数据盘提取 rpk 到本地临时文件（迁移/校验用；entryName = 盘内文件名）。
     *  v2.2.5 公开：旧格式包（真实包名命名）启动前需导出重装迁移。 */
    fun exportRpk(runtime: QemuRuntime, dataDisk: File, entryName: String): File? {
        val outDir = File(runtime.tmpDir, "rpk-read").apply { deleteRecursively(); mkdirs() }
        val (code, _) = exec(
            runtime,
            listOf(
                bin(runtime, "mcopy").absolutePath, "-i", dataDisk.absolutePath, "-n",
                "::/resource/package/$entryName", outDir.absolutePath,
            ),
        )
        if (code != 0) return null
        return outDir.listFiles()?.firstOrNull { it.isFile }
    }

    /**
     * 列出数据盘 /resource/package/ 下的全部 vapp 包（逐个解出 manifest 解析）。
     * 镜像被重新部署后列表自动回到出厂状态（com.vela.demo）。
     */
    suspend fun list(
        runtime: QemuRuntime,
        dataDisk: File,
    ): Result<List<VmPackage>> = withContext(Dispatchers.IO) {
        runCatching {
            // 先检查数据盘存在，再考虑 mtools —— 运行时未装/镜像未部署时
            // 静默返回空列表，绝不触发网络下载（v2.2.1 边界修正）
            check(dataDisk.isFile) { "数据盘镜像不存在" }
            ensureTools(runtime).getOrThrow()
            val outDir = File(runtime.tmpDir, "rpk-list").apply { deleteRecursively(); mkdirs() }
            val (code, out) = exec(
                runtime,
                listOf(
                    bin(runtime, "mcopy").absolutePath, "-i", dataDisk.absolutePath, "-n", "-s",
                    "::/resource/package", outDir.absolutePath,
                ),
            )
            val dir = File(outDir, "package")
            // mcopy 目录复制成功时文件落在 <out>/package/；目录为空时部分版本报错，兼容两种情况
            val files = dir.takeIf { it.isDirectory }?.listFiles { f -> f.isFile && f.name.endsWith(".rpk") }
                ?.orEmpty()
                ?: outDir.listFiles { f -> f.isFile && f.name.endsWith(".rpk") }.orEmpty()
            if (code != 0 && files.isEmpty()) {
                throw IllegalStateException("读取数据盘失败: ${out.trim().take(300)}")
            }
            files.sortedBy { it.name }.mapNotNull { f ->
                runCatching {
                    val p = RpkManager.parse(f).getOrNull() ?: return@runCatching null
                    // v2.2.5: 磁盘文件名 = safeId.rpk；manifest 里的 package = 真实包名。
                    // v2.2.4 及更早版本安装的旧包文件名 = 真实包名.rpk，safeId 兼容回退
                    val stem = f.nameWithoutExtension
                    val safeId = if (stem.startsWith("qa") && stem.length == 12) stem
                                 else QuickAppIds.safeId(p.packageId.ifBlank { stem })
                    VmPackage(
                        packageId = p.packageId.ifBlank { stem },
                        safeId = safeId,
                        name = p.name,
                        versionName = p.versionName,
                        sizeBytes = f.length(),
                        iconFile = p.iconFile,
                        fileName = f.name,
                    )
                }.getOrNull()
            }
        }
    }

    /** 从数据盘移除包（v2.2.5 起参数 = safeId，即 VmPackage.safeId / install 返回值）。
     *  同时清理解包树 ::/vapps/<safeId>（只删 .rpk 的话包在「卸载」后仍能启动）。 */
    suspend fun remove(
        runtime: QemuRuntime,
        dataDisk: File,
        safeId: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            ensureTools(runtime).getOrThrow()
            check(safeId.isNotBlank()) { "包名为空" }
            val (code, out) = exec(
                runtime,
                listOf(
                    bin(runtime, "mdel").absolutePath, "-i", dataDisk.absolutePath,
                    "::/resource/package/$safeId.rpk",
                ),
            )
            check(code == 0) { "mdel 删除失败: ${out.trim().take(300)}" }
            // 清理解包树（不存在时忽略错误）
            exec(
                runtime,
                listOf(
                    bin(runtime, "mdeltree").absolutePath, "-i", dataDisk.absolutePath,
                    "::/vapps/$safeId",
                ),
            )
            Unit
        }
    }
}
