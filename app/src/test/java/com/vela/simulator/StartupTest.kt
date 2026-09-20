package com.vela.simulator

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 启动回归测试（黑屏 Bug 复现用例）：
 * 曾在 Compose 编译器 1.5.14 下因 `VelaApp(vm = viewModel())` 默认参数写法
 * 导致整个组合被静默跳过、界面黑屏且无任何日志。此测试保证真实启动后
 * 首页内容必须完整渲染。
 *
 * v0.2.3：导航重构为 HorizontalPager + MIUIx 悬浮底栏，断言同步更新。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// SDK 28：贴近 targetSdk=28 的真实回退路径；同时 miuix-blur 的 AGSL shader
// 在 Robolectric native runtime 不可用（sdk>=33 会触发 RuntimeShader swizzle 崩溃），
// 28 < 33 时 isRenderEffectSupported() 返回 false，液态玻璃自动回退实色。
@Config(sdk = [28], application = VelaApp::class)
class StartupTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeScreenRenders() {
        rule.waitForIdle()
        // 悬浮底栏三个页签（VelaTab.label 同时渲染于 PageScaffold 顶栏标题 → 各 2 处）
        // 悬浮底栏三个页签：当前页(设备)与预组合页(工坊)的 PageScaffold 顶栏
        // 也渲染同名标题 → 2 处；设置页(第 3 页)未预组合，仅底栏 tab → 1 处
        rule.onAllNodesWithText("设备").fetchSemanticsNodes().let { org.junit.Assert.assertEquals(2, it.size) }
        rule.onAllNodesWithText("工坊").fetchSemanticsNodes().let { org.junit.Assert.assertEquals(2, it.size) }
        rule.onAllNodesWithText("设置").fetchSemanticsNodes().let { org.junit.Assert.assertEquals(1, it.size) }
        // 首页标题与运行时卡片
        rule.onNodeWithText("Xiaomi VELA 模拟器").assertExists()
        rule.onNodeWithText("QEMU 运行时", substring = true).assertExists()
        // 模板加载完成（15 款内置设备）：refreshTemplates 在 IO 线程异步执行，
        // waitForIdle 不保证其完成，显式等待至超时
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodesWithText("设备模板（15）", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("设备模板（15）").assertExists()
        // 组合树非空
        val tree = rule.onRoot().printToString(maxDepth = 4)
        org.junit.Assert.assertTrue("组合树为空（黑屏回归）", tree.contains("QEMU"))
    }
}
