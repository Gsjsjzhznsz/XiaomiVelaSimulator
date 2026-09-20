package com.vela.simulator.engine

import android.content.Context
import com.vela.simulator.util.DebExtractor
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
 * 首次启动时从 Termux 官方 apt 仓库解析 qemu-system-arm 及其依赖树，
 * 下载 .deb 包并解包到应用私有目录（PREFIX），执行时注入环境变量。
 *
 * 支持 Termux 官方源与清华 TUNA 镜像源切换（国内加速）。
 */
class QemuRuntime(private val context: Context) {

    companion object {
        const val REPO_OFFICIAL = "https://packages.termux.dev/apt/termux-main"
        const val REPO_TUNA = "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main"
        const val ROOT_PACKAGE = "qemu-system-arm"
        const val RUNTIME_VERSION = "1" // 运行时目录格式版本

        /** Termux 二进制的前缀（编译期硬编码路径），用于 .deb 路径重映射 */
        const val TERMUX_PREFIX = "data/data/com.termux/files"
    }

    private val json = Json { ignoreUnknownKeys = true }

    val prefix: File get() = File(context.filesDir, "qemu-prefix")
    val prefixUsr: File get() = File(prefix, "usr")
    val qemuBinary: File get() = File(prefixUsr, "bin/qemu-system-arm")
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

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun repoBase(mirror: String): String = if (mirror == "tuna") REPO_TUNA else REPO_OFFICIAL

    /** 拉取并解析 apt Packages 索引（xz 压缩） */
    suspend fun fetchPackageIndex(mirror: String, abi64: Boolean): Map<String, PkgEntry> = withContext(Dispatchers.IO) {
        val arch = if (abi64) "aarch64" else "arm"
        val url = "${repoBase(mirror)}/dists/stable/main/binary-$arch/Packages.xz"
        progress?.onStage("获取软件源索引（$arch）")
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "获取 Packages.xz 失败: HTTP ${resp.code} ($url)" }
            val plain = org.tukaani.xz.XZInputStream(resp.body!!.byteStream())
            parsePackages(plain.bufferedReader().readText())
        }
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

    /** 完整安装流程：索引 → 依赖闭包 → 下载 → 校验 → 解包 → 授权 */
    suspend fun install(mirror: String) = withContext(Dispatchers.IO) {
        val abi64 = android.os.Build.SUPPORTED_ABIS.firstOrNull()?.contains("64") == true
        val index = fetchPackageIndex(mirror, abi64)
        val deps = resolveDeps(index, ROOT_PACKAGE)
        check(deps.isNotEmpty()) { "软件源中未找到 $ROOT_PACKAGE，请切换镜像源重试" }
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
