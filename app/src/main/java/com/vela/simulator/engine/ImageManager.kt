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
    }

    val imagesDir: File get() = File(context.filesDir, "images").apply { mkdirs() }

    @Serializable
    data class ImageFile(val url: String, val sha256: String = "", val size: Long = 0, val out: String)

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
                            copyFromAssets(f.url.removePrefix("asset://"), dest, f.out)
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

    /** v2.1: 从 APK assets 拷贝内置镜像（asset://images/xxx → images/xxx） */
    private fun copyFromAssets(assetPath: String, dest: File, label: String) {
        if (dest.isFile && dest.length() > 0) {
            progress?.onStage("内置镜像已就绪 $label")
            return
        }
        progress?.onStage("部署内置镜像 $label")
        dest.parentFile?.mkdirs()
        context.assets.open(assetPath).use { ins ->
            dest.outputStream().use { ins.copyTo(it) }
        }
        progress?.onLog("内置镜像部署完成: $label")
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

    /** 从 SAF Uri 导入本地镜像 */
    fun import(uri: android.net.Uri, outName: String): Result<File> = runCatching {
        imagesDir.mkdirs()
        val dest = kernelFile(outName)
        context.contentResolver.openInputStream(uri)?.use { ins ->
            dest.outputStream().use { ins.copyTo(it) }
        } ?: error("无法读取所选文件")
        dest
    }

    fun listLocalImages(): List<File> = imagesDir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()

    fun deleteImage(name: String): Boolean = kernelFile(name).delete()
}
