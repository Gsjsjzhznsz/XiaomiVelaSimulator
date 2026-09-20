# Xiaomi VELA Simulator

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android-green.svg)](https://www.android.com)

**安卓上的小米 VELA 系统模拟器** —— 基于 [openvela](https://github.com/open-vela)（小米 VELA 官方开源）源码构建的 QEMU 镜像，在手机上直接仿真小米/红米可穿戴设备的系统运行环境。

> 本项目为社区学习研究工具，与 Xiaomi 无隶属或背书关系。

---

## ✨ 功能特性

- **🚀 全自动开箱即用**
  - 首次启动自动从 Termux 官方 apt 源下载 `qemu-system-arm` 及全部依赖（支持清华 TUNA 镜像加速），自动解包、SHA256 校验并注入运行环境
  - 设备镜像从本仓库 Release 自动下载 + SHA256 校验（清单驱动，可自定义 URL）
- **📟 双视图仿真输出**
  - **串口控制台**：实时查看 NuttShell (nsh) 启动日志，支持直接输入命令（`help`、`ps`、`free`、`uname`…）
  - **VNC 画面**：内置极简 RFB 客户端（Raw 编码），呈现帧缓冲画面；圆表自动圆形裁剪
- **🛠 黑屏修复 + 文件日志（v0.2.1）**：修复启动黑屏（组合被静默跳过）；新增 `vela.log` 文件日志、QEMU 会话日志落盘、崩溃 tombstone 与"分享日志"入口
- **👆 触摸输入（v0.2.0 新增）**
  - 画面视图支持直接触摸/拖动：手势坐标按 letterbox 映射回帧缓冲，经 RFB PointerEvent 上行
  - `virt` 机器自动挂载 `virtio-tablet-pci` 绝对指针设备（模板参数 `touchInput` 可关）
  - 内置 KeyEvent 通道与截图按钮（保存到应用外部专属目录）
- **⚡ 快应用模拟 · 模拟工坊（v0.2.0 新增）**
  - 导入 `.rpk`（快应用 ZIP 容器）：解析 manifest.json（包名/版本/最低平台/入口页面/页面路由/features/permissions）、提取图标、枚举文件清单
  - 设备外形内模拟启动画面与页面路由跳转，查看 i18n 多语言字符串表
  - 说明：JS 业务逻辑的完整执行需要快应用引擎，本模块定位为包体检查 + 外形模拟预览
- **⏱ 表盘模拟 · 模拟工坊（v0.2.0 新增）**
  - 导入 `.bin` 表盘（也支持 ZIP 容器）：通用资源级解析，扫描提取内嵌 PNG/JPEG（预览图/背景/指针/图标）、ASCII/UTF-8 字符串表、头部十六进制摘要
  - 按设备外形显示表盘预览底图，叠加**实时走时**（数字/模拟指针双风格随模板）
  - 15 款设备外形可任意切换预览
- **⌚ 15 款设备全参数模板**（全部可编辑）
  - Xiaomi Smart Band 9 / 9 Pro / 10 / 10 NFC / 10 Pro / 11 / 11 NFC
  - Xiaomi Watch S3 系列 / S4 系列 / S4 41mm / S4 15 周年纪念版 / S5 系列
  - REDMI Watch 5 / 5 eSIM / 6
  - 参数覆盖：屏幕形状（圆/方）/ 尺寸 / 分辨率 / ppi / CPU 架构 / 内存 / 存储 / NFC / eSIM / GPS / 心率 / 血氧 等
  - 手表类映射到 QEMU `virt`（Cortex-A），手环类映射到 `mps2-an500/521`（Cortex-M7/M33 MCU）
- **🛠 自定义配置与模板**
  - 模板编辑器：形状 / 分辨率预设 / 尺寸 / ppi / QEMU 机器与内存 / SMP 核心数 / 附加 QEMU 参数 / 特性开关
  - 自定义模板持久化保存，可随时以任一内置模板为蓝本复制修改
  - 支持导入本地 `.elf` / `.bin` 镜像（SAF 文件选择器）

## 📦 下载 APK

最新版本：[**Releases**](https://github.com/Gsjsjzhznsz/XiaomiVelaSimulator/releases) · v0.2.1 直链：
`XiaomiVelaSimulator-v0.2.1-debug.apk`（约 21MB，minSdk 26，Android 8.0+，修复 v0.2.0 启动黑屏）

## 📦 三个官方系预编译镜像（见 [Releases](https://github.com/Gsjsjzhznsz/XiaomiVelaSimulator/releases/tag/v0.1.0)）

| 镜像 | 目标机 | CPU | 适用模板 |
|---|---|---|---|
| `openvela-qemu-armv7a-nsh.elf` | `virt` | Cortex-A7/A15 | 手表类（S3/S4/S5、Redmi Watch…） |
| `openvela-mps2-an500-nsh.elf` | `mps2-an500` | Cortex-M7 | 手环类（Band 9/10/11…） |
| `openvela-mps2-an521-nsh.elf` | `mps2-an521` | Cortex-M33 | 手环类（可自行切换） |

镜像构建自 **openvela/nuttx `dev` 分支**（小米 VELA 官方开源，Apache-2.0），配置为官方 `tools/configure.sh <board>:nsh`。
`qemu-armv7a` 与 `mps2-an500` 均已在 QEMU 实测验证启动：

```
nx_start: Entry
uart_register: Registering /dev/console
NuttShell (NSH)
nsh> nx_start: CPU0: Beginning Idle Loop
nsh> uname -a
NuttX 0.0.0 c9ead108 Sep 20 2026 11:55:41 arm qemu-armv7a
nsh> ps
  PID GROUP PRI POLICY   TYPE    NPX STATE    EVENT     SIGMASK            STACK    USED FILLED COMMAND
    0     0   0 FIFO     Kthread   - Ready              0000000000000000 0004072 0000712  17.4%  CPU0 IDLE
    2     2 100 RR       Task      - Running            0000000000000000 0004048 0001520  37.5%  nsh_main
```

完整启动日志见 [docs/boot_log_qemu-armv7a.txt](docs/boot_log_qemu-armv7a.txt)。

> `mps2-an500/an521` 构建时修复了 openvela dev 分支缺失 `ARM_M_SYSTICK` Kconfig 定义的链接错误（补丁见提交，仅新增 Kconfig 段落，未改动内核源码逻辑）。

## 📲 快速开始

1. 下载并安装 [APK](../../releases)（或自行构建，见下）
2. 首页点击 **QEMU 运行时** 卡片 → 自动下载运行时（约 20MB，可选 TUNA 加速）
3. 选择任一设备模板 → 点击 **自动下载** 获取对应镜像（约 0.3–5MB）
4. **启动模拟** → 切到"控制台"标签，看到 `NuttShell (NSH)` 即成功
5. 试试输入 `uname -a`、`ps`、`free`
6. 切到"画面"标签：触摸/拖动可向 guest 发送指针事件（需镜像含显示与输入驱动），支持截图
7. 底部 **工坊** 标签：导入 `.rpk` 快应用 / `.bin` 表盘进行包解析与外形模拟预览

## 🔨 自行构建

```bash
git clone https://github.com/Gsjsjzhznsz/XiaomiVelaSimulator.git
cd XiaomiVelaSimulator
./gradlew assembleDebug          # 需要 JDK 17 + Android SDK (Platform 34)
adb install app/build/outputs/apk/debug/app-debug.apk
```

或直接用 Android Studio 打开工程根目录。

## ❓ FAQ

### 为什么 targetSdk 是 28？

Android 10+ 的 W^X 限制禁止 `targetSdk >= 29` 的应用执行应用数据目录中的二进制文件。本应用采用 Termux 同款方案：运行时下载 QEMU 二进制并执行，因此 `targetSdk` 保持 28（Termux 至今如此）。这不影响在 Android 14/15 上运行。

### 触摸在哪些镜像上有效？

触摸链路为：App 手势 → RFB PointerEvent → QEMU `virtio-tablet-pci` → guest 输入驱动。需要镜像同时具备**显示设备 + 输入驱动**（例如带 LVGL 的 openvela 图形配置）才有可见反馈；纯 nsh 串口镜像无显示，触摸无从谈起，属正常现象。MPS2 MCU 板卡本身无触摸外设。

### 手环模板（MPS2）为什么画面标签是空的？

MPS2 仿真的是无显示控制器的 MCU 板卡，NuttX 输出走串口控制台。画面视图用于带显示设备的镜像（可在自定义模板的附加参数中尝试 `-device virtio-gpu-device` 等）。

### 打开应用黑屏怎么办？

v0.2.0 存在一个 Compose 组合缺陷：`VelaApp(vm = viewModel())` 默认参数写法在特定调用路径下会导致整个界面组合被静默跳过（不崩溃、无异常），表现为打开即黑屏。**v0.2.1 已修复并加入 Robolectric 启动回归测试**。若你仍遇到黑屏，请到 **设置 → 诊断日志 → 分享日志** 导出 `vela.log` 反馈。

### 日志文件在哪里？

应用运行日志、QEMU 会话输出、崩溃记录（tombstone）均写入应用专属外部目录（无需存储权限）：

```
Android/data/com.vela.simulator/files/logs/
├── vela.log          # 主日志（超 2MB 自动滚动为 vela.log.old）
├── session-*.log     # 每次 QEMU 会话的完整输出
└── crash-*.txt       # 未捕获异常堆栈
```

首页会显示"上次异常退出"提示条；可在设置页分享或清空日志。

### 模板参数是官方准确的吗？

屏幕/硬件参数依据公开资料整理，**均为可编辑预设**，可在模板编辑器中随时调整；QEMU 仿真参数才是决定启动行为的实际配置。

### 如何自己构建镜像？

```bash
git clone --depth 1 -b dev https://github.com/open-vela/nuttx.git
git clone --depth 1 -b dev https://github.com/open-vela/nuttx-apps.git
cd nuttx
tools/configure.sh qemu-armv7a:nsh
make -j CROSSDEV=arm-none-eabi-
# 产物 nuttx (ELF) 即可在 App 中"导入本地镜像"使用
```

## 🏗 架构

```
app/src/main/java/com/vela/simulator/
├── device/            设备模板模型 + 仓库（assets 15 款 + 用户自定义）
├── engine/
│   ├── QemuRuntime    Termux 源自动引导：apt 索引解析/依赖闭包/下载/解包
│   ├── QemuArgsBuilder 模板 → QEMU 命令行（virt/mps2、串口 TCP、VNC 端口、virtio-tablet）
│   ├── QemuSession    进程管理 + 日志泵 + 会话状态机
│   └── ImageManager   清单驱动镜像下载/校验/导入
├── terminal/          串口控制台（TCP → nsh）
├── vnc/               极简 RFB 3.8 客户端（Raw 编码 + PointerEvent/KeyEvent 上行）
├── quickapp/          快应用 .rpk 解析（manifest/图标/路由/i18n）
├── watchface/         表盘 .bin 资源级解析（PNG/JPEG 扫描/字符串/头摘要）
├── ui/                Compose 界面（VELA 手表风：深色 + 小米橙，含模拟工坊）
└── util/              .deb 解包（AR/TAR/XZ/Zstd 纯 Java 实现）
```

## ⚖️ 许可与致谢

- 本项目代码：Apache-2.0（见 [LICENSE](LICENSE)）
- [openvela](https://github.com/open-vela) / [Apache NuttX](https://nuttx.apache.org)：Apache-2.0
- [Termux](https://github.com/termux/termux-packages)：QEMU 运行时二进制来源（GPL 一类开源许可，随各软件包分发）
- [QEMU](https://www.qemu.org)：GPL
