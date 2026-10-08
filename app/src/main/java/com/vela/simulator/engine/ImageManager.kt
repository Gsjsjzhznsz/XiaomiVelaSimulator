package com.vela.simulator.engine

import android.content.Context
import com.vela.simulator.util.NetUa
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 镜像管理器：清单驱动（assets/image_manifest.json）的 VELA 系统镜像
 * 自动下载 + SHA256 校验 + 本地镜像导入。
 *
 * 清单由工程 CI/脚本生成，默认指向本仓库 Release 中托管、
 * 基于 openvela（小米 VELA 官方开源）源码构建的 QEMU 镜像。
 *
 * 国内加速（v0.2.3）：GitHub 直连在境内网络经常超时/失败，下载时自动级联
 * 多个公共 GitHub 反代（gh-proxy 系列），直连成功则直接用，失败逐个换代理重试。
 */
/** v2.2.7: 状态盘部署决策（顶层枚举，随纯函数一起被单测锁定） */
enum class DeployDecision { KEEP, DEPLOY, ADOPT_WRITE_MARKER }

/**
 * v2.2.8: 部署结果报告（参考 vortex 的 AVD 生命周期契约：数据盘是用户状态，
 * 任何重部署都必须可追溯、可回滚）。
 * @param ok 部署链路是否成功（镜像文件就绪）
 * @param statefulMigrated 状态盘是否被重部署（升级换资产/强制修复）——调用方
 *                         必须随后从 files/quickapps 备份恢复用户包
 * @param statefulBackup 重部署前旧盘的备份文件（取证/手动回滚用；首次部署为 null）
 */
data class DeployReport(
    val ok: Boolean,
    val statefulMigrated: Boolean = false,
    val statefulBackup: java.io.File? = null,
)

class ImageManager(private val context: Context) {

    companion object {
        /** GitHub 反代候选（顺序即优先级，空串 = 直连）；失效后可在后续版本更新 */
        val GH_PROXIES = listOf(
            "",                       // 直连优先（代理本身也可能限速/失效）
            "https://gh-proxy.com/",
            "https://ghfast.top/",
            "https://ghproxy.net/",
        )

        /** 内核最小合理体积：内置 openvela 固件均 ≥ 4MB，低于此值视为错误页/残缺文件（v0.2.7） */
        const val MIN_KERNEL_BYTES: Long = 1024L * 1024L

        /** 文件前 4 字节是否为 ELF 魔数 0x7F 'E' 'L' 'F'（v0.2.7，静态：QemuSession 启动前校验也用） */
        fun isElfFile(f: File): Boolean = runCatching {
            if (!f.exists() || f.length() < 4) return@runCatching false
            f.inputStream().use { ins ->
                val head = ByteArray(4)
                var off = 0
                while (off < 4) {
                    val n = ins.read(head, off, 4 - off)
                    if (n < 0) return@runCatching false
                    off += n
                }
                head[0] == 0x7F.toByte() && head[1] == 'E'.code.toByte()
                    && head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte()
            }
        }.getOrDefault(false)

        /**
         * v2.2.7: 状态盘部署决策（纯函数，单测锁定）。
         * 数据盘是用户可写盘 —— 只要部署标记与清单一致，内容 SHA 无论怎么变
         * （装包/卸包/自愈写盘）都必须 KEEP，绝不重拷。
         */
        fun isStatefulImage(out: String, statefulFlag: Boolean): Boolean =
            statefulFlag || out.equals("data.img", ignoreCase = true)

        fun decideStatefulDeploy(
            fileExists: Boolean,
            markerContent: String?,
            manifestSha256: String,
            forceRedeploy: Boolean,
        ): DeployDecision = when {
            !fileExists || forceRedeploy -> DeployDecision.DEPLOY
            markerContent == null -> DeployDecision.ADOPT_WRITE_MARKER
            manifestSha256.isNotEmpty() && !markerContent.equals(manifestSha256, ignoreCase = true) ->
                DeployDecision.DEPLOY
            else -> DeployDecision.KEEP
        }
    }

    val imagesDir: File get() = File(context.filesDir, "images").apply { mkdirs() }

    /**
     * v2.2.13: 计算 APK 内置资产的实际 SHA256（AssetManager 流式哈希，失败返回 null）。
     * 清单是人手维护的元数据，v2.2.12 真机事故实锤「清单滞后」是常态风险：
     * 发布换了新固件资产但清单 sha 未同步 → 部署文件（旧固件）sha == 清单 sha →
     * 误判「一致」→ 新固件永不落盘，用户设备长期停留在旧固件上，
     * 触摸/布局修复全部未生效且从会话日志完全无法察觉。
     * 故 asset:// 条目一律以 APK 资产本身为最高权威，清单 sha 仅作回退参考。 */
    private fun assetSha256(assetPath: String): String? = runCatching {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        context.assets.open(assetPath).use { ins ->
            val buf = ByteArray(128 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                if (n > 0) md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** v2.2.13: asset:// 条目的比对基准 —— 资产实际 sha 优先，哈希失败回退清单 sha */
    private fun authoritativeSha(url: String, manifestSha: String): String =
        if (url.startsWith("asset://")) assetSha256(url.removePrefix("asset://")) ?: manifestSha
        else manifestSha

    @Serializable
    data class ImageFile(val url: String, val sha256: String = "", val size: Long = 0, val out: String, val stateful: Boolean = false)

    @Serializable
    data class ImageEntry(
        val id: String,
        val name: String,
        val desc: String = "",
        val machine: String,
        val kernel: String,
        val files: List<ImageFile>,
        val source: String = "",
    )

    @Serializable
    data class Manifest(val version: Int = 1, val updated: String = "", val images: List<ImageEntry>)

    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .addInterceptor(NetUa.interceptor)
        .build()

    var progress: QemuRuntime.Progress? = null

    fun loadManifest(): Manifest = runCatching {
        json.decodeFromString(
            Manifest.serializer(),
            context.assets.open("image_manifest.json").bufferedReader().readText()
        )
    }.getOrElse { Manifest(images = emptyList()) }

    fun kernelFile(name: String): File = File(imagesDir, name)

    /**
     * 就绪判定（v0.2.7 加固）：文件存在 + ≥ 1MB。
     * v2.1 放宽：raw bin 固件（如 vapp nuttx.bin）无 ELF 魔数，但错误页/残缺
     * 响应几乎不可能 ≥1MB，体积下限已是可靠防护，ELF 魔数改为可选特征。
     */
    fun isKernelReady(name: String): Boolean {
        val f = kernelFile(name)
        return f.exists() && f.length() >= MIN_KERNEL_BYTES
    }

    /** 本地内核文件实际大小（字节）；不存在返回 0。供 UI 显示真实体积 */
    fun kernelSize(name: String): Long = kernelFile(name).takeIf { it.exists() }?.length() ?: 0L

    fun verifyKernel(name: String, sha256: String): Boolean {
        val f = kernelFile(name)
        if (!f.exists() || sha256.isEmpty()) return false
        return runCatching { QemuRuntime(context).sha256(f).equals(sha256, ignoreCase = true) }.getOrDefault(false)
    }

    /**
     * 下载清单条目中的全部文件（GitHub 资产自动级联反代重试 + 分段并行提速）。
     * 多文件时并行下载（并发 3），单文件内部走 HttpDownloader 分段逻辑。
     * v2.1: 支持 asset:// 内置镜像（APK assets 直拷，零网络，秒级就绪）。
     */
    suspend fun download(entry: ImageEntry) = withContext(Dispatchers.IO) {
        imagesDir.mkdirs()
        val sem = Semaphore(3)
        coroutineScope {
            entry.files.map { f ->
                launch(Dispatchers.IO) {
                    sem.withPermit {
                        val dest = kernelFile(f.out)
                        if (f.url.startsWith("asset://")) {
                            copyFromAssets(f.url.removePrefix("asset://"), dest, f.out, f.sha256)
                        } else {
                            downloadWithFallback(f.url, dest, f.out, f.size)
                        }
                    }
                }
            }.joinAll()
        }
        // 校验与就绪提示在全部下载完成后统一执行；SHA 不符立即删除坏文件（v0.2.7）
        for (f in entry.files) {
            if (f.sha256.isNotEmpty()) {
                progress?.onStage("SHA256 校验 ${f.out}")
                val dest = kernelFile(f.out)
                val actual = QemuRuntime(context).sha256(dest)
                if (!actual.equals(f.sha256, ignoreCase = true)) {
                    runCatching { dest.delete() }
                    throw IOException("${f.out} SHA256 校验失败（已删除损坏文件，可重新下载）")
                }
            }
        }
        progress?.onStage("镜像就绪")
    }

    /** v2.1: 从 APK assets 拷贝内置镜像（asset://images/xxx → images/xxx）
     *  v2.2.1: 清单含 SHA256 时校验旧文件，不匹配则重新拷贝 —— 修复升级后旧
     *  损坏镜像永不替换的问题（本版 data.img 重建了 FAT 一致性，必须能覆盖旧文件）
     *  v2.2.8: 原子化落盘（参考 vortex VelaImageStore 的 .part → renameTo 契约）——
     *  先写 <dest>.tmp 并 fsync，再 rename 覆盖。旧实现 O_TRUNC 直写活盘 + 预删除，
     *  拷贝中途进程被杀会留下 0 字节/半截 data.img → FAT 全毁 → 触发 DiskDoctor
     *  再抹一次的连锁丢盘；rename 在 POSIX 语义下要么全旧要么全新，无中间态。 */
    private fun copyFromAssets(assetPath: String, dest: File, label: String, expectSha256: String = "") {
        if (dest.isFile && dest.length() > 0) {
            // v2.2.13: 比对基准改为资产实际 sha（清单滞后不再让旧文件误判「已就绪」跳过重拷）
            val expected = authoritativeSha("asset://$assetPath", expectSha256)
            val stale = expected.isNotEmpty() && !verifyKernel(label, expected)
            if (!stale) {
                progress?.onStage("内置镜像已就绪 $label")
                return
            }
            progress?.onStage("内置镜像版本更新，重新部署 $label")
        }
        atomicCopyAsset(assetPath, dest, label)
    }

    /** v2.2.8: 无条件原子拷贝（不走「已就绪早退」，供部署决策分支显式调用） */
    private fun atomicCopyAsset(assetPath: String, dest: File, label: String) {
        progress?.onStage("部署内置镜像 $label")
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        context.assets.open(assetPath).use { ins ->
            java.io.FileOutputStream(tmp).use { outs ->
                ins.copyTo(outs)
                outs.fd.sync()
            }
        }
        if (!tmp.renameTo(dest)) {
            // 极端文件系统不支持覆盖 rename → 退化为先删后改（此时新盘已完整落盘）
            dest.delete()
            check(tmp.renameTo(dest)) { "镜像部署 rename 失败: $tmp -> $dest" }
        }
        progress?.onLog("内置镜像部署完成: $label")
    }

    /**
     * v2.2.8: 重部署前对状态盘做快照备份（data.img.bak-<时间戳>）。
     * 参考 vortex「wipeData 仅显式且可追溯」：任何会覆盖用户盘的操作都必须
     * 先留可回滚副本。保留最近 2 份，超出自动清理（64MB/份，防存储膨胀）。
     */
    private fun backupStatefulImage(dest: File, label: String): File? {
        if (!dest.isFile || dest.length() <= 0L) return null
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val bak = File(imagesDir, "$label.bak-$stamp")
        val ok = runCatching {
            val tmp = File(imagesDir, bak.name + ".tmp")
            dest.inputStream().use { ins ->
                java.io.FileOutputStream(tmp).use { outs -> ins.copyTo(outs); outs.fd.sync() }
            }
            tmp.renameTo(bak)
        }.getOrDefault(false)
        if (!ok) {
            com.vela.simulator.util.FileLogger.w("image", "状态盘备份失败（继续重部署，无回滚副本）: $bak")
            return null
        }
        com.vela.simulator.util.FileLogger.i("image", "状态盘已备份: ${bak.name} (${dest.length()}B)")
        // 保留最近 2 份
        imagesDir.listFiles { f -> f.isFile && f.name.startsWith("$label.bak-") }
            ?.sortedByDescending { it.name }
            ?.drop(2)
            ?.forEach { runCatching { it.delete() } }
        return bak
    }

    /**
     * v2.2.1: 确保本地镜像与清单一致（asset:// 项 SHA 不符时自动重部署，零网络）。
     * 返回 true = 本地镜像与清单一致（或无法校验但文件存在）。
     *
     * v2.2.7 真机实锤重写（用户日志 commit 97bbabf，09-28 22:17 会话）：
     * 旧实现对【所有】asset 文件按「部署文件内容 SHA」判定新旧 —— 而数据盘是
     * 用户可写状态盘，工坊每装一个 rpk 内容必变 → SHA 必然 ≠ 出厂清单 →
     * 【每次启动会话都会把 data.img 删掉重拷】，用户包被静默抹光 → vapp 恒报
     * package not found → 自愈恢复 → 重启又被抹 → 循环到自愈上限（日志实锤：
     * 22:17:45.364 列表 2 个包 → startSession → 45.510 只剩 demo）。
     *
     * 修复语义：
     *  - 只读内核（nuttx.bin 等）：维持内容 SHA 校验（损坏可发现，重拷无副作用）；
     *  - 状态盘（stateful=true / out=="data.img"）：改用「部署标记」
     *    <out>.deployed（内容 = 清单 sha256，即 APK 内置资产的出厂身份）：
     *      · 文件缺失            → 部署 + 写标记；
     *      · 标记缺失、文件在    → 采纳现状（老用户升级首启，绝不抹盘）+ 写标记；
     *      · 标记 ≠ 清单 sha     → APK 换了新资产 → 一次性重部署 + 写标记；
     *      · 其余（含内容 SHA 任何变化）→ 保留现状（用户包不受影响）。
     *  - forceRedeploy=true（仅 DiskDoctor 修复路径用）：删文件+标记强制重拷，
     *    部署后由调用方负责从 files/quickapps 备份恢复用户包。
     *
     * v2.2.8 硬化（用户反馈「数据盘一直丢失」+ vortex 契约参考）：
     *  - 状态盘重部署前自动快照备份（data.img.bak-<ts>，留 2 份），任何情况
     *    都不再「先删后拷」裸奔；
     *  - 返回 DeployReport：调用方可感知「状态盘被重部署」并立即恢复用户包，
     *    旧 Boolean 返回把迁移静默化，是升级换资产后包全丢的盲区；
     *  - 拷贝全程原子化（tmp+rename），进程被杀不再留下半截盘。
     */
    suspend fun ensureAssetsCurrent(entryId: String, forceRedeploy: Boolean = false): DeployReport = withContext(Dispatchers.IO) {
        val entry = loadManifest().images.firstOrNull { it.id == entryId }
            ?: return@withContext DeployReport(true)
        var allOk = true
        var migrated = false
        var lastBackup: File? = null
        for (f in entry.files) {
            if (!f.url.startsWith("asset://")) continue
            val dest = kernelFile(f.out)
            val marker = deployedMarker(f.out)
            if (forceRedeploy) {
                runCatching { marker.delete() }
            }
            val missing = !dest.isFile || dest.length() <= 0
            if (isStatefulImage(f.out, f.stateful)) {
                var manifestSha = f.sha256
                val markerContent = if (marker.isFile) runCatching { marker.readText().trim() }.getOrNull() else null
                // v2.2.13: 标记与清单不一致时，先核对 APK 资产实际 sha——一致则
                // 视为「清单滞后但资产未变」，KEEP（避免无谓抹盘迁移）；仍不一致
                // 才走一次性迁移（真正换了出厂数据盘资产）。64MB 哈希只在
                // 标记≠清单的分支执行，常规启动零额外开销。
                if (markerContent != null && manifestSha.isNotEmpty() &&
                    !markerContent.equals(manifestSha, ignoreCase = true)) {
                    manifestSha = authoritativeSha(f.url, manifestSha)
                }
                when (decideStatefulDeploy(!missing, markerContent, manifestSha, forceRedeploy)) {
                    DeployDecision.DEPLOY -> {
                        val replacing = dest.isFile
                        if (replacing) {
                            // APK 升级更换出厂数据盘资产 / DiskDoctor 强制修复
                            // → 先备份旧盘再重部署（v2.2.8：绝不无副本覆盖用户盘）
                            progress?.onStage("数据镜像版本更新，备份并重新部署 ${f.out}")
                            lastBackup = backupStatefulImage(dest, f.out)
                        } else {
                            progress?.onStage("部署内置镜像 ${f.out}")
                        }
                        // v2.2.8: 无条件原子拷贝（不走 copyFromAssets 的已就绪早退，
                        // 否则重部署会被静默跳过）
                        atomicCopyAsset(f.url.removePrefix("asset://"), dest, f.out)
                        if (manifestSha.isNotEmpty() && !verifyKernel(f.out, manifestSha)) allOk = false
                        writeDeployedMarker(f.out, manifestSha)
                        if (replacing) migrated = true
                    }
                    DeployDecision.ADOPT_WRITE_MARKER -> {
                        /* v2.2.8 语义升级：老版本升级首启（标记缺失、盘在）= 一次性迁移。
                         * v2.2.7 的「原样采纳」会让 v2.2.4-2.2.7 的 spc=8 旧盘永远停留
                         * 在固件跨簇读取缺陷区（真实包 app.js 必挂）——本版出厂数据盘
                         * 回归 spc=1，必须迁移。安全性：先备份旧盘（可回滚），重部署后
                         * 由调用方从 files/quickapps 备份恢复用户包（DeployReport.migrated）。 */
                        progress?.onStage("数据盘布局升级，备份并迁移 ${f.out}")
                        lastBackup = backupStatefulImage(dest, f.out)
                        atomicCopyAsset(f.url.removePrefix("asset://"), dest, f.out)
                        if (manifestSha.isNotEmpty() && !verifyKernel(f.out, manifestSha)) allOk = false
                        writeDeployedMarker(f.out, manifestSha)
                        migrated = true
                    }
                    DeployDecision.KEEP -> { /* 标记匹配：用户数据盘原样保留（装包导致的 SHA 变化无关紧要） */ }
                }
                if (!dest.isFile) allOk = false
            } else {
                if (missing) {
                    copyFromAssets(f.url.removePrefix("asset://"), dest, f.out, f.sha256)
                    allOk = allOk && dest.isFile
                    continue
                }
                if (f.url.startsWith("asset://")) {
                    // v2.2.13: 内置只读内核以 APK 资产实际 sha 为最高权威（清单滞后
                    // 不再阻断固件交付）。v2.2.12 真机事故：清单 sha 滞留旧固件值，
                    // 部署文件 sha 碰巧与之相等 → 误判「一致」→ 新固件永不部署，
                    // 用户的触摸/布局修复长期未生效。成本：每次会话启动多一次
                    // ~3.5MB 哈希（几十毫秒），清单正确时行为与旧逻辑完全一致。
                    val expected = authoritativeSha(f.url, f.sha256)
                    if (expected.isNotEmpty()) {
                        val actual = runCatching { QemuRuntime(context).sha256(dest) }.getOrDefault("")
                        if (!actual.equals(expected, ignoreCase = true)) {
                            // 内核版本更新/损坏 → 原子重拷（rename 覆盖，无中间态）
                            progress?.onStage("内核镜像版本更新，重新部署 ${f.out}")
                            atomicCopyAsset(f.url.removePrefix("asset://"), dest, f.out)
                            val after = runCatching { QemuRuntime(context).sha256(dest) }.getOrDefault("")
                            if (!after.equals(expected, ignoreCase = true)) allOk = false
                        }
                    }
                } else if (f.sha256.isNotEmpty()) {
                    val actual = runCatching { QemuRuntime(context).sha256(dest) }.getOrDefault("")
                    if (!actual.equals(f.sha256, ignoreCase = true)) {
                        // 只读内核损坏 → 原子重拷（rename 覆盖，无中间态）
                        progress?.onStage("内核镜像版本更新，重新部署 ${f.out}")
                        copyFromAssets(f.url.removePrefix("asset://"), dest, f.out, f.sha256)
                        val after = runCatching { QemuRuntime(context).sha256(dest) }.getOrDefault("")
                        if (!after.equals(f.sha256, ignoreCase = true)) allOk = false
                    }
                }
            }
        }
        DeployReport(allOk, migrated, lastBackup)
    }

    /** v2.2.7: 状态盘部署标记（内容 = 清单 sha256 = APK 内置资产的出厂身份） */
    private fun deployedMarker(out: String): File = File(imagesDir, "$out.deployed")

    private fun writeDeployedMarker(out: String, manifestSha256: String) {
        if (manifestSha256.isEmpty()) return
        runCatching { deployedMarker(out).writeText(manifestSha256) }
    }

    /**
     * 单文件下载：直连 → 逐个反代重试，命中线路内部分段并行。
     * 判定标准（v0.2.7 加固）：接收字节数 ≥ 清单声明体积（清单 size=0 时退化为 >0）。
     * 修复：此前仅 bytes>0 即算成功，部分代理对失效资源返回 200+1KB 错误页会被落盘。
     */
    private suspend fun downloadWithFallback(
        url: String,
        dest: File,
        label: String,
        expectedSize: Long = 0L,
    ) = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        for (proxy in GH_PROXIES) {
            val target = if (proxy.isEmpty()) url else proxy + url
            try {
                progress?.onLog("下载 $label${if (proxy.isEmpty()) "（直连）" else "（代理 ${proxy.removePrefix("https://").trimEnd('/')})"}")
                val bytes = httpGet(target, dest, label, expectedSize)
                if (bytes > 0 && (expectedSize <= 0 || bytes >= expectedSize)) return@withContext
                error("响应体积不符: got ${bytes}B want ${expectedSize}B（可能为错误页/残缺响应）")
            } catch (e: Exception) {
                lastError = e
                runCatching { dest.delete() }
                progress?.onLog("下载失败: ${e.message}，切换线路重试")
                com.vela.simulator.util.FileLogger.w("image", "$label 经 ${proxy.ifEmpty { "direct" }} 下载失败: ${e.message}")
            }
        }
        throw IOException("镜像下载失败（已尝试直连与 ${GH_PROXIES.size - 1} 个代理）: $url", lastError)
    }

    /** 流式/分段下载到 dest，返回接收的字节数（>1.5MB 自动 4 段并行） */
    private suspend fun httpGet(url: String, dest: File, label: String, expectedSize: Long): Long = withContext(Dispatchers.IO) {
        com.vela.simulator.util.HttpDownloader.download(http, url, dest, expectedSize) { read ->
            // v0.2.7：total 传清单声明体积（此前恒传 -1，UI 无法显示百分比与总量）
            progress?.onFileProgress(label, read, expectedSize)
        }
    }

    /** 从 SAF Uri 导入本地镜像（v2.2.8: 原子落盘 tmp+rename，中途失败不留半截文件） */
    fun import(uri: android.net.Uri, outName: String): Result<File> = runCatching {
        imagesDir.mkdirs()
        val dest = kernelFile(outName)
        val tmp = File(imagesDir, "$outName.tmp")
        context.contentResolver.openInputStream(uri)?.use { ins ->
            java.io.FileOutputStream(tmp).use { outs ->
                ins.copyTo(outs)
                outs.fd.sync()
            }
        } ?: error("无法读取所选文件")
        if (!tmp.renameTo(dest)) {
            dest.delete()
            check(tmp.renameTo(dest)) { "镜像导入 rename 失败: $tmp -> $dest" }
        }
        dest
    }

    fun listLocalImages(): List<File> = imagesDir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()

    fun deleteImage(name: String): Boolean = kernelFile(name).delete()
}
