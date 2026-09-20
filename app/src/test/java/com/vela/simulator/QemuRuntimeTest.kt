package com.vela.simulator

import androidx.test.core.app.ApplicationProvider
import com.vela.simulator.engine.QemuRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * QEMU 运行时下载链路修复回归（v0.2.3，用户真机 HTTP 404/403）：
 * - 包名必须为 qemu-system-arm-headless（Termux 已移除旧 qemu-system-arm）
 * - 索引候选必须包含 Packages.gz/bz2/plain（官方源已下线 Packages.xz）
 * - Packages 文本解析与依赖闭包解析
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QemuRuntimeTest {

    private val runtime = QemuRuntime(ApplicationProvider.getApplicationContext())

    @Test
    fun rootPackageIsHeadless() {
        assertEquals("qemu-system-arm-headless", QemuRuntime.ROOT_PACKAGE)
    }

    @Test
    fun indexFormatsIncludeGzAndPlain() {
        val names = QemuRuntime.INDEX_FORMATS.map { it.first }
        assertTrue("缺少 Packages.gz 候选", names.contains("Packages.gz"))
        assertTrue("缺少纯 Packages 候选", names.contains("Packages"))
        assertTrue("缺少 Packages.bz2 候选", names.contains("Packages.bz2"))
    }

    @Test
    fun mirrorsContainCascade() {
        assertTrue(QemuRuntime.MIRRORS.first() == "official")
        assertTrue(QemuRuntime.MIRRORS.size >= 3)
        assertTrue(QemuRuntime.repoBase("tuna").contains("tuna"))
    }

    @Test
    fun parsePackagesParsesAptIndex() {
        val sample = """
            Package: qemu-system-arm-headless
            Version: 1:11.0.3
            Depends: glib, dtc, libpixman, qemu-common
            Filename: pool/main/q/qemu-system-arm-headless/qemu-system-arm-headless_1:11.0.3_aarch64.deb
            SHA256: aaa111
            Size: 3175776

            Package: qemu-common
            Version: 1:11.0.3
            Depends: glib
            Filename: pool/main/q/qemu-common/qemu-common_1:11.0.3_aarch64.deb
            SHA256: bbb222
            Size: 1048576
        """.trimIndent()
        val index = runtime.parsePackages(sample)
        assertEquals(2, index.size)
        val headless = index["qemu-system-arm-headless"]!!
        assertEquals("1:11.0.3", headless.version)
        assertEquals(3175776L, headless.size)
        assertEquals("aaa111", headless.sha256)
    }

    @Test
    fun resolveDepsWalksClosure() {
        val sample = """
            Package: qemu-system-arm-headless
            Depends: qemu-common, dtc
            Filename: a.deb
            Size: 10

            Package: qemu-common
            Depends: glib
            Filename: b.deb
            Size: 20

            Package: dtc
            Depends: libfdt
            Filename: c.deb
            Size: 5

            Package: glib
            Depends:
            Filename: d.deb
            Size: 100

            Package: libfdt
            Depends: dtc
            Filename: e.deb
            Size: 3
        """.trimIndent()
        val index = runtime.parsePackages(sample)
        val closure = runtime.resolveDeps(index, QemuRuntime.ROOT_PACKAGE)
        val names = closure.map { it.pkg }.toSet()
        assertTrue("依赖闭包应含 headless", "qemu-system-arm-headless" in names)
        assertTrue("依赖闭包应含 qemu-common", "qemu-common" in names)
        assertTrue("依赖闭包应含 glib", "glib" in names)
        assertTrue("依赖闭包应含 libfdt", "libfdt" in names)
        assertEquals(5, closure.size)
    }
}
