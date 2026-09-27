package com.vela.simulator.quickapp

import com.vela.simulator.engine.QemuRuntime
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
 * 注：曾试验安装时宿主侧预解包到 ::/vapps/<pkg>（让 vapp 零写启动），
 * 实测 vapp 对 mtools 写的 8.3 短名条目无法路径解析（mtools 自身也无法
 * 解析），三轮启动全部回退到重新解包，已废弃该路线。
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
     * 安装 rpk 到数据盘 ::/resource/package/<packageId>.rpk（同名覆盖 = 升级）。
     * v2.2.2: 同时把包内容预解包到 ::/vapps/<packageId>/ —— vapp 启动时发现
     * 解包树已存在即走零写路径，guest 不再对 FAT 做任何解包写盘（防损坏）。
     * 写入后回读校验 manifest 的 package 字段一致才算成功。
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

            progress?.onStage("写入 $pkgId.rpk 到数据盘…")
            val dest = "::/resource/package/$pkgId.rpk"
            val (code, out) = exec(
                runtime,
                listOf(bin(runtime, "mcopy").absolutePath, "-i", dataDisk.absolutePath, "-n", "-m", rpkFile.absolutePath, dest),
            )
            check(code == 0) { "mcopy 写入失败: ${out.trim().take(300)}" }

            // 回读校验：从镜像内读出的包能解析出一致 packageId
            val back = readFromDisk(runtime, dataDisk, "$pkgId.rpk")
            check(back != null) { "写入后回读失败（数据盘空间不足或镜像损坏）" }
            val backParsed = RpkManager.parse(back).getOrNull()
            check(backParsed?.packageId == pkgId) { "回读包校验不一致，安装失败" }
            progress?.onStage("安装完成: $pkgId")
            pkgId
        }
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
