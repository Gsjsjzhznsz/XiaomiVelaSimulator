package com.vela.simulator.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.vela.simulator.BuildConfig
import com.vela.simulator.R
import com.vela.simulator.ui.LocalEnableBlur
import com.vela.simulator.ui.util.BlurredBar
import com.vela.simulator.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 关于页（v0.2.4，与 BandQQ 关于页同构）：应用信息、GitHub 仓库、作者与联系方式、项目简介。
 * 仓库 Gsjsjzhznsz/XiaomiVelaSimulator（点击跳浏览器）、作者一秋（与 BandQQ 同作者）、
 * QQ群 885186458（点击复制）。项目简介按本应用定位重写。
 */
@Composable
fun AboutScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val blurBackdrop = rememberBlurBackdrop(LocalEnableBlur.current)
    val blurActive = blurBackdrop != null
    val colorScheme = MiuixTheme.colorScheme
    val repoUrl = "https://github.com/Gsjsjzhznsz/XiaomiVelaSimulator"

    var toastMsg by remember { mutableStateOf<String?>(null) }
    toastMsg?.let { msg ->
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        toastMsg = null
    }

    // BandQQ v2.8.1 教训：R.mipmap.ic_launcher 在 API 26+ 是 adaptive-icon XML，
    // Compose painterResource 遇到 AdaptiveIconDrawable 直接抛 IllegalStateException。
    // 改为 ContextCompat.getDrawable + core-ktx toBitmap。
    val launcherBitmap = remember {
        runCatching {
            ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.toBitmap()
        }.getOrNull()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            BlurredBar(blurBackdrop) {
                SmallTopAppBar(
                    title = "关于",
                    color = if (blurActive) Color.Transparent else colorScheme.surface,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = "返回",
                                tint = colorScheme.onBackground,
                            )
                        }
                    },
                )
            }
        },
        popupHost = { },
    ) { innerPadding ->
        Box(
            modifier = if (blurBackdrop != null) Modifier.layerBackdrop(blurBackdrop) else Modifier
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                Spacer(Modifier.height(innerPadding.calculateTopPadding() + 16.dp))

                // ===== 应用标识区 =====
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (launcherBitmap != null) {
                        Image(
                            bitmap = launcherBitmap.asImageBitmap(),
                            contentDescription = "VELA 模拟器图标",
                            modifier = Modifier.size(84.dp),
                        )
                    }
                    Text(
                        text = "VELA 模拟器",
                        fontSize = 22.sp,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    Text(
                        text = "v${BuildConfig.VERSION_NAME}",
                        fontSize = 14.sp,
                        color = colorScheme.onSurfaceSecondary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                // ===== 项目信息 =====
                SmallTitle(text = "项目信息")
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        AboutInfoRow("应用", "小米 VELA 可穿戴设备模拟器（QEMU 仿真）")
                        AboutInfoRow("作者", "一秋")
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "联系方式",
                                fontSize = 14.sp,
                                color = colorScheme.onSurfaceSecondary,
                                modifier = Modifier.width(76.dp),
                            )
                            Text(
                                text = "QQ群 885186458（点击复制）",
                                fontSize = 14.sp,
                                color = colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(repoUrl))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }.onFailure { toastMsg = "打开浏览器失败" }
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "仓库",
                                fontSize = 14.sp,
                                color = colorScheme.onSurfaceSecondary,
                                modifier = Modifier.width(76.dp),
                            )
                            Text(
                                text = repoUrl,
                                fontSize = 14.sp,
                                color = colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                // ===== 项目简介（按本应用定位重写）=====
                SmallTitle(text = "项目简介", modifier = Modifier.padding(top = 16.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "VELA 模拟器把小米 VELA 可穿戴系统搬上你的安卓手机：" +
                            "内置 15 款手环/手表设备模板（外观预览 + 可编辑仿真参数），" +
                            "首次使用自动从 Termux 软件源获取 QEMU 运行时（无需 root），" +
                            "加载 openvela（小米 VELA 官方开源，Apache-2.0）QEMU 镜像即可启动 " +
                            "NuttX/VELA 系统，支持触摸交互与串口控制台。\n\n" +
                            "工坊提供快应用（.rpk）与表盘（.bin）的包级解析与设备外形模拟预览：" +
                            "manifest 清单、图标、页面路由、i18n 字符串表、内嵌资源扫描，" +
                            "可用于开发期的包体检查与资源审阅。\n\n" +
                            "界面遵循 MIUIx 设计语言：液态玻璃悬浮底栏、预测性返回手势、" +
                            "Monet 动态取色、可调动画速度与级联入场。" +
                            "本应用为社区学习工具，与 Xiaomi 无隶属或背书关系；" +
                            "设备模板参数为公开资料整理的可编辑预设。",
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                        color = colorScheme.onSurface,
                        modifier = Modifier.padding(14.dp),
                    )
                }

                // ===== 操作按钮 =====
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("vela_qq", "885186458"))
                            toastMsg = "QQ 群号已复制：885186458"
                        },
                        colors = ButtonDefaults.buttonColors(),
                        modifier = Modifier.weight(1f),
                    ) { Text("复制联系方式") }
                    Button(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(repoUrl))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }.onFailure { toastMsg = "打开浏览器失败" }
                        },
                        colors = ButtonDefaults.buttonColorsPrimary(),
                        modifier = Modifier.weight(1f),
                    ) { Text("打开 GitHub 仓库") }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun AboutInfoRow(label: String, value: String) {
    val colorScheme = MiuixTheme.colorScheme
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = colorScheme.onSurfaceSecondary,
            modifier = Modifier.width(76.dp),
        )
        Text(text = value, fontSize = 14.sp, modifier = Modifier.weight(1f))
    }
}
