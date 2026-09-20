package com.vela.simulator.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 镜像管理器：清单驱动（assets/image_manifest.json）的 VELA 系统镜像
 * 自动下载 + SHA256 校验 + 本地镜像导入。
 *
 * 清单由工程 CI/脚本生成，默认指向本仓库 Release 中托管、
 * 基于 openvela（小米 VELA 官方开源）源码构建的 QEMU 镜像。
 * 用户可在设置中切换下载加速前缀（如 ghproxy）。
 */
class ImageManager(private val context: Context) {

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

    /** 下载清单条目中的全部文件 */
    suspend fun download(entry: ImageEntry) = withContext(Dispatchers.IO) {
        imagesDir.mkdirs()
        for (f in entry.files) {
            val dest = kernelFile(f.out)
            progress?.onLog("下载镜像 ${f.out}")
            val req = Request.Builder().url(f.url).build()
            http.newCall(req).execute().use { resp ->
                check(resp.isSuccessful) { "镜像下载失败 HTTP ${resp.code}: ${f.url}" }
                val body = resp.body!!
                val total = body.contentLength()
                dest.outputStream().use { out ->
                    val src = body.byteStream()
                    val buf = ByteArray(128 * 1024)
                    var read = 0L
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        progress?.onFileProgress(f.out, read, total)
                    }
                }
            }
            if (f.sha256.isNotEmpty()) {
                progress?.onStage("SHA256 校验 ${f.out}")
                val actual = QemuRuntime(context).sha256(dest)
                check(actual.equals(f.sha256, ignoreCase = true)) { "${f.out} SHA256 校验失败" }
            }
        }
        progress?.onStage("镜像就绪")
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
