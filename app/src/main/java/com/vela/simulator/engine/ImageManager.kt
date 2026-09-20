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

    fun isKernelReady(name: String): Boolean = kernelFile(name).exists() && kernelFile(name).length() > 0

    fun verifyKernel(name: String, sha256: String): Boolean {
        val f = kernelFile(name)
        if (!f.exists() || sha256.isEmpty()) return false
        return runCatching { QemuRuntime(context).sha256(f).equals(sha256, ignoreCase = true) }.getOrDefault(false)
    }

    /**
     * 下载清单条目中的全部文件（GitHub 资产自动级联反代重试 + 分段并行提速）。
     * 多文件时并行下载（并发 3），单文件内部走 HttpDownloader 分段逻辑。
     */
    suspend fun download(entry: ImageEntry) = withContext(Dispatchers.IO) {
        imagesDir.mkdirs()
        val sem = Semaphore(3)
        coroutineScope {
            entry.files.map { f ->
                launch(Dispatchers.IO) {
                    sem.withPermit {
                        val dest = kernelFile(f.out)
                        downloadWithFallback(f.url, dest, f.out, f.size)
                    }
                }
            }.joinAll()
        }
        // 校验与就绪提示在全部下载完成后统一执行
        for (f in entry.files) {
            if (f.sha256.isNotEmpty()) {
                progress?.onStage("SHA256 校验 ${f.out}")
                val dest = kernelFile(f.out)
                val actual = QemuRuntime(context).sha256(dest)
                check(actual.equals(f.sha256, ignoreCase = true)) { "${f.out} SHA256 校验失败" }
            }
        }
        progress?.onStage("镜像就绪")
    }

    /**
     * 单文件下载：直连 → 逐个反代重试，命中线路内部分段并行。
     * 判定标准：HTTP 200 且接收字节数 > 0（部分反代对失效资源返回 200 空体）。
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
                if (bytes > 0) return@withContext
                error("响应体为空")
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
            progress?.onFileProgress(label, read, -1L)
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
