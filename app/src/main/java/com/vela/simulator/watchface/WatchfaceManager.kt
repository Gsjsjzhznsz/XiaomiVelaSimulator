package com.vela.simulator.watchface

import android.content.Context
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * VELA 表盘 (.bin) 文件解析与模拟。
 *
 * 小米/VELA 系表盘固件 (.bin) 为二进制打包格式（各机型版本头结构不同，
 * 非全部公开），典型内含：布局指令块 + 字体/图片资源 + 预览图 + 字符串表。
 *
 * 本模块做**通用资源级解析**（不依赖具体机型头格式）：
 *  - ZIP 容器表盘（部分渠道以 zip 分发）→ 解包后再扫描
 *  - 内嵌 PNG/JPEG 资源扫描提取（预览图、背景、指针、图标等）
 *  - ASCII/UTF-8 字符串表提取（表盘名、开发者信息等）
 *  - 头部十六进制摘要
 *
 * 提取到的整幅图（面积最大者）作为"表盘预览图"，
 * 在工坊页按设备外形显示并叠加实时走时，实现表盘上表效果模拟。
 */
object WatchfaceManager {

    data class ExtractedImage(val file: File, val width: Int, val height: Int, val offset: Long, val format: String)

    data class FileEntry(val path: String, val size: Long)

    data class WatchfacePackage(
        val filePath: String,
        val fileName: String,
        val fileSize: Long,
        val headerHex: String,
        val images: List<ExtractedImage> = emptyList(),
        val previewFile: String? = null,   // 面积最大的图（作为表盘预览）
        val previewWidth: Int = 0,
        val previewHeight: Int = 0,
        val strings: List<String> = emptyList(),
        val zipEntries: List<FileEntry>? = null, // ZIP 容器时的成员列表
    )

    private val PNG_SIG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val MAX_IMAGES = 30
    private val MAX_TOTAL_EXTRACT = 12L shl 20   // 12MB

    /** 从 Uri 导入并解析 */
    fun import(ctx: Context, uri: android.net.Uri, outDirName: String = "watchfaces"): Result<WatchfacePackage> = runCatching {
        val outDir = File(ctx.filesDir, outDirName).apply { mkdirs() }
        val displayName = runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull() ?: "import_${System.currentTimeMillis()}.bin"
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]"), "_")
        val f = File(outDir, safeName)
        ctx.contentResolver.openInputStream(uri)!!.use { ins -> f.outputStream().use { ins.copyTo(it) } }
        check(f.length() > 0) { "文件为空" }
        parse(f).getOrElse { e ->
            f.delete(); throw IllegalStateException(e.message ?: "解析失败", e)
        }
    }

    /** 解析表盘文件（bin 或 zip 容器） */
    fun parse(file: File): Result<WatchfacePackage> = runCatching {
        val workDir = File(file.parentFile, file.nameWithoutExtension + "_extracted")
        workDir.deleteRecursively(); workDir.mkdirs()

        val head = file.inputStream().use { ins ->
            val b = ByteArray(48); val n = ins.read(b); b.copyOf(if (n > 0) n else 0)
        }
        val isZip = head.size >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()

        var zipEntries: List<FileEntry>? = null
        // 扫描的宿主文件集合
        val scanTargets = mutableListOf(file)

        if (isZip) {
            val entries = mutableListOf<FileEntry>()
            ZipInputStream(file.inputStream().buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val name = e.name.trimStart('/')
                    if (name.isEmpty() || name.endsWith("/")) { zip.closeEntry(); continue }
                    entries += FileEntry(name, e.size)
                    val out = File(workDir, name.replace("..", "__"))
                    out.parentFile?.mkdirs()
                    out.writeBytes(zip.readBytes())
                    zip.closeEntry()
                }
            }
            zipEntries = entries
            scanTargets.clear()
            workDir.walkTopDown().filter { it.isFile && it.length() < 32 shl 20 }.forEach { scanTargets += it }
        }

        var extracted = 0L
        val images = mutableListOf<ExtractedImage>()
        var seq = 0
        for (target in scanTargets) {
            if (images.size >= MAX_IMAGES) break
            val data = target.readBytes()
            var i = 0
            while (i < data.size - 8 && images.size < MAX_IMAGES) {
                if (startsWith(data, i, PNG_SIG)) {
                    val (w, h) = pngSize(data, i)
                    if (w > 0 && h > 0 && w * h >= 400) { // 忽略过小碎片
                        val end = findPngEnd(data, i)
                        if (end > i) {
                            val img = data.copyOfRange(i, end)
                            if (extracted + img.size <= MAX_TOTAL_EXTRACT) {
                                val out = File(workDir, "res_${seq++}_${target.nameWithoutExtension}.png")
                                out.writeBytes(img)
                                images += ExtractedImage(out, w, h, i.toLong(), "PNG")
                                extracted += img.size
                                i = end
                            }
                        }
                    }
                } else if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD8.toByte() && data[i + 2] == 0xFF.toByte()) {
                    val end = findJpegEnd(data, i)
                    if (end > 0) {
                        val img = data.copyOfRange(i, end)
                        if (img.size > 1500 && extracted + img.size <= MAX_TOTAL_EXTRACT) {
                            val out = File(workDir, "res_${seq++}_${target.nameWithoutExtension}.jpg")
                            out.writeBytes(img)
                            val dims = jpegSize(img)
                            images += ExtractedImage(out, dims.first, dims.second, i.toLong(), "JPEG")
                            extracted += img.size
                            i = end
                        }
                    }
                }
                i++
            }
        }

        // 预览图 = 面积最大且可解码的图
        val preview = images
            .filter { BitmapFactory.decodeFile(it.file.absolutePath) != null }
            .maxByOrNull { it.width.toLong() * it.height }

        val strings = extractStrings(
            if (isZip) scanTargets.firstOrNull()?.readBytes() ?: ByteArray(0) else file.readBytes()
        )

        WatchfacePackage(
            filePath = file.absolutePath,
            fileName = file.name,
            fileSize = file.length(),
            headerHex = head.joinToString(" ") { "%02X".format(it) },
            images = images,
            previewFile = preview?.file?.absolutePath,
            previewWidth = preview?.width ?: 0,
            previewHeight = preview?.height ?: 0,
            strings = strings,
            zipEntries = zipEntries,
        )
    }

    // ---------- 内部工具 ----------

    private fun startsWith(data: ByteArray, off: Int, sig: ByteArray): Boolean {
        if (off + sig.size > data.size) return false
        for (i in sig.indices) if (data[off + i] != sig[i]) return false
        return true
    }

    /** PNG IHDR 宽高 */
    private fun pngSize(d: ByteArray, off: Int): Pair<Int, Int> {
        if (off + 24 > d.size) return 0 to 0
        val w = ((d[off + 16].toInt() and 0xFF) shl 24) or ((d[off + 17].toInt() and 0xFF) shl 16) or
                ((d[off + 18].toInt() and 0xFF) shl 8) or (d[off + 19].toInt() and 0xFF)
        val h = ((d[off + 20].toInt() and 0xFF) shl 24) or ((d[off + 21].toInt() and 0xFF) shl 16) or
                ((d[off + 22].toInt() and 0xFF) shl 8) or (d[off + 23].toInt() and 0xFF)
        return w to h
    }

    /** IEND 块结束位置（含 CRC） */
    private fun findPngEnd(d: ByteArray, off: Int): Int {
        val sig = byteArrayOf(0x49, 0x45, 0x4E, 0x44)
        for (i in off + 8 until d.size - 8) {
            if (startsWith(d, i, sig)) return i + 4 + 4
        }
        return 0
    }

    private fun findJpegEnd(d: ByteArray, off: Int): Int {
        for (i in off + 3 until d.size - 1) {
            if (d[i] == 0xFF.toByte() && d[i + 1] == 0xD9.toByte()) return i + 2
        }
        return 0
    }

    /** JPEG SOF 尺寸（粗略） */
    private fun jpegSize(d: ByteArray): Pair<Int, Int> {
        var i = 2
        while (i + 9 < d.size) {
            if (d[i] != 0xFF.toByte()) { i++; continue }
            val marker = d[i + 1].toInt() and 0xFF
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                val h = ((d[i + 5].toInt() and 0xFF) shl 8) or (d[i + 6].toInt() and 0xFF)
                val w = ((d[i + 7].toInt() and 0xFF) shl 8) or (d[i + 8].toInt() and 0xFF)
                return w to h
            }
            val len = ((d[i + 2].toInt() and 0xFF) shl 8) or (d[i + 3].toInt() and 0xFF)
            i += 2 + len
        }
        return 0 to 0
    }

    /** ASCII/UTF-8 可打印字符串提取 */
    private fun extractStrings(data: ByteArray): List<String> {
        val out = LinkedHashSet<String>()
        val buf = ByteArrayOutputStream()
        for (b in data) {
            val v = b.toInt() and 0xFF
            if (v == 0x09 || (v in 0x20..0x7E) || v >= 0xC0) {
                buf.write(v)
            } else {
                if (buf.size() >= 5) {
                    val s = buf.toString("UTF-8").trim()
                    if (s.length >= 5 && s.length <= 120 && out.size < 80) out += s
                }
                buf.reset()
            }
        }
        return out.toList()
    }

    /** 已导入列表 */
    fun listImported(ctx: Context): List<File> =
        File(ctx.filesDir, "watchfaces").listFiles { f -> f.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()
}
