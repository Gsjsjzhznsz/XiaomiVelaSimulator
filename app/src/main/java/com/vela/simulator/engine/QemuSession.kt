package com.vela.simulator.engine

import com.vela.simulator.device.DeviceTemplate
import com.vela.simulator.terminal.SerialConsole
import com.vela.simulator.util.FileLogger
import com.vela.simulator.vnc.RfbClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
        val plan: QemuArgsBuilder.Plan? = withContext(Dispatchers.IO) {
            runCatching { QemuArgsBuilder.build(template, imagesDir, runtime.prefixUsr) }
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
        sc.launch {
            var connected = false
            repeat(40) { // 最多 ~20s
                delay(500)
                if (console.connect(plan.serialPort)) { connected = true; return@repeat }
            }
            if (connected) {
                appendLog("[serial] 已连接串口 tcp:${plan.serialPort}")
            } else {
                appendLog("[serial] 串口连接失败")
            }
        }

        // VNC 客户端
        if (plan.vncPort > 0) {
            sc.launch {
                var ok = false
                repeat(40) {
                    delay(500)
                    val client = RfbClient()
                    if (runCatching { client.connect(plan.vncPort, template.screen.width, template.screen.height) }.getOrDefault(false)) {
                        vnc = client; ok = true; return@repeat
                    } else { runCatching { client.close() } }
                }
                if (ok) appendLog("[vnc] 已连接 127.0.0.1:${plan.vncPort}")
                else appendLog("[vnc] 未连接（若镜像无显示设备，画面为空属正常现象）")
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
        runCatching { process?.destroy() }
        process = null
        console.close()
        runCatching { vnc?.close() }
        vnc = null
    }

    fun sendLine(cmd: String) = console.sendLine(cmd)

    fun release() {
        stop()
        logPump?.cancel()
        FileLogger.endSessionLog()
    }
}
