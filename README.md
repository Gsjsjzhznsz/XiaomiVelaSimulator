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

## 📦 三个官方系预编译镜像（见 [Releases](https://github.com/Gsjsjzhznsz/XiaomiVelaSimulator/releases/tag/v0.1.0)）

| 镜像 | 目标机 | CPU | 适用模板 |
|---|---|---|---|
| `openvela-qemu-armv7a-nsh.elf` | `virt` | Cortex-A7/A15 | 手表类（S3/S4/S5、Redmi Watch…） |
| `openvela-mps2-an500-nsh.elf` | `mps2-an500` | Cortex-M7 | 手环类（Band 9/10/11…） |
| `openvela-mps2-an521-nsh.elf` | `mps2-an521` | Cortex-M33 | 手环类（可自行切换） |

镜像构建自 **openvela/nuttx `dev` 分支**（小米 VELA 官方开源，Apache-2.0），配置为官方 `tools/configure.sh <board>:nsh`。
其中 `qemu-armv7a` 已在 QEMU 实测验证启动：

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

> `mps2-an500/an521` 构建时修复了 openvela dev 分支缺失 `ARM_M_SYSTICK` Kconfig 定义的链接错误（补丁见提交，仅新增 Kconfig 段落，未改动内核源码逻辑）。

## 📲 快速开始

1. 下载并安装 [APK](../../releases)（或自行构建，见下）
2. 首页点击 **QEMU 运行时** 卡片 → 自动下载运行时（约 20MB，可选 TUNA 加速）
3. 选择任一设备模板 → 点击 **自动下载** 获取对应镜像（约 0.3–5MB）
4. **启动模拟** → 切到"控制台"标签，看到 `NuttShell (NSH)` 即成功
5. 试试输入 `uname -a`、`ps`、`free`

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

### 手环模板（MPS2）为什么画面标签是空的？

MPS2 仿真的是无显示控制器的 MCU 板卡，NuttX 输出走串口控制台。画面视图用于带显示设备的镜像（可在自定义模板的附加参数中尝试 `-device virtio-gpu-device` 等）。

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
│   ├── QemuArgsBuilder 模板 → QEMU 命令行（virt/mps2、串口 TCP、VNC 端口）
│   ├── QemuSession    进程管理 + 日志泵 + 会话状态机
│   └── ImageManager   清单驱动镜像下载/校验/导入
├── terminal/          串口控制台（TCP → nsh）
├── vnc/               极简 RFB 3.8 客户端（Raw 编码）
├── ui/                Compose 界面（VELA 手表风：深色 + 小米橙）
└── util/              .deb 解包（AR/TAR/XZ/Zstd 纯 Java 实现）
```

## ⚖️ 许可与致谢

- 本项目代码：Apache-2.0（见 [LICENSE](LICENSE)）
- [openvela](https://github.com/open-vela) / [Apache NuttX](https://nuttx.apache.org)：Apache-2.0
- [Termux](https://github.com/termux/termux-packages)：QEMU 运行时二进制来源（GPL 一类开源许可，随各软件包分发）
- [QEMU](https://www.qemu.org)：GPL
