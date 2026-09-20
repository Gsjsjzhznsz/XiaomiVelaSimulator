package com.vela.simulator.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val White = Color(0xFFFFFFFF)

private val DarkColors = darkColorScheme(
    primary = VelaOrange,
    onPrimary = White,
    primaryContainer = VelaOrangeDim,
    onPrimaryContainer = VelaText,
    secondary = VelaOrangeSoft,
    onSecondary = White,
    background = VelaBg,
    onBackground = VelaText,
    surface = VelaSurface,
    onSurface = VelaText,
    surfaceVariant = VelaSurfaceHigh,
    onSurfaceVariant = VelaTextDim,
    outline = VelaOutline,
    error = VelaRed,
)

@Composable
fun VelaTheme(content: @Composable () -> Unit) {
    // 本应用为深色手表风格主题，固定使用深色方案
    MaterialTheme(
        colorScheme = DarkColors,
        typography = VelaTypography,
        content = content,
    )
}
