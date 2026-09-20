// Xiaomi VELA Simulator 根构建脚本
// v0.2.3: 升级到 BandQQ 同款栈（AGP 8.13 + Kotlin 2.4 + compose plugin），
// miuix（MIUIx 风格组件库）要求 activity 1.12+ / compileSdk 36+。
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10" apply false
}
