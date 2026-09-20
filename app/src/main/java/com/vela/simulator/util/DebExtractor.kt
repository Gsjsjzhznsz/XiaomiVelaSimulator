package com.vela.simulator.util

import com.github.luben.zstd.ZstdInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * .deb 解包器（纯 Java 实现，无需 root / ar 命令）：
 * .deb 是一个 AR 归档，内含 debian-binary、control.tar.*、data.tar.*
 * 本类提取 data.tar.[xz|zst|gz] 并按 Termux 前缀重映射落盘。
 */
object DebExtractor {

    /**
     * 解包 deb 到 destDir。
     * @param stripPrefix 需要剥离的路径前缀（如 "data/data/com.termux/files"），剥离后
     *                    内容直接落在 destDir 下；若路径不含该前缀则尝试剥离首段 "data/"。
     */
    fun extract(deb: File, destDir: File, stripPrefix: String = "data/data/com.termux/files") {
        destDir.mkdirs()
        FileInputStream(deb).use { ar -> extractDataTar(ar, destDir, stripPrefix) }
    }

    private fun extractDataTar(arIn: InputStream, destDir: File, stripPrefix: String) {
        var found = false
        ArArchiveInputStream(arIn).use { ar ->
            while (true) {
                val entry = ar.nextArEntry ?: break
                if (entry.name.startsWith("data.tar")) {
                    found = true
                    val name = entry.name
                    when {
                        name.endsWith(".xz") -> untar(XZCompressorInputStream(ar), destDir, stripPrefix)
                        name.endsWith(".zst") -> untar(ZstdInputStream(ar), destDir, stripPrefix)
                        name.endsWith(".gz") -> untar(java.util.zip.GZIPInputStream(ar), destDir, stripPrefix)
                        else -> untar(ar, destDir, stripPrefix) // 未压缩
                    }
                    break
                }
            }
        }
        check(found) { "deb 中未找到 data.tar 条目" }
    }

    private fun untar(tarIn: InputStream, destDir: File, stripPrefix: String) {
        TarArchiveInputStream(tarIn).use { tar ->
            while (true) {
                val e = tar.nextTarEntry ?: break
                var rel = e.name.removePrefix("./")
                if (rel.isEmpty()) continue
                // 路径重映射：剥离 termux 前缀
                if (stripPrefix.isNotEmpty() && rel.startsWith(stripPrefix)) {
                    rel = rel.removePrefix(stripPrefix).removePrefix("/")
                } else if (rel.startsWith("data/")) {
                    rel = rel.removePrefix("data/").removePrefix("/")
                }
                if (rel.isEmpty()) continue
                val out = File(destDir, rel)
                // v0.2.5 安全修复：拒绝解包路径穿越（../、绝对路径逃逸出 destDir）
                val destCanon = destDir.canonicalPath + File.separator
                if (!out.canonicalPath.startsWith(destCanon)) continue
                if (e.isSymbolicLink) {
                    out.parentFile?.mkdirs()
                    runCatching {
                        if (out.exists()) out.delete()
                        java.nio.file.Files.createSymbolicLink(
                            out.toPath(),
                            java.nio.file.Path.of(e.linkName)
                        )
                    }
                    continue
                }
                if (e.isDirectory) { out.mkdirs(); continue }
                out.parentFile?.mkdirs()
                out.outputStream().use { tar.copyTo(it) }
                // 保留可执行权限位
                val mode = e.mode
                val isExec = (mode and 0b001000000) != 0 // owner x
                val isSymlink = false
                if (isExec && !isSymlink) {
                    out.setExecutable(true, false)
                }
                chmod775IfLib(out)
            }
        }
    }

    private fun chmod775IfLib(f: File) {
        if (f.isFile && f.parentFile?.name == "lib") f.setReadable(true, false)
    }
}
