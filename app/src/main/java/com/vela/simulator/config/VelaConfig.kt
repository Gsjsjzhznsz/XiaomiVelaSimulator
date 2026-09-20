package com.vela.simulator.config

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appearanceStore by preferencesDataStore(name = "vela_appearance")

/**
 * 外观与交互设置（BandQQ / KernelSU manager 同构，DataStore Flow 驱动
 * 全局主题即时生效，无需重启）。
 *
 * @param themeMode   0跟随系统 1浅色 2深色 3-5 同名 Monet 动态取色档
 * @param keyColor    0=小米橙（品牌默认），非 0 为用户选定的 ARGB 种子色
 * @param enableBlur  顶栏/底栏模糊（miuix-blur，Android 13+ 生效，低版本自动回退实色）
 * @param enableFloatingBottomBar  MIUIx 悬浮底栏（关闭则用普通底栏）
 * @param navGlass    悬浮底栏的液态玻璃效果（悬浮底栏的二级选项）
 * @param enablePredictiveBack     预测性返回手势（Android 14+ 运行时动态开关）
 * @param pageScale   界面缩放 0.8 ~ 1.1
 * @param motionSpeed 动画速度倍率 0.5 ~ 2.0（越大越快）
 * @param motionStagger 列表级联入场的逐项间隔 0 ~ 300ms
 */
data class AppearanceConfig(
    val themeMode: Int = 0,
    val keyColor: Int = 0,
    val enableBlur: Boolean = true,
    val enableFloatingBottomBar: Boolean = true,
    val navGlass: Boolean = true,
    val enablePredictiveBack: Boolean = true,
    val pageScale: Float = 1.0f,
    val motionSpeed: Float = 1.0f,
    val motionStagger: Int = 100,
)

class VelaConfig(private val context: Context) {

    private object Keys {
        val THEME_MODE = intPreferencesKey("theme_mode")
        val KEY_COLOR = intPreferencesKey("key_color")
        val ENABLE_BLUR = booleanPreferencesKey("enable_blur")
        val ENABLE_FLOATING_BOTTOM_BAR = booleanPreferencesKey("enable_floating_bottom_bar")
        val NAV_GLASS = booleanPreferencesKey("nav_glass")
        val ENABLE_PREDICTIVE_BACK = booleanPreferencesKey("enable_predictive_back")
        val PAGE_SCALE = floatPreferencesKey("page_scale")
        val MOTION_SPEED = floatPreferencesKey("motion_speed")
        val MOTION_STAGGER = intPreferencesKey("motion_stagger")
    }

    fun observe(): Flow<AppearanceConfig> = context.appearanceStore.data.map { p ->
        AppearanceConfig(
            themeMode = p[Keys.THEME_MODE] ?: 0,
            keyColor = p[Keys.KEY_COLOR] ?: 0,
            enableBlur = p[Keys.ENABLE_BLUR] ?: true,
            enableFloatingBottomBar = p[Keys.ENABLE_FLOATING_BOTTOM_BAR] ?: true,
            navGlass = p[Keys.NAV_GLASS] ?: true,
            enablePredictiveBack = p[Keys.ENABLE_PREDICTIVE_BACK] ?: true,
            pageScale = p[Keys.PAGE_SCALE] ?: 1.0f,
            motionSpeed = p[Keys.MOTION_SPEED] ?: 1.0f,
            motionStagger = p[Keys.MOTION_STAGGER] ?: 100,
        )
    }

    fun observeThemeMode(): Flow<Int> = context.appearanceStore.data.map { it[Keys.THEME_MODE] ?: 0 }
    fun observeKeyColor(): Flow<Int> = context.appearanceStore.data.map { it[Keys.KEY_COLOR] ?: 0 }
    fun observeEnableBlur(): Flow<Boolean> = context.appearanceStore.data.map { it[Keys.ENABLE_BLUR] ?: true }
    fun observeFloatingBottomBar(): Flow<Boolean> =
        context.appearanceStore.data.map { it[Keys.ENABLE_FLOATING_BOTTOM_BAR] ?: true }
    fun observeNavGlass(): Flow<Boolean> = context.appearanceStore.data.map { it[Keys.NAV_GLASS] ?: true }
    fun observePredictiveBack(): Flow<Boolean> =
        context.appearanceStore.data.map { it[Keys.ENABLE_PREDICTIVE_BACK] ?: true }
    fun observePageScale(): Flow<Float> = context.appearanceStore.data.map { it[Keys.PAGE_SCALE] ?: 1.0f }
    fun observeMotionSpeed(): Flow<Float> = context.appearanceStore.data.map { it[Keys.MOTION_SPEED] ?: 1.0f }
    fun observeMotionStagger(): Flow<Int> = context.appearanceStore.data.map { it[Keys.MOTION_STAGGER] ?: 100 }

    suspend fun setThemeMode(mode: Int) { context.appearanceStore.edit { it[Keys.THEME_MODE] = mode } }
    suspend fun setKeyColor(color: Int) { context.appearanceStore.edit { it[Keys.KEY_COLOR] = color } }
    suspend fun setEnableBlur(v: Boolean) { context.appearanceStore.edit { it[Keys.ENABLE_BLUR] = v } }
    suspend fun setEnableFloatingBottomBar(v: Boolean) {
        context.appearanceStore.edit { it[Keys.ENABLE_FLOATING_BOTTOM_BAR] = v }
    }
    suspend fun setNavGlass(v: Boolean) { context.appearanceStore.edit { it[Keys.NAV_GLASS] = v } }
    suspend fun setEnablePredictiveBack(v: Boolean) {
        context.appearanceStore.edit { it[Keys.ENABLE_PREDICTIVE_BACK] = v }
    }
    suspend fun setPageScale(v: Float) { context.appearanceStore.edit { it[Keys.PAGE_SCALE] = v } }
    suspend fun setMotionSpeed(v: Float) { context.appearanceStore.edit { it[Keys.MOTION_SPEED] = v } }
    suspend fun setMotionStagger(v: Int) { context.appearanceStore.edit { it[Keys.MOTION_STAGGER] = v } }
}
