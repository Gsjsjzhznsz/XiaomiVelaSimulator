# Worklog — XiaomiVelaSimulator

## 项目
XiaomiVelaSimulator：Termux QEMU 跑 OpenVela/NuttX vapp 固件 + 真实 rpk 渲染。
交付形态：APK（下载运行环境资产包）→ GitHub 发布。

## 状态（2026-09-24 会话，环境重置后第 4 次全量重建 → 端到端+显示全通）

### 端到端链路（全部验证通过）
- nuttx.bin 1.75MB（qemu-armv7a, cortex-a15, -M virt）
- 启动：boot_test.sh（raw nuttx.bin @0x600000 + PC=0x6002e0；**ELF loader 静默失败，必须用 raw bin**）
- mount virtblk0 → FAT LFN（4KB 簇：mformat -c 8）
- vapp → vrpk 解包 com.vela.demo.rpk（512B 分块读/stdout 日志/路径规范化/父目录 mkdir 全部生效）
- QuickJS: ESM import → factory(4 参: global/globalThis/window/exports) → VDOM → vrender → LVGL
- 定时器 tick 持续跑（uv timer 保活 200ms）
- **显示通**：1280x800 屏幕呈现 demo 深蓝背景 + 红/绿文本（screendump 采样证实）

### 黑屏三根因（本次全部修复）
1. vrender apply_style 用栈上 lv_style_t（悬垂）→ 改 lv_obj_set_style_* 对象本地样式
2. virtio-gpu vsync 仅在 pan 时 flush（单缓冲永不 pan）→ else 分支无条件 flush resource 1
3. vapp 无保活 → uv_run 立即返回；加 5Hz uv_timer + lv_refr_now 首帧

### 工厂函数签名（关键知识）
aiot-toolkit 编译产物：`export default function(global, globalThis, window, $app_exports$)`
exports 是第 4 参；boot script 读 exports.default.{onInit,template,style}

### 全量补丁持久化（my-project/scripts/patches/）
- openrt/ 8 源文件（含 vrpk 4 修复 + file_exists open 探测 + 保活）
- wrappers/{libpng,freetype,yoga,protobuf-c,curl,glue}/ Kconfig+Make.defs+Makefile
- curl_config.h（HAVE_FCNTL_O_NONBLOCK/HAVE_LONGLONG/SIZEOF_CURL_OFF_T 8/BUILDING_LIBCURL 由 Makefile -D）
- nuttx.patch（virtio-mmio/gpu 恒等+flush+红色探针【探针待删】、usrsock.h 守卫、virtio.h kmm_memalign 分配、board Make.defs newlib -isystem）
- feature-jidl.patch（jidl→true、exchange choice 隐藏、protobuf-c 父层 -I、curl -I、feature_utils move-ctor）
- apps-zlib.patch（Z_HAVE_UNISTD_H）
- apply_all.sh（幂等应用全部）+ presets.sh（libcxx/libcxxabi 17.0.6 + openamp 固定 commit 20 补丁 + .git 标记短路 + stddef __need/uchar.h/libmetal 恒等）
- frag_apply.py（kconfiglib 三遍法，INT 收字符串！）
- vapp_fragment.txt（86 符号；新增 TLS_TASK_NELEM=1、FEATURE_SYSTEM_EXCHANGE=n）
- kconfig-tweak shim 在 /home/z/.venv/bin（重置后需重写：patch 语义 + --file 文本模式）
- arch/dummy/Kconfig 需手动创建（fork 硬编码 source，仅有 dummy_kconfig 文件）

### 已知坑（教训）
- kconfiglib set_value 对 INT/HEX 只收字符串
- git apply 在仓库子目录=路径解析到仓库根（静默 no-op）→ 用 patch(1)
- fork openamp 补丁 a/b 前缀不对称（b 侧无 b/ 前缀带仓名）→ normalize 后 patch -p1
- presets.sh 别用 head 截管道（SIGPIPE 杀脚本致 .patched 标记缺失→重克隆丢补丁）
- libpng pnglibconf.h 需 sed：PNG_ARM_NEON_OPT 0、PNG_ZLIB_VERNUM 0
- yoga 需 .cxx 影子树（含 .h 一起拷）
- printf 引用 cJSON_Delete 后的 valuestring=打印乱码（已修：先打印后 free）
- 长任务：前台 timeout ≤560s + 幂等脚本续跑（沙箱杀后台）

### 下一步
- [ ] 删红色 fbmem 探针 → 重编终版
- [ ] APK 资产包（qemu+toolchain+固件+data.img+脚本）
- [ ] GitHub 发布（repo + release 二进制）
