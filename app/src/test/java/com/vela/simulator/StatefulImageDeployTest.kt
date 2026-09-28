package com.vela.simulator

import com.vela.simulator.engine.DeployDecision
import com.vela.simulator.engine.ImageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v2.2.7 回归锁：数据盘（状态镜像）绝不能因「内容 SHA 变化」被重部署。
 *
 * 真机实锤（用户日志 commit 97bbabf，09-28 22:17 会话）：v2.2.4 起的
 * ensureAssetsCurrent 对所有 asset 文件按「部署文件内容 SHA」判新旧，
 * 而工坊每装一个 rpk 都会改写 data.img → SHA 永远与出厂清单不符 →
 * 【每次启动会话都抹盘】，用户包被静默清空 → vapp 恒报 package not
 * found → 自愈恢复 → 重启又被抹 → 循环到自愈上限。
 * 日志铁证：22:17:45.364 列表 2 包（自愈刚恢复）→ startSession →
 * 22:17:45.510 列表只剩 demo（150ms 内被抹）。
 */
class StatefulImageDeployTest {

    private val MANIFEST_SHA = "b41a9ed482cce30737af26a15261ec2a6513490e83d0d1783c1dd721d4c3aafa"

    private fun decide(
        fileExists: Boolean = true,
        marker: String? = MANIFEST_SHA,
        manifestSha: String = MANIFEST_SHA,
        force: Boolean = false,
    ): DeployDecision =
        ImageManager.decideStatefulDeploy(fileExists, marker, manifestSha, force)

    @Test
    fun `installed packages change content sha but disk must be kept`() {
        // 核心回归锁：文件存在 + 部署标记与清单一致 → 一律 KEEP。
        // 注意决策入参根本不含「当前文件内容 SHA」—— 装包/卸包/自愈写盘
        // 导致的任何内容变化都不得触发重拷（v2.2.4~v2.2.6 每次启动抹盘）。
        assertEquals(DeployDecision.KEEP, decide())
    }

    @Test
    fun `missing marker with existing disk adopts current state without wipe`() {
        // 老用户升级到 v2.2.7 首启：无标记文件但盘上有用户包 —— 必须采纳现状
        //（只补写标记），绝不能当成「新资产」重部署把包抹掉。
        assertEquals(DeployDecision.ADOPT_WRITE_MARKER, decide(marker = null))
    }

    @Test
    fun `missing disk deploys fresh image`() {
        assertEquals(DeployDecision.DEPLOY, decide(fileExists = false))
    }

    @Test
    fun `genuine asset upgrade deploys exactly once`() {
        // APK 升级更换了出厂数据盘（清单 sha 变了）→ 一次性重部署
        assertEquals(DeployDecision.DEPLOY, decide(manifestSha = "new-asset-sha"))
    }

    @Test
    fun `diskdoctor force redeploy overrides keep`() {
        assertEquals(DeployDecision.DEPLOY, decide(force = true))
    }

    @Test
    fun `data img is stateful even without manifest flag`() {
        // 兼容旧清单：out=="data.img" 一律按状态盘处理，防止清单字段缺失时回退到内容 SHA 判旧
        assertTrue(ImageManager.isStatefulImage("data.img", statefulFlag = false))
        assertTrue(ImageManager.isStatefulImage("DATA.IMG", statefulFlag = false))
        assertTrue(ImageManager.isStatefulImage("data.img", statefulFlag = true))
        // 只读内核维持内容 SHA 校验语义
        assertEquals(false, ImageManager.isStatefulImage("nuttx.bin", statefulFlag = false))
    }

    @Test
    fun `manifest marks data img as stateful`() {
        // 锁定清单字段：data.img 必须显式 stateful=true（否则升级到旧清单语义会再抹盘）
        val manifest = File("src/main/assets/image_manifest.json").readText()
        val objRe = Regex("\\{[^{}]*\\}")
        val dataEntries = objRe.findAll(manifest)
            .map { it.value }
            .filter { it.contains("\"out\"\\s*:\\s*\"data.img\"".toRegex()) }
            .toList()
        assertTrue("清单中未找到 data.img 条目", dataEntries.isNotEmpty())
        for (e in dataEntries) {
            assertTrue(
                "清单 data.img 条目缺少 stateful=true（v2.2.7 回归锁）",
                e.contains("\"stateful\"\\s*:\\s*true".toRegex()),
            )
        }
    }
}
