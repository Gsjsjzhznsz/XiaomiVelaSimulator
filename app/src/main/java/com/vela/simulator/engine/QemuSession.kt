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

    val logLines = MutableStateFlow(listOf("[vela] 会话就绪，等待启动"))

    private var pendingSerial = ""

    /** 追加一条完整日志行 */
    fun appendLog(line: String) {
        logLines.value = (logLines.value + line).takeLast(800)
        FileLogger.sessionLine(line)
    }

    /** 串口原始文本：处理不完整行，合并后再展示 */
    fun appendLogRaw(text: String) {
        pendingSerial += text
        val parts = pendingSerial.split('\n', '\r')
        pendingSerial = parts.last()
        val complete = parts.dropLast(1).filter { it.isNotEmpty() }
        if (complete.isEmpty()) return
        logLines.value = (logLines.value + complete).takeLast(800)
        _consoleLines.value = (_consoleLines.value + complete).takeLast(400)
        complete.forEach { FileLogger.sessionLine(it) }
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
        logLines.value = listOf("[vela] 启动 ${template.name}")
        FileLogger.beginSessionLog(template.name)
        FileLogger.i("session", "开始启动会话 template=${template.id} machine=${template.qemu.machine}")

        // v0.2.7：启动前内核健康前置校验 —— 残缺/错误页内核（无 ELF 魔数或体积过小）
        // 不再交给 QEMU（此前会直接启动失败/黑屏，用户无法定位原因）
        val kernel = File(imagesDir, template.qemu.kernel)
        if (!kernel.exists()) {
            failWith("系统镜像未下载（${template.qemu.kernel}），请到设备详情页下载", null)
            return
        }
        if (kernel.length() < ImageManager.MIN_KERNEL_BYTES || !ImageManager.isElfFile(kernel)) {
            val mb = String.format(java.util.Locale.US, "%.1f", kernel.length() / 1024.0 / 1024.0)
            FileLogger.w("session", "内核文件异常: ${kernel.name} ${kernel.length()}B, 删除并要求重新下载")
            runCatching { kernel.delete() }
            failWith("系统镜像损坏（仅 ${mb}MB，非有效 ELF 内核），已清除，请到设备详情页重新下载", null)
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

        // QEMU stdout/stderr → 日志
        logPump = sc.launch {
            proc.inputStream.bufferedReader().forEachLine { appendLog("[qemu] $it") }
        }

        // 等待串口就绪并连接
        // v0.2.5 修复：原实现用 return@repeat 试图退出（对 inline repeat 无效），
        // 连接成功后仍会继续重连 40 次，反复拆建串口丢失启动期输出。
        sc.launch {
            var connected = false
            for (i in 1..40) { // 最多 ~20s
                delay(500)
                if (console.connect(plan.serialPort)) { connected = true; break }
            }
            if (connected) {
                appendLog("[serial] 已连接串口 tcp:${plan.serialPort}")
            } else {
                appendLog("[serial] 串口连接失败")
            }
        }

        // VNC 客户端：连接成功后立即启动帧循环（v0.2.5：原实现从未调用 frameLoop，
        // 无 FramebufferUpdateRequest 上行，QEMU 永不发送画面）
        if (plan.vncPort > 0) {
            sc.launch {
                var connected: RfbClient? = null
                for (i in 1..40) { // 最多 ~20s，等 QEMU VNC 端口就绪
                    delay(500)
                    val client = RfbClient()
                    val ok = runCatching {
                        client.connect(plan.vncPort, template.screen.width, template.screen.height)
                    }.getOrDefault(false)
                    if (ok) { connected = client; break }
                    runCatching { client.close() }
                }
                val client = connected
                if (client == null) {
                    appendLog("[vnc] 未连接（若镜像无显示设备，画面将自动以控制台兑底显示）")
                    return@launch
                }
                vnc = client
                appendLog("[vnc] 已连接 127.0.0.1:${plan.vncPort}")
                runCatching { client.frameLoop() }
                    .onFailure { appendLog("[vnc] 画面流中断: ${it.message}") }
            }
        }

        // 进程退出监听
        sc.launch {
            val code = proc.waitFor()
            _exitCode.value = code
            _state.value = if (_state.value != State.IDLE) State.EXITED else State.IDLE
            appendLog("[vela] QEMU 已退出（代码 $code）")
            FileLogger.i("session", "QEMU 退出 code=$code")
            console.close()
        }

        _state.value = State.RUNNING
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
