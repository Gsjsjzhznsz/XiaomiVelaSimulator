package com.vela.simulator.engine

import android.content.Context
import com.vela.simulator.util.DebExtractor
import com.vela.simulator.util.HttpDownloader
import com.vela.simulator.util.NetUa
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * QEMU 运行时管理器 —— 仿 Termux 方案：
 * 首次启动时从 Termux apt 仓库解析 qemu-system-arm-headless 及其依赖树，
 * 下载 .deb 包并解包到应用私有目录（PREFIX），执行时注入环境变量。
 *
 * 健壮性设计（v0.2.3，修复用户真机 HTTP 404/403）：
 * - 包名：Termux 仓库自 qemu 10.x 起更名为 qemu-system-arm-headless（旧的
 *   qemu-system-arm 已不存在，索引命中后依赖解析为空）；
 * - 索引格式：仓库已取消 Packages.xz，自动回退 Packages.gz / .bz2 / 纯 Packages；
 * - UA：默认 okhttp UA 会触发部分镜像站 WAF 403，统一使用浏览器 UA；
 * - 镜像级联：官方源 + BFSU/USTC/NJU/SJTU/TUNA 五大镜像，逐源自动重试；
 * - 竞速选源（v0.2.4）：auto 模式并发探测全部软件源，谁先吐出索引就用谁，
 *   境外用户自动命中官方源，境内用户自动命中国内镜像，403/404 源秒级淘汰；
 * - 并行下载（v0.2.4）：依赖包 4 路并发 + 单文件 4 段 Range 分段，
 *   总下载时间从串行逐个降至约 1/4；单包失败自动换源续传。
 */
class QemuRuntime(private val context: Context) {
    companion object {
        const val REPO_OFFICIAL = "https://packages.termux.dev/apt/termux-main"
        const val REPO_TUNA = "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main"
        const val REPO_BFSU = "https://mirrors.bfsu.edu.cn/termux/apt/termux-main"
        const val REPO_USTC = "https://mirrors.ustc.edu.cn/termux/apt/termux-main"
        const val REPO_NJU = "https://mirror.nju.edu.cn/termux/apt/termux-main"
        const val REPO_SJTU = "https://mirror.sjtu.edu.cn/termux/apt/termux-main"

        /**
         * 软件源清单（v0.2.4 扩充）：官方源 + 5 个教育网镜像。
         * 官方源排第一：境外用户直连最快；国内镜像作为境内加速与容灾 fallback。
         */
        val MIRRORS = listOf("official", "bfsu", "ustc", "nju", "sjtu", "tuna")

        fun repoBase(mirror: String): String = when (mirror) {
            "tuna" -> REPO_TUNA
            "bfsu" -> REPO_BFSU
            "ustc" -> REPO_USTC
            "nju" -> REPO_NJU
            "sjtu" -> REPO_SJTU
            else -> REPO_OFFICIAL
        }

        /** 依赖包并行下载并发数 */
        const val DOWNLOAD_CONCURRENCY = 4

        const val ROOT_PACKAGE = "qemu-system-arm-headless"
        const val RUNTIME_VERSION = "2" // v2: 包名/索引格式变更，旧安装标记失效需重装

        /** Termux 二进制的前缀（编译期硬编码路径），用于 .deb 路径重映射 */
        const val TERMUX_PREFIX = "data/data/com.termux/files"

        /** (文件名, 解压函数) 候选表，顺序即优先级；Termux 仓库已下线 Packages.xz */
        val INDEX_FORMATS: List<Pair<String, (java.io.InputStream) -> java.io.InputStream>> = listOf(
            "Packages.xz" to { ins -> org.tukaani.xz.XZInputStream(ins) },
            "Packages.gz" to { ins -> java.util.zip.GZIPInputStream(ins) },
            "Packages.bz2" to { ins ->
                org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(ins)
            },
            "Packages" to { ins -> ins },
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    val prefix: File get() = File(context.filesDir, "qemu-prefix")
    val prefixUsr: File get() = File(prefix, "usr")

    /**
     * QEMU 主二进制探测：headless 包安装为 qemu-system-arm-headless，
     * 旧包（≤9.x）为 qemu-system-arm。运行时自动探测存在的那个。
     */
    val qemuBinary: File
        get() = sequenceOf("bin/qemu-system-arm-headless", "bin/qemu-system-arm")
            .map { File(prefixUsr, it) }
            .firstOrNull { it.exists() }
            ?: File(prefixUsr, "bin/qemu-system-arm-headless")
    val tmpDir: File get() = File(prefixUsr, "tmp")
    val homeDir: File get() = File(prefixUsr, "home")

    val isInstalled: Boolean get() = qemuBinary.exists() && qemuBinary.canExecute()

    val installMark: File get() = File(context.filesDir, "runtime-v$RUNTIME_VERSION.installed")

    @Serializable
    data class PkgEntry(
        val pkg: String,
        val version: String = "",
        val depends: String = "",
        val filename: String = "",
        val sha256: String = "",
        val size: Long = 0,
    )

    @Serializable
    data class RuntimeStatus(
        val installed: Boolean = false,
        val packagesInstalled: Int = 0,
        val qemuVersion: String = "",
    )

    /** 下载进度回调 (已下载字节, 总字节, 当前文件名, 阶段描述) */
    interface Progress {
        fun onStage(stage: String)
        fun onFileProgress(name: String, downloaded: Long, total: Long)
        fun onLog(line: String)
    }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(NetUa.interceptor)
        .build()

    /** 竞速探测专用客户端：短超时，快速淘汰不可达源 */
    private val probeHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(NetUa.interceptor)
        .build()

    /**
     * 拉取并解析 apt Packages 索引，自动探测压缩格式。
     * Termux 仓库 2026 起仅提供 Packages / Packages.gz / Packages.bz2（Packages.xz 已下线）。
     * 每种格式逐个尝试，404 则换下一个，命中即解析。
     */
    suspend fun fetchPackageIndex(mirror: String, abi64: Boolean): Map<String, PkgEntry> = withContext(Dispatchers.IO) {
        val arch = if (abi64) "aarch64" else "arm"
        val base = "${repoBase(mirror)}/dists/stable/main/binary-$arch"
        progress?.onStage("获取软件源索引（$arch @ $mirror）")
        var lastError: Exception? = null
        for ((suffix, decompress) in INDEX_FORMATS) {
            val url = "$base/$suffix"
            try {
                val req = Request.Builder().url(url).build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        error("HTTP ${resp.code}")
                    }
                    val plain = decompress(resp.body!!.byteStream())
                    val map = parsePackages(plain.bufferedReader().readText())
                    if (map.isNotEmpty()) {
                        progress?.onLog("索引获取成功: $suffix (${map.size} 包)")
                        return@withContext map
                    }
                    error("索引为空")
                }
            } catch (e: Exception) {
                lastError = e
                progress?.onLog("$suffix 不可用 (${e.message})，尝试下一格式")
            }
        }
        throw IllegalStateException("获取 Packages 索引失败（$mirror）: ${lastError?.message}", lastError)
    }

    /** 解析 apt Packages 文本格式 */
    fun parsePackages(text: String): Map<String, PkgEntry> {
        val map = HashMap<String, PkgEntry>()
        var pkg = ""; var ver = ""; var dep = ""; var fn = ""; var sha = ""; var size = 0L
        fun flush() {
            if (pkg.isNotEmpty()) map[pkg] = PkgEntry(pkg, ver, dep, fn, sha, size)
            pkg = ""; ver = ""; dep = ""; fn = ""; sha = ""; size = 0
        }
        for (raw in text.lineSequence()) {
            when {
                raw.startsWith("Package: ") -> { flush(); pkg = raw.removePrefix("Package: ").trim() }
                raw.startsWith("Version: ") -> ver = raw.removePrefix("Version: ").trim()
                raw.startsWith("Depends: ") -> dep = raw.removePrefix("Depends: ").trim()
                raw.startsWith("Filename: ") -> fn = raw.removePrefix("Filename: ").trim()
                raw.startsWith("SHA256: ") -> sha = raw.removePrefix("SHA256: ").trim()
                raw.startsWith("Size: ") -> size = raw.removePrefix("Size: ").trim().toLongOrNull() ?: 0
            }
        }
        flush()
        return map
    }

    /** 依赖闭包解析（广度优先，忽略版本约束与 alternatives） */
    fun resolveDeps(index: Map<String, PkgEntry>, root: String): List<PkgEntry> {
        val out = LinkedHashMap<String, PkgEntry>()
        val queue = ArrayDeque(listOf(root))
        val seen = HashSet<String>()
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            if (!seen.add(name)) continue
            val entry = index[name] ?: continue // 源中缺失则跳过（如 essential 内建包）
            out[name] = entry
            entry.depends.split(',').forEach { clause ->
                val first = clause.trim().split('|').firstOrNull()?.trim() ?: return@forEach
                val depName = first.substringBefore('(').trim()
                if (depName.isNotEmpty()) queue.addLast(depName)
            }
        }
        return out.values.toList()
    }

    var progress: Progress? = null

    /**
     * 完整安装流程：选源（竞速/指定）→ 索引 → 依赖闭包 → 并行下载（多源容错 + 分段）
     * → 校验 → 解包 → 授权。
     * auto 模式：全部软件源并发竞速，首个成功吐出索引的源胜出（其余连接立即取消），
     * 下载阶段单包失败还会在其余源之间自动换源重试。
     */
    suspend fun install(mirror: String = "auto") = withContext(Dispatchers.IO) {
        val abi64 = android.os.Build.SUPPORTED_ABIS.firstOrNull()?.contains("64") == true
        if (mirror == "auto") {
            progress?.onStage("正在竞速选择最快软件源…")
            val chosen = probeFastestMirror(abi64)
                ?: throw IllegalStateException("全部软件源均不可达（${MIRRORS.joinToString()}），请检查网络后重试")
            val (name, index) = chosen
            installFrom(name, index, MIRRORS.filter { it != name })
        } else {
            progress?.onLog("使用指定软件源: $mirror (${repoBase(mirror)})")
            installFrom(mirror, fetchPackageIndex(mirror, abi64), emptyList())
        }
    }

    /**
     * 并发竞速探测全部软件源：同时对每个源发起 Packages.gz 请求（8s 连接超时），
     * 第一个成功解析出非空索引的源胜出，其余请求通过 job.cancel() 即时取消。
     * 全部失败返回 null。胜出时同时返回已解析索引，避免二次下载。
     */
    private suspend fun probeFastestMirror(
        abi64: Boolean,
    ): Pair<String, Map<String, PkgEntry>>? = coroutineScope {
        val arch = if (abi64) "aarch64" else "arm"
        val chan = kotlinx.coroutines.channels.Channel<Pair<String, Map<String, PkgEntry>>>(1)
        val jobs = MIRRORS.map { m ->
            launch(Dispatchers.IO + kotlinx.coroutines.CoroutineName("probe-$m")) {
                runCatching {
                    val base = "${repoBase(m)}/dists/stable/main/binary-$arch"
                    val req = Request.Builder().url("$base/Packages.gz").build()
                    probeHttp.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) error("HTTP ${resp.code}")
                        val text = java.util.zip.GZIPInputStream(resp.body!!.byteStream())
                            .bufferedReader().readText()
                        val map = parsePackages(text)
                        if (map.isEmpty()) error("索引为空")
                        map
                    }
                }.onSuccess { chan.trySend(m to it) }
                    .onFailure { progress?.onLog("软件源 $m 不可用: ${it.message}") }
            }
        }
        try {
            val winner = withTimeoutOrNull(30_000L) { chan.receive() }
            if (winner != null) {
                progress?.onLog("竞速选定软件源: ${winner.first}（其余源已取消）")
            }
            winner
        } finally {
            jobs.forEach { it.cancel() }
        }
    }

    /** 镜像失败时记录到应用文件日志（供 vela.log 排障） */
    private fun logMirrorFailure(e: Exception, mirror: String) {
        runCatching {
            com.vela.simulator.util.FileLogger.e(
                "runtime", "软件源 $mirror 安装失败: ${e.message}", e,
            )
        }
    }

    /**
     * 单一镜像源的完整安装流程（v0.2.4 并行版）：
     * 下载阶段 4 路并发 + 单文件 Range 分段 + 逐包跨源换源重试；
     * 下载全部完成后按依赖顺序解包（解包是磁盘 IO 密集，保持串行避免 IO 争用）。
     */
    private suspend fun installFrom(
        mirror: String,
        index: Map<String, PkgEntry>,
        failoverMirrors: List<String>,
    ) = withContext(Dispatchers.IO) {
        val deps = resolveDeps(index, ROOT_PACKAGE)
        check(deps.isNotEmpty()) { "软件源中未找到 $ROOT_PACKAGE" }
        progress?.onStage("解析出 ${deps.size} 个软件包")

        val cache = File(context.cacheDir, "debs").apply { mkdirs() }
        val pending = deps.filter { it.filename.isNotEmpty() }
        val toFetch = pending.filter { p ->
            val dest = File(cache, p.filename.substringAfterLast('/'))
            !(dest.exists() && dest.length() == p.size)
        }
        val totalBytes = toFetch.sumOf { it.size }.coerceAtLeast(1)
        val doneBytes = java.util.concurrent.atomic.AtomicLong(0)
        val doneCount = java.util.concurrent.atomic.AtomicInteger(0)
        progress?.onFileProgress("运行时下载 0/${toFetch.size} 包", 0, totalBytes)

        val sem = kotlinx.coroutines.sync.Semaphore(DOWNLOAD_CONCURRENCY)
        coroutineScope {
            toFetch.map { p ->
                launch(Dispatchers.IO) {
                    sem.withPermit {
                        val dest = File(cache, p.filename.substringAfterLast('/'))
                        val tmp = File(cache, dest.name + ".part")
                        progress?.onLog("下载 ${p.pkg} (${p.size / 1024} KB)")
                        downloadMultiMirror(p.filename, tmp, p.size, p.pkg, mirror, failoverMirrors, doneBytes, totalBytes)
                        if (p.sha256.isNotEmpty()) {
                            val actual = sha256(tmp)
                            check(actual.equals(p.sha256, ignoreCase = true)) { "${p.pkg} SHA256 校验失败" }
                        }
                        if (!tmp.renameTo(dest)) {
                            tmp.copyTo(dest, overwrite = true)
                            tmp.delete()
                        }
                        progress?.onStage("已完成 ${doneCount.incrementAndGet()}/${toFetch.size}: ${p.pkg}")
                        progress?.onFileProgress(
                            "运行时下载 ${doneCount.get()}/${toFetch.size} 包",
                            doneBytes.get(), totalBytes,
                        )
                    }
                }
            }.joinAll()
        }

        // 解包阶段（串行）
        pending.forEachIndexed { i, p ->
            val dest = File(cache, p.filename.substringAfterLast('/'))
            progress?.onStage("解包 ${i + 1}/${pending.size}: ${p.pkg}")
            DebExtractor.extract(dest, prefix, TERMUX_PREFIX)
        }

        // 确保可执行与必要目录
        qemuBinary.setExecutable(true, false)
        tmpDir.mkdirs(); homeDir.mkdirs()
        File(prefixUsr, "lib").mkdirs()
        installMark.writeText("ok ${System.currentTimeMillis()}")
        progress?.onStage("QEMU 运行时安装完成")
    }

    /**
     * 单包下载：主源失败自动在其余源之间换源重试（指数上不退避，镜像间彼此独立），
     * 命中源内部再走 HttpDownloader 的分段/单流逻辑。
     * onBytes 增量聚合计入全局进度（doneBytes 为跨包共享计数器）。
     */
    private suspend fun downloadMultiMirror(
        filename: String,
        dest: File,
        size: Long,
        label: String,
        primary: String,
        failovers: List<String>,
        doneBytes: java.util.concurrent.atomic.AtomicLong,
        totalBytes: Long,
    ) {
        val mirrors = listOf(primary) + failovers
        var lastError: Exception? = null
        for (m in mirrors) {
            val url = "${repoBase(m)}/$filename"
            try {
                HttpDownloader.download(http, url, dest, size) { delta ->
                    val cur = doneBytes.addAndGet(delta)
                    progress?.onFileProgress("运行时下载中…", cur, totalBytes)
                }
                return
            } catch (e: Exception) {
                lastError = e
                runCatching { dest.delete() }
                progress?.onLog("$label 经 $m 下载失败: ${e.message}，换源重试")
                com.vela.simulator.util.FileLogger.w("runtime", "$label 经 $m 下载失败: ${e.message}")
            }
        }
        throw IllegalStateException("$label 在全部软件源均下载失败: ${lastError?.message}", lastError)
    }

    fun sha256(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 执行 QEMU 时的环境变量 */
    fun execEnvironment(extra: Map<String, String> = emptyMap()): Map<String, String> = buildMap {
        put("LD_LIBRARY_PATH", "${prefixUsr.absolutePath}/lib")
        put("PATH", "${prefixUsr.absolutePath}/bin:/system/bin:/vendor/bin")
        put("TMPDIR", tmpDir.absolutePath)
        put("HOME", homeDir.absolutePath)
        put("TERM", "xterm-256color")
        put("ANDROID_DATA", "/data") // termux 二进制兼容需要
        put("ANDROID_ROOT", "/system")
        putAll(extra)
    }

    /** 查询 qemu 版本（安装后调用） */
    fun qemuVersion(): String = runCatching {
        if (!isInstalled) return "未安装"
        val pb = ProcessBuilder(listOf(qemuBinary.absolutePath, "--version"))
            .redirectErrorStream(true)
        execEnvironment().forEach { (k, v) -> pb.environment()[k] = v }
        pb.start().inputStream.bufferedReader().readText().lineSequence().firstOrNull() ?: ""
    }.getOrDefault("未知")
}
