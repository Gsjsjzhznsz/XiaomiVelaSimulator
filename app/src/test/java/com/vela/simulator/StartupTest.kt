package com.vela.simulator

import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = VelaApp::class)
class StartupTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeScreenRenders() {
        rule.waitForIdle()
        // 底部导航
        rule.onNodeWithText("设备").assertExists()
        rule.onNodeWithText("工坊").assertExists()
        rule.onNodeWithText("设置").assertExists()
        // 首页标题与运行时卡片
        rule.onNodeWithText("Xiaomi VELA 模拟器").assertExists()
        rule.onNodeWithText("QEMU 运行时", substring = true).assertExists()
        // 模板加载完成（15 款内置设备）
        rule.onNodeWithText("设备模板（15）").assertExists()
        // 组合树非空
        val tree = rule.onRoot().printToString(maxDepth = 4)
        org.junit.Assert.assertTrue("组合树为空（黑屏回归）", tree.contains("QEMU"))
    }
}
