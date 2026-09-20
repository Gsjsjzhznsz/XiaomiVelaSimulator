package com.vela.simulator.quickapp

import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 快应用 (.rpk) 包解析与模拟。
 *
 * .rpk 本质是 ZIP 容器，内含：
 *  - manifest.json  : 快应用清单（package/name/icon/router.pages/features/permissions…）
 *  - app.js + 分包  : 编译后的 JS 逻辑（需要 JS 引擎才能执行，本模块不执行）
 *  - i18n/<locale>.json : 多语言字符串表
 *  - common/…       : 资源文件
 *
 * 本模块提供：清单解析、图标提取、页面路由列表、i18n 字符串表、文件清单，
 * 以及工坊页的"启动画面 + 页面路由"设备外形模拟预览。
 */
object RpkManager {

    data class PageInfo(val route: String, val component: String)

    data class FileEntry(val path: String, val size: Long)

    data class QuickAppPackage(
        val filePath: String,
        val packageId: String = "",
        val name: String = "",
        val versionName: String = "",
        val versionCode: Long = 0,
        val minPlatformVersion: String = "",
        val iconFile: String? = null,
        val entryPage: String = "",
        val pages: List<PageInfo> = emptyList(),
        val features: List<String> = emptyList(),
        val permissions: List<String> = emptyList(),
        val i18nLocales: List<String> = emptyList(),
        val files: List<FileEntry> = emptyList(),
        val totalFiles: Int = 0,
        val fileSize: Long = 0,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 从 Uri 拷贝到本地后解析 */
    fun import(ctx: Context, uri: android.net.Uri, outDirName: String = "quickapps"): Result<QuickAppPackage> = runCatching {
        val outDir = File(ctx.filesDir, outDirName).apply { mkdirs() }
        val tmp = File(outDir, "import_${System.currentTimeMillis()}.rpk")
        ctx.contentResolver.openInputStream(uri)!!.use { ins ->
            tmp.outputStream().use { ins.copyTo(it) }
        }
        check(tmp.length() > 0) { "文件为空" }
        parse(tmp).getOrElse { e ->
            tmp.delete(); throw IllegalStateException(e.message ?: "解析失败", e)
        }
    }

    /** 解析 .rpk（ZIP 容器） */
    fun parse(file: File): Result<QuickAppPackage> = runCatching {
        val outDir = File(file.parentFile, file.nameWithoutExtension + "_extracted")
        outDir.deleteRecursively(); outDir.mkdirs()

        var manifestText: String? = null
        val files = mutableListOf<FileEntry>()
        val i18nLocales = mutableListOf<String>()
        var total = 0
        var iconPathInZip: String? = null

        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name.trimStart('/')
                if (name.isEmpty() || name.endsWith("/")) { zip.closeEntry(); continue }
                total++
                if (files.size < 200) files += FileEntry(name, e.size)

                when {
                    name == "manifest.json" -> {
                        manifestText = zip.readBytes().decodeToString()
                    }
                    name.startsWith("i18n/") && name.endsWith(".json") -> {
                        i18nLocales += name.removePrefix("i18n/").removeSuffix(".json")
                        val f = File(outDir, name); f.parentFile?.mkdirs(); f.writeBytes(zip.readBytes())
                    }
                    else -> {
                        // 其它条目: 落盘以提取图标/资源
                        if (e.size < 2 shl 20) {
                            val f = File(outDir, name)
                            f.parentFile?.mkdirs()
                            f.writeBytes(zip.readBytes())
                        }
                    }
                }
                zip.closeEntry()
            }
        }

        val manifest = manifestText?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?: throw IllegalArgumentException("manifest.json 缺失或非法 —— 不是有效的快应用 (.rpk) 包")

        fun JsonObject.str(key: String): String =
            runCatching { this[key]!!.jsonPrimitive.content }.getOrDefault("")

        // 图标路径（如 /common/icon.png）
        val iconRaw = manifest.str("icon")
        if (iconRaw.isNotBlank()) {
            val f = File(outDir, iconRaw.trimStart('/'))
            if (f.isFile && BitmapFactory.decodeFile(f.absolutePath) != null) {
                iconPathInZip = f.absolutePath
            }
        }

        // 页面路由
        val router = runCatching { manifest["router"]!!.jsonObject }.getOrNull()
        val entry = router?.str("entry") ?: ""
        val pages = mutableListOf<PageInfo>()
        router?.get("pages")?.let { p ->
            runCatching {
                p.jsonObject.forEach { (route, v) ->
                    val comp = runCatching { v.jsonObject.str("component") }.getOrDefault("")
                    pages += PageInfo(route, comp)
                }
            }
        }

        val features = runCatching { manifest["features"]!!.jsonArray.map { it.jsonObject.str("name") } }
            .getOrDefault(emptyList())
        val permissions = runCatching {
            manifest["config"]!!.jsonObject["permissions"]!!.jsonArray.map { it.jsonPrimitive.content }
        }.getOrDefault(emptyList())

        QuickAppPackage(
            filePath = file.absolutePath,
            packageId = manifest.str("package"),
            name = manifest.str("name").ifBlank { file.nameWithoutExtension },
            versionName = manifest.str("versionName"),
            versionCode = manifest.str("versionCode").toLongOrNull() ?: 0,
            minPlatformVersion = manifest.str("minPlatformVersion"),
            iconFile = iconPathInZip,
            entryPage = entry,
            pages = pages,
            features = features,
            permissions = permissions,
            i18nLocales = i18nLocales.distinct(),
            files = files.toList(),
            totalFiles = total,
            fileSize = file.length(),
        )
    }

    /** 读取 i18n 字符串表 */
    fun loadI18n(pkg: QuickAppPackage, locale: String): Map<String, String> {
        val f = File(File(pkg.filePath).parentFile, File(pkg.filePath).nameWithoutExtension + "_extracted/i18n/$locale.json")
        if (!f.isFile) return emptyMap()
        return runCatching {
            val obj = json.parseToJsonElement(f.readText()).jsonObject
            obj.mapValues { it.value.jsonPrimitive.content }
        }.getOrDefault(emptyMap())
    }

    /** 已导入包列表 */
    fun listImported(ctx: Context): List<File> =
        File(ctx.filesDir, "quickapps").listFiles { f -> f.isFile && f.name.endsWith(".rpk") }?.sortedByDescending { it.lastModified() } ?: emptyList()
}
