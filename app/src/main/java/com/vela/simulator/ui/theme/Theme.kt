package com.vela.simulator.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowInsetsControllerCompat
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** 当前应用是否处于深色主题（供液态玻璃组件在 draw 阶段读取，BandQQ 同构） */
val LocalVelaDarkTheme = staticCompositionLocalOf { true }

/** 主题模式（BandQQ / KernelSU manager 同款六档） */
enum class ThemeMode(val value: Int, val label: String) {
    SYSTEM(0, "跟随系统"),
    LIGHT(1, "浅色"),
    DARK(2, "深色"),
    MONET_SYSTEM(3, "动态取色 · 跟随系统"),
    MONET_LIGHT(4, "动态取色 · 浅色"),
    MONET_DARK(5, "动态取色 · 深色");

    val isDark: Boolean get() = value == 2 || value == 5
    val isSystem: Boolean get() = value == 0 || value == 3
    val isMonet: Boolean get() = value >= 3

    companion object {
        fun fromValue(value: Int): ThemeMode = entries.find { it.value == value } ?: SYSTEM
    }
}

/**
 * VELA 主题（MIUIx 设计 · BandQQ 同构）：
 * - miuix ThemeController 驱动六档主题模式 + 动态取色；
 * - keyColor = 0 时使用小米橙（品牌默认色），非 0 为用户自选 ARGB 种子色；
 * - 同时提供 Material3 兼容层（存量页面里的 MaterialTheme 引用跟随 miuix
 *   的深浅色与主色），混合期两套组件色调一致；
 * - pageScale 按比例缩放全局密度（KernelSU PageScale 同款 80% ~ 110%）。
 */
@Composable
fun VelaTheme(
    themeMode: Int,
    keyColor: Int = 0,
    pageScale: Float = 1.0f,
    content: @Composable () -> Unit,
) {
    val mode = ThemeMode.fromValue(themeMode)
    val darkTheme = mode.isDark || (mode.isSystem && isSystemInDarkTheme())

    val schemeMode = when (mode) {
        ThemeMode.SYSTEM -> ColorSchemeMode.System
        ThemeMode.LIGHT -> ColorSchemeMode.Light
        ThemeMode.DARK -> ColorSchemeMode.Dark
        ThemeMode.MONET_SYSTEM -> ColorSchemeMode.MonetSystem
        ThemeMode.MONET_LIGHT -> ColorSchemeMode.MonetLight
        ThemeMode.MONET_DARK -> ColorSchemeMode.MonetDark
    }

    // keyColor：0 = 小米橙品牌默认；非 0 = 用户在主题设置里选定的 ARGB 种子色。
    // Monet 档 + 用户未选色时交由 miuix 使用系统动态色板（Android 12+），
    // 12 以下系统色板缺失，回退小米橙。
    val seedColor: Color? = when {
        keyColor != 0 -> Color(keyColor)
        mode.isMonet && Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> VelaOrange
        else -> VelaOrange
    }

    val controller = remember(schemeMode, darkTheme, seedColor) {
        ThemeController(
            colorSchemeMode = schemeMode,
            keyColor = seedColor,
            isDark = darkTheme,
        )
    }

    // 界面缩放：缩放全局密度与字宽
    val base = LocalDensity.current
    val scaledDensity = remember(base, pageScale) {
        Density(base.density * pageScale, base.fontScale * pageScale)
    }

    CompositionLocalProvider(LocalDensity provides scaledDensity) {
        MiuixTheme(controller = controller) {
            val activity = LocalContext.current as? Activity
            LaunchedEffect(darkTheme) {
                val window = activity?.window ?: return@LaunchedEffect
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            // Material3 兼容层：存量页面继续可用，色调跟随 miuix
            val m3 = miuixToMaterial3(darkTheme)
            MaterialTheme(colorScheme = m3, typography = VelaTypography) {
                CompositionLocalProvider(LocalVelaDarkTheme provides darkTheme) {
                    content()
                }
            }
        }
    }
}

/** 兼容旧调用签名（无参形式）：默认跟随系统 */
@Composable
fun VelaThemeNoArgs(content: @Composable () -> Unit) {
    VelaTheme(themeMode = 0, keyColor = 0, pageScale = 1.0f, content = content)
}

/**
 * miuix 色板 → Material3 色板映射（混合期兼容层）。
 * 主色取 miuix 当前主色（Monet/自选种子色时与 miuix 保持一致），
 * 其余角色用 VELA 常量补齐（深浅色两套）。
 */
@Composable
private fun miuixToMaterial3(dark: Boolean): androidx.compose.material3.ColorScheme {
    val miuixPrimary = MiuixTheme.colorScheme.primary
    val miuixOnPrimary = MiuixTheme.colorScheme.onPrimary
    return if (dark) {
        darkColorScheme(
            primary = miuixPrimary,
            onPrimary = miuixOnPrimary,
            background = VelaBg,
            onBackground = VelaText,
            surface = VelaSurface,
            onSurface = VelaText,
            surfaceVariant = VelaSurfaceHigh,
            onSurfaceVariant = VelaTextDim,
            outline = VelaOutline,
            error = VelaRed,
        )
    } else {
        lightColorScheme(
            primary = miuixPrimary,
            onPrimary = miuixOnPrimary,
            background = Color(0xFFF7F7FA),
            onBackground = Color(0xFF1A1A1F),
            surface = Color(0xFFFFFFFF),
            onSurface = Color(0xFF1A1A1F),
            surfaceVariant = Color(0xFFEEEEF2),
            onSurfaceVariant = Color(0xFF5A5A66),
            outline = Color(0xFFD5D5DD),
            error = VelaRed,
        )
    }
}
