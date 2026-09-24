package com.vela.simulator.engine

import com.vela.simulator.device.DeviceTemplate
import com.vela.simulator.terminal.SerialConsole
import com.vela.simulator.util.FileLogger
import com.vela.simulator.vnc.RfbClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 一次 QEMU 仿真会话：进程生命周期 + 串口控制台 + VNC 客户端。
 */
class QemuSession(
    private val runtime: QemuRuntime,
    val template: DeviceTemplate,
    private val imagesDir: File,
) {
    enum class State { IDLE, BOOTING, RUNNING, EXITED, FAILED }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    private val _exitCode = MutableStateFlow<Int?>(null)
    val exitCode: StateFlow<Int?> = _exitCode

    private val _ports = MutableStateFlow(Ports(0, 0))
    val ports: StateFlow<Ports> = _ports

    /** v2.1.1: guest 扫描输出是否已就绪（desktop-resize 到达 或 ServerInit 即模板尺寸）。
     *  早连接时 QEMU 处于无 scanout 占位 surface（"Display output is not active."），
     *  UI 层据此持续显示启动进度而非把占位帧当画面展示。 */
    private val _scanoutReady = MutableStateFlow(false)
    val scanoutReady: StateFlow<Boolean> = _scanoutReady

    /** v2.1.1: 启动阶段文案（驱动“画面”页启动进度层）。空串 = 不在启动流程 */
    private val _bootPhase = MutableStateFlow("")
    val bootPhase: StateFlow<String> = _bootPhase

    data class Ports(val serial: Int, val vnc: Int)

    val console = SerialConsole()
    var vnc: RfbClient? = null
        private set

    /** 串口原始输出（不含 [vela]/[qemu] 前缀），供“控制台画面”兑底视图使用 */
    private val _consoleLines = MutableStateFlow(listOf<String>())
    val consoleLines: StateFlow<List<String>> = _consoleLines

    private var process: Process? = null
    private var scope: CoroutineScope? = null
    private var logPump: Job? = null

    /** v0.3.0: 自动启动命令是否已发出（每会话仅一次） */
    private var autoCommandSent = false

    val logLines = MutableStateFlow(listOf("[vela] 会话就绪，等待启动"))

    private var pendingSerial = ""

    /** v2.1.1: vapp 自动命令重试次数（防慢机丢命令，最多 2 次） */
    private var autoRetryCount = 0

    fun appendLog(line: String) {
        logLines.value = (logLines.value + line).takeLast(800)
        FileLogger.sessionLine(line)
    }

    /** 串口原始文本：处理不完整行，合并后再展示；同时检测 NSH 提示符触发自动命令 */
    fun appendLogRaw(text: String) {
        pendingSerial += text
        val parts = pendingSerial.split('\n', '\r')
        pendingSerial = parts.last()
        val complete = parts.dropLast(1).filter { it.isNotEmpty() }
        if (complete.isNotEmpty()) {
            logLines.value = (logLines.value + complete).takeLast(800)
            _consoleLines.value = (_consoleLines.value + complete).takeLast(400)
            complete.forEach { FileLogger.sessionLine(it) }
        }
        maybeAutoCommand()
    }

    /**
     * v0.3.0: 检测 NSH 提示符后自动执行模板命令（如 lvgldemo 启动 LVGL 界面）。
     * 提示符不携带换行，所以同时检查未决缓冲 pendingSerial；命中后延时发送，
     * 确保 shell 已就绪可读。
     * v2.1: 支持 ";" 分隔的多条命令（如 vapp 固件先 mount 数据盘再启动 vapp），
     * 逐条间隔 2.5s 发送，确保前一条在 guest 侧执行完毕。
     */
    private fun maybeAutoCommand() {
        val cmd = template.qemu.autoCommand.trim()
        if (cmd.isEmpty() || autoCommandSent) return
        val tail = pendingSerial + _consoleLines.value.takeLast(2).joinToString(" ")
        if (!tail.contains("nsh>")) return
        autoCommandSent = true
        _bootPhase.value = "已连接系统控制台，执行启动命令"
        val cmds = cmd.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        appendLog("[vela] 检测到 NSH，自动执行 ${cmds.size} 条命令")
        scope?.launch {
            cmds.forEachIndexed { i, c ->
                if (i > 0) delay(2500) else delay(1200)
                if (i == 1) _bootPhase.value = "挂载数据盘，启动 vapp 应用"
                appendLog("[vela] 自动命令: $c")
                runCatching { console.sendLine(c) }
                    .onFailure { appendLog("[vela] 自动命令发送失败: ${it.message}") }
            }
            autoCommandWatchdog(cmds)
        }
    }

    /**
     * v2.1.1: 自动命令看门狗。低端真机 TCG 引导可能远慢于桌面（nsh 提示符
     * 出现前的输出在串口门控下不会丢，但极慢设备上 mount/vapp 偶发无响应），
     * 90s 后仍无扫描输出且串口日志中无 vapp 启动痕迹时，自动补发一轮命令
     * （最多 2 次），消除“黑屏等到底”的死等。
     */
    private fun autoCommandWatchdog(cmds: List<String>) {
        scope?.launch {
            repeat(2) {
                delay(90_000)
                if (_state.value != State.RUNNING || _scanoutReady.value) return@launch
                if (autoRetryCount >= 2) return@launch
                val vappStarted = _consoleLines.value.any {
                    it.contains("nxtask_activate: vapp") || it.contains("[vapp]")
                }
                if (vappStarted) return@launch
                autoRetryCount++
                appendLog("[vela] 长时间无图形输出（可能命令未被 guest 执行），自动重试第 $autoRetryCount 次")
                _bootPhase.value = "引导较慢，自动重试启动命令（第 $autoRetryCount 次）"
                cmds.forEachIndexed { i, c ->
                    if (i > 0) delay(2500)
                    runCatching { console.sendLine(c) }
                }
            }
        }
    }

    /** 标记会话失败（不抛出，保证调用方不崩溃） */
    fun failWith(message: String, tr: Throwable? = null) {
        _state.value = State.FAILED
        appendLog("[vela] 启动失败: $message")
        FileLogger.e("session", "会话启动失败: $message", tr)
    }

    suspend fun start() {
        check(_state.value != State.RUNNING && _state.value != State.BOOTING) { "会话已在运行" }
        _state.value = State.BOOTING
        _exitCode.value = null
        autoCommandSent = false
        autoRetryCount = 0
        _scanoutReady.value = false
        _bootPhase.value = "等待系统控制台就绪"
        logLines.value = listOf("[vela] 启动 ${template.name}")
        FileLogger.beginSessionLog(template.name)
        FileLogger.i("session", "开始启动会话 template=${template.id} machine=${template.qemu.machine}")

        // v0.2.7：启动前内核健康前置校验 —— 残缺/错误页内核（无 ELF 魔数或体积过小）
        // 不再交给 QEMU（此前会直接启动失败/黑屏，用户无法定位原因）
        // v2.1：raw 引导固件（如 vapp 的 nuttx.bin）不是 ELF，只校验体积下限
        val kernel = File(imagesDir, template.qemu.kernel)
        if (!kernel.exists()) {
            failWith("系统镜像未下载（${template.qemu.kernel}），请到设备详情页下载", null)
            return
        }
        val rawBoot = template.qemu.bootMode == DeviceTemplate.QemuSpec.BOOT_RAW
        if (kernel.length() < ImageManager.MIN_KERNEL_BYTES || (!rawBoot && !ImageManager.isElfFile(kernel))) {
            val mb = String.format(java.util.Locale.US, "%.1f", kernel.length() / 1024.0 / 1024.0)
            FileLogger.w("session", "内核文件异常: ${kernel.name} ${kernel.length()}B, 删除并要求重新下载")
            runCatching { kernel.delete() }
            failWith("系统镜像损坏（仅 ${mb}MB，非有效内核），已清除，请到设备详情页重新下载", null)
            return
        }

        val plan: QemuArgsBuilder.Plan? = withContext(Dispatchers.IO) {
            runCatching {
                QemuArgsBuilder.build(template, imagesDir, runtime.prefixUsr, runtime.qemuBinary)
            }
                .onFailure { failWith(it.message ?: "构建 QEMU 参数失败", it) }
                .getOrNull()
        }
        if (plan == null) return
        _ports.value = Ports(plan.serialPort, plan.vncPort)
        appendLog("[qemu-cmd] ${plan.command.joinToString(" ")}")
        FileLogger.i("session", "qemu-cmd: ${plan.command.joinToString(" ")}")

        val pb = ProcessBuilder(plan.command)
        pb.redirectErrorStream(true)
        runtime.execEnvironment().forEach { (k, v) -> pb.environment()[k] = v }
        pb.directory(runtime.prefix)

        val sc = scope ?: CoroutineScope(Dispatchers.IO + Job())
        scope = sc

        try {
            process = pb.start()
        } catch (e: Exception) {
            _state.value = State.FAILED
            appendLog("[vela] 启动失败: ${e.message}")
            FileLogger.e("session", "QEMU 进程启动失败", e)
            if (e.message?.contains("exec", true) == true) {
                appendLog("[vela] 提示: 若 Android>=10 报 exec 权限错误，请确认 targetSdk=28 且运行时已安装")
            }
            return
        }

        val proc = process!!

        // QEMU stdout/stderr → 日志（v0.3.0: 捕获关闭时 read 中断异常，
        // 修复 stop() 与读循环竞争导致的 InterruptedIOException 伪崩溃）
        logPump = sc.launch {
            runCatching {
                proc.inputStream.bufferedReader().forEachLine { appendLog("[qemu] $it") }
            }.onFailure {
                if (it !is kotlinx.coroutines.CancellationException) {
                    appendLog("[qemu] 输出流结束: ${it.message}")
                }
            }
        }

        // v0.3.2: 状态先于所有协程置位。原实现把 RUNNING 放在全部 launch 之后，
        // VNC 协程首条 while(state==RUNNING) 可能在置位前被调度执行 → 整个
        // VNC 循环静默退出，会话日志无任何 [vnc] 行（真机 233137 症状之一）。
        _state.value = State.RUNNING

        // 进程退出监听
        sc.launch {
            val code = proc.waitFor()
            _exitCode.value = code
            _state.value = if (_state.value != State.IDLE) State.EXITED else State.IDLE
            appendLog("[vela] QEMU 已退出（代码 $code）")
            FileLogger.i("session", "QEMU 退出 code=$code")
            console.close()
        }

        // 等待串口就绪并连接
        // v0.2.5 修复：原实现用 return@repeat 试图退出（对 inline repeat 无效），
        // 连接成功后仍会继续重连 40 次，反复拆建串口丢失启动期输出。
        sc.launch {
            var connected = false
            for (i in 1..120) { // v2.1.1: 最多 ~60s（低端真机上 QEMU 进程冷启动也可能较慢）
                delay(500)
                if (_state.value != State.RUNNING) return@launch
                if (console.connect(plan.serialPort)) { connected = true; break }
            }
            if (connected) {
                appendLog("[serial] 已连接串口 tcp:${plan.serialPort}")
            } else {
                appendLog("[serial] 串口连接失败（60s 超时）")
            }
        }

        // VNC 客户端：连接成功后立即启动帧循环（v0.2.5：原实现从未调用 frameLoop，
        // 无 FramebufferUpdateRequest 上行，QEMU 永不发送画面）
        // v0.3.0: 外层重连循环。实测 QEMU 在 guest 尚无 scanout（lvgldemo 未启动）
        // 时收到 SetPixelFormat 会直接断开连接——存在时序竞争：VNC 端口先就绪、
        // 图形后初始化。未出过画面就断开 → 退避重连，直到 guest 图形就绪。
        // v0.3.2:
        //  1) 内层不再 40 次(≈20s)后放弃 —— QEMU 10/11 的 VNC 监听器绑定推迟到
        //     machine init（串口客户端连接）之后，低端真机 TCG 下该窗口可能明显
        //     超过 20s，原逻辑会在端口就绪前永久放弃且只留一条“未连接”日志；
        //     改为持续重试直至会话结束；
        //  2) 每次失败记录原因（拒绝/超时/握手失败），日志节流：首 3 次 + 每 20 次；
        //  3) 帧循环退出原因（onEnd）写入会话日志，不再静默死亡。
        if (plan.vncPort > 0) {
            sc.launch {
                var rounds = 0
                while (_state.value == State.RUNNING) {
                    rounds++
                    var connected: RfbClient? = null
                    var lastErr: String? = null
                    var n = 0
                    while (true) {
                        n++
                        delay(500)
                        if (_state.value != State.RUNNING) return@launch
                        val client = RfbClient()
                        val ok = runCatching {
                            client.connect(plan.vncPort, template.screen.width, template.screen.height)
                        }.onFailure { lastErr = it.message ?: it.javaClass.simpleName }
                            .getOrDefault(false)
                        if (ok) { connected = client; break }
                        runCatching { client.close() }
                        if (n <= 3 || n % 20 == 0) {
                            appendLog("[vnc] 连接尝试 #$n 失败: ${lastErr ?: "未知原因"}（持续重试中，等待 QEMU VNC 端口就绪）")
                        }
                    }
                    val client = connected
                    if (client == null) return@launch // 会话已结束
                    vnc = client
                    // v2.1.1: ServerInit 即为模板期望尺寸 => 连接时扫描输出已就绪
                    //（晚连接/占位与真实尺寸相同两种情形），直接判定图形就绪
                    if (client.serverInitWidth == template.screen.width &&
                        client.serverInitHeight == template.screen.height) {
                        _scanoutReady.value = true
                        _bootPhase.value = ""
                    }
                    appendLog(
                        "[vnc] 已连接 127.0.0.1:${plan.vncPort}" +
                            if (n > 1) "（第 $rounds 轮，经 $n 次尝试）" else ""
                    )
                    // v0.3.3: 初始尺寸取证 —— 早连接时 ServerInit 是 QEMU 无 scanout
                    // 的 640x480 占位 surface，guest 图形就绪后由 desktop-resize
                    // 通知真实尺寸（前提：客户端已订阅 enc=-223，本次已修复）
                    appendLog(
                        "[vnc] 服务器初始尺寸 ${client.serverInitWidth}x${client.serverInitHeight}" +
                            "（模板期望 ${template.screen.width}x${template.screen.height}，" +
                            "guest 图形就绪后自动切换）"
                    )
                    var gotFrame = false
                    runCatching {
                        client.frameLoop(
                            onFrame = { gotFrame = true },
                            onEnd = { reason ->
                                if (reason != null) appendLog("[vnc] 画面流结束: $reason")
                            },
                            onResize = { nw, nh ->
                                appendLog("[vnc] guest 扫描输出就绪，画面尺寸 → ${nw}x${nh}")
                                FileLogger.i("vnc", "desktop-resize ${nw}x${nh} (${template.id})")
                                _scanoutReady.value = true
                                _bootPhase.value = ""
                            },
                        )
                    }.onFailure { appendLog("[vnc] 画面流异常退出: ${it.message}") }
                    runCatching { client.close() }
                    if (vnc === client) vnc = null
                    // 出过画面说明图形会话已建立；断开多为 guest 退出/会话停止，不再重连
                    if (gotFrame || _state.value != State.RUNNING) return@launch
                    appendLog("[vnc] guest 图形尚未就绪（scanout 竞争），2s 后重连")
                    _bootPhase.value = "图形栈启动中，等待扫描输出…"
                    delay(2000)
                }
            }
        }
    }

    fun stop() {
        FileLogger.i("session", "停止会话")
        _state.value = State.IDLE
        val p = process
        if (p != null) {
            runCatching { p.destroy() }
            // v0.2.7：SIGTERM 未生效时后台线程升级强杀（不阻塞 UI 线程），
            // 修复此前偶发的“点了停止但 QEMU 进程仍在后台运行”
            Thread {
                runCatching {
                    if (!p.waitFor(3, TimeUnit.SECONDS)) {
                        FileLogger.w("session", "进程未响应 SIGTERM，强制结束 (pid=${p.hashCode()})")
                        p.destroyForcibly()
                        p.waitFor(2, TimeUnit.SECONDS)
                    }
                }
            }.apply { isDaemon = true; name = "qemu-kill" }.start()
        }
        process = null
        console.close()
        runCatching { vnc?.close() }
        vnc = null
    }

    fun sendLine(cmd: String) = console.sendLine(cmd)

    fun release() {
        stop()
        logPump?.cancel()
        scope?.cancel()
        FileLogger.endSessionLog()
    }
}
