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
 * v2.2.4 根因闭环（桌面 e2e + guest 逐级探测实锤）：预解包路线 v2.2.2 被
 * 误判废弃，真正根因是三层叠加：① NuttX FAT 查找对 8.3 条目是原始字节
 * 大小写敏感（/data/RESOURCE 可查，/data/resource ENOENT）；② 小写字节的
 * 8.3 条目被视为非法，LFN 长名链是唯一能命中小写查找的形态；③ vapp_main.c
 * 的解包树路径是小写 /data/vapps/<pkg>，因此永远查不到 mtools 写的大写短名
 * 树 → 每次首启都走解包写盘 → 触发固件 FAT 半更新缺陷 → 数据盘亚损坏
 * （mtools 宽容可读、NuttX 拒绝）→ vapp 报 package not found → 黑屏。
 * 修复：install = mdel 先删 + mcopy 写 rpk + 宿主预解包到 ::/vapps/<pkg> +
 * FatLfnInjector 注入小写 LFN 长名链 → vapp 命中路径#1 零写启动，guest 不再
 * 写数据盘，损坏代码路径不可达（e2e 实证整周期镜像字节零变化）。
 */
object RpkInstaller {

    /** v2.2.2: mtools 输出中判定 FAT 已损坏的特征串（真机实锤：
     *  "Cluster # at 6 too big(0xfff0013)" / "Error reading FAT" 等）。
     *  纯函数（单测锁定） */
    fun isFatCorruptOutput(out: String): Boolean = listOf(
        "error reading fat", "cannot initialize", "non ms-dos disk", "too big(",
    ).any { out.lowercase().contains(it) }

    /** 数据盘中解析出的 vapp 包 */
    data class VmPackage(
        val packageId: String,
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
     * 安装 rpk 到数据盘（同名覆盖 = 升级，v2.2.4 起每次都是干净重写）。
     *
     * 流程：
     *  ① mdel 旧 .rpk + mdeltree 旧解包树（忽略不存在错误）——v2.2.2 教训：
     *     mcopy -n 在条目已存在/已损坏时行为随 mtools 版本漂移（桌面 rc=1、
     *     Termux 静默跳过），损坏条目永不重写 → 先删后写，行为确定；
     *  ② mcopy 写 ::/resource/package/<pkg>.rpk（vapp 路径#2 备用 + 工坊列表用）；
     *  ③ 宿主解包到临时目录 → mcopy -s 整树到 ::/vapps/<pkg>（vapp 路径#1）；
     *  ④ FatLfnInjector 给 /vapps 子树注入小写 LFN 长名链（NuttX 大小写敏感，
     *     无 LFN 则小写查找永不命中）→ vapp 零写启动，guest 不再写盘；
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

            progress?.onStage("清理旧版本 $pkgId…")
            exec(
                runtime,
                listOf(bin(runtime, "mdel").absolutePath, "-i", dataDisk.absolutePath,
                    "::/resource/package/$pkgId.rpk"),
            )
            exec(
                runtime,
                listOf(bin(runtime, "mdeltree").absolutePath, "-i", dataDisk.absolutePath,
                    "::/vapps/$pkgId"),
            )

            progress?.onStage("写入 $pkgId.rpk 到数据盘…")
            val dest = "::/resource/package/$pkgId.rpk"
            val (code, out) = exec(
                runtime,
                listOf(bin(runtime, "mcopy").absolutePath, "-i", dataDisk.absolutePath, "-m", rpkFile.absolutePath, dest),
            )
            check(code == 0) { "mcopy 写入失败: ${out.trim().take(300)}" }

            // 宿主预解包 → ::/vapps/<pkg>（vapp 路径#1 零写启动）
            progress?.onStage("预解包 $pkgId（虚拟机内零写启动）…")
            val unpackDir = File(runtime.tmpDir, "rpk-unpack").apply { deleteRecursively(); mkdirs() }
            unzipTo(rpkFile, File(unpackDir, pkgId))
            val (c2, o2) = exec(
                runtime,
                listOf(bin(runtime, "mcopy").absolutePath, "-i", dataDisk.absolutePath, "-n", "-s",
                    File(unpackDir, pkgId).absolutePath, "::/vapps/$pkgId"),
            )
            check(c2 == 0) { "预解包写入失败: ${o2.trim().take(300)}" }
            unpackDir.deleteRecursively()

            // LFN 长名链注入（NuttX 小写查找唯一可命中形态）；失败仅降级为
            // 路径#2 解包启动（旧行为），不阻断安装
            runCatching { FatLfnInjector.injectVappsSubtree(dataDisk) }
                .onSuccess { FileLogger.i("rpk", "LFN 注入完成: ${it.injected} 条") }
                .onFailure { FileLogger.w("rpk", "LFN 注入失败（降级为解包启动）: ${it.message}") }

            // 回读校验：从镜像内读出的包能解析出一致 packageId
            val back = readFromDisk(runtime, dataDisk, "$pkgId.rpk")
            check(back != null) { "写入后回读失败（数据盘空间不足或镜像损坏）" }
            val backParsed = RpkManager.parse(back).getOrNull()
            check(backParsed?.packageId == pkgId) { "回读包校验不一致，安装失败" }
            progress?.onStage("安装完成: $pkgId")
            pkgId
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

    /** 从数据盘提取 rpk 到本地临时文件（校验/详情用） */
    private fun readFromDisk(runtime: QemuRuntime, dataDisk: File, fileName: String): File? {
        val outDir = File(runtime.tmpDir, "rpk-read").apply { deleteRecursively(); mkdirs() }
        val (code, _) = exec(
            runtime,
            listOf(
                bin(runtime, "mcopy").absolutePath, "-i", dataDisk.absolutePath, "-n",
                "::/resource/package/$fileName", outDir.absolutePath,
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
                    VmPackage(
                        packageId = p.packageId.ifBlank { f.nameWithoutExtension },
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

    /** 从数据盘移除包（内置 com.vela.demo 允许移除；重新部署内置镜像即可恢复）。
     *  v2.2.1: 同时清理解包树 ::/vapps/<pkg>（vapp 路径#1 优先用解包树，
     *  只删 .rpk 的话包在「卸载」后仍能启动）。v2.2.2 起解包树由宿主预解包产生。 */
    suspend fun remove(
        runtime: QemuRuntime,
        dataDisk: File,
        packageId: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            ensureTools(runtime).getOrThrow()
            check(packageId.isNotBlank()) { "包名为空" }
            val (code, out) = exec(
                runtime,
                listOf(
                    bin(runtime, "mdel").absolutePath, "-i", dataDisk.absolutePath,
                    "::/resource/package/$packageId.rpk",
                ),
            )
            check(code == 0) { "mdel 删除失败: ${out.trim().take(300)}" }
            // 清理解包树（不存在时忽略错误）
            exec(
                runtime,
                listOf(
                    bin(runtime, "mdeltree").absolutePath, "-i", dataDisk.absolutePath,
                    "::/vapps/$packageId",
                ),
            )
            Unit
        }
    }
}
