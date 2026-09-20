package com.vela.simulator.engine

import android.content.Context
import com.vela.simulator.util.DebExtractor
import com.vela.simulator.util.NetUa
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * - 镜像级联：官方源 → TUNA → BFSU → USTC 逐源自动重试，无需用户手动切换。
 */
class QemuRuntime(private val context: Context) {

    companion object {
        const val REPO_OFFICIAL = "https://packages.termux.dev/apt/termux-main"
        const val REPO_TUNA = "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main"
        const val REPO_BFSU = "https://mirrors.bfsu.edu.cn/termux/apt/termux-main"
        const val REPO_USTC = "https://mirrors.ustc.edu.cn/termux/apt/termux-main"

        /** 级联顺序：官方源优先（最新），国内镜像作加速/容灾 fallback */
        val MIRRORS = listOf("official", "tuna", "bfsu", "ustc")

        fun repoBase(mirror: String): String = when (mirror) {
            "tuna" -> REPO_TUNA
            "bfsu" -> REPO_BFSU
            "ustc" -> REPO_USTC
            else -> REPO_OFFICIAL
        }

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
     * 完整安装流程：镜像级联 → 索引 → 依赖闭包 → 下载 → 校验 → 解包 → 授权。
     * 任一镜像失败自动切换下一个（4G/Wi-Fi 弱网、镜像抽风时用户无感知），
     * 全部失败才抛出汇总异常。
     */
    suspend fun install(mirror: String = "auto") = withContext(Dispatchers.IO) {
        val candidates = if (mirror == "auto") MIRRORS else listOf(mirror)
        var lastError: Exception? = null
        for (m in candidates) {
            try {
                progress?.onLog("尝试软件源: $m (${repoBase(m)})")
                installFrom(m)
                return@withContext
            } catch (e: Exception) {
                lastError = e
                logMirrorFailure(e, m)
            }
        }
        throw IllegalStateException(
            "全部软件源均失败（${candidates.joinToString()}），最后错误: ${lastError?.message}",
            lastError,
        )
    }

    /** 镜像失败时记录到应用文件日志（供 vela.log 排障） */
    private fun logMirrorFailure(e: Exception, mirror: String) {
        runCatching {
            com.vela.simulator.util.FileLogger.e(
                "runtime", "软件源 $mirror 安装失败: ${e.message}", e,
            )
        }
    }

    /** 单一镜像源的完整安装流程 */
    private suspend fun installFrom(mirror: String) = withContext(Dispatchers.IO) {
        val abi64 = android.os.Build.SUPPORTED_ABIS.firstOrNull()?.contains("64") == true
        val index = fetchPackageIndex(mirror, abi64)
        val deps = resolveDeps(index, ROOT_PACKAGE)
        check(deps.isNotEmpty()) { "软件源中未找到 $ROOT_PACKAGE" }
        progress?.onStage("需下载 ${deps.size} 个软件包")

        val cache = File(context.cacheDir, "debs").apply { mkdirs() }
        val base = repoBase(mirror)
        deps.forEachIndexed { i, p ->
            if (p.filename.isEmpty()) return@forEachIndexed
            val dest = File(cache, p.filename.substringAfterLast('/'))
            if (!dest.exists() || dest.length() != p.size) {
                val url = "$base/${p.filename}"
                progress?.onLog("下载 ${p.pkg} (${p.size / 1024} KB)")
                download(url, dest, p.size, p.pkg)
                // SHA256 校验
                if (p.sha256.isNotEmpty()) {
                    val actual = sha256(dest)
                    check(actual.equals(p.sha256, ignoreCase = true)) { "${p.pkg} SHA256 校验失败" }
                }
            }
            progress?.onStage("解包 ${i + 1}/${deps.size}: ${p.pkg}")
            DebExtractor.extract(dest, prefix, TERMUX_PREFIX)
        }

        // 确保可执行与必要目录
        qemuBinary.setExecutable(true, false)
        tmpDir.mkdirs(); homeDir.mkdirs()
        File(prefixUsr, "lib").mkdirs()
        installMark.writeText("ok ${System.currentTimeMillis()}")
        progress?.onStage("QEMU 运行时安装完成")
    }

    private fun download(url: String, dest: File, total: Long, label: String) {
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "下载失败 HTTP ${resp.code}: $url" }
            val body = resp.body!!
            val len = body.contentLength().takeIf { it > 0 } ?: total
            dest.outputStream().use { out ->
                val src = body.byteStream()
                val buf = ByteArray(64 * 1024)
                var read = 0L
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    read += n
                    progress?.onFileProgress(label, read, len)
                }
            }
        }
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
