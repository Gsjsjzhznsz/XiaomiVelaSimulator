package com.vela.simulator.quickapp

/**
 * v2.2.5: 虚拟机内 rpk 包的「安全短 ID」。
 *
 * 根因（桌面 20+ 组对照实验 + 真机日志实锤，详见 worklog v2.2.5-fix）：
 * 固件 NuttX FAT 的 LFN 长名打开存在「绝对路径长度 ≥48 字符即绑定错误 dirent
 * （起始簇=0）→ 首读 EIO」缺陷，与目录深度/目录名/簇位无关。
 * vapp 的两条包查找路径都带固定前缀：
 *   路径#1 /data/vapps/<pkg>/app.js          = 19 + len(pkg)
 *   路径#2 /data/RESOURCE/PACKAGE/<pkg>.rpk  = 27 + len(pkg)
 * 真实包名（如 io.github.gsjsjzhznsz.bandqq，28 字符）使两条路径达到 47/55 字符，
 * 全部落入失败区 → 「无法使用 rpk 导入功能」。demo 包（13 字符，路径 ≤40）一直正常。
 *
 * 方案：磁盘层身份改用确定性短 ID（"qa" + sha256 前 10 个 hex = 12 字符），
 * 最长路径 6+17+12+4 = 39 字符，远离 48 边界（47 字符实测通过，留 8 字符余量）。
 * vapp 只读 manifest 的 router/pages，不校验 package 字段与 URL 一致，方案安全。
 * 映射是纯函数（sha256(真实包名)），重装/自愈恢复后自动一致，无需持久化表。
 */
object QuickAppIds {

    /** 内置 demo 包：13 字符、路径 ≤40 字符，一直在安全区，保持原 id 兼容内置镜像 */
    const val DEMO_PACKAGE_ID = "com.vela.demo"

    fun isBuiltin(packageId: String): Boolean = packageId == DEMO_PACKAGE_ID

    /** 真实包名 → 磁盘层安全短 ID（demo 原样返回；确定性，可随时重算） */
    fun safeId(packageId: String): String {
        if (packageId.isBlank()) return ""
        if (isBuiltin(packageId)) return packageId
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(packageId.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }.take(10)
        return "qa$hex"
    }
}
