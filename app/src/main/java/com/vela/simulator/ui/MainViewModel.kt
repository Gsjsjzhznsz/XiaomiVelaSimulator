package com.vela.simulator.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vela.simulator.VelaApp
import com.vela.simulator.device.DeviceTemplate
import com.vela.simulator.device.TemplateRepository
import com.vela.simulator.engine.ImageManager
import com.vela.simulator.engine.QemuRuntime
import com.vela.simulator.engine.QemuSession
import com.vela.simulator.quickapp.QuickAppIds
import com.vela.simulator.quickapp.RpkInstaller
import com.vela.simulator.quickapp.RpkManager
import com.vela.simulator.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** 全局视图模型：模板、运行时安装、镜像下载、仿真会话 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx get() = getApplication<VelaApp>()

    val runtime: QemuRuntime get() = ctx.qemuRuntime
    val images: ImageManager get() = ctx.imageManager
    val templates: TemplateRepository get() = ctx.templateRepo

    // ---- 模板 ----
    private val _templateList = MutableStateFlow<List<Pair<DeviceTemplate, TemplateRepository.TemplateMeta>>>(emptyList())
    val templateList: StateFlow<List<Pair<DeviceTemplate, TemplateRepository.TemplateMeta>>> = _templateList
    fun refreshTemplates() {
        runCatching { templates.loadAll() }
            .onSuccess {
                _templateList.value = it
                FileLogger.i("templates", "模板加载完成: ${it.size} 个")
            }
            .onFailure { e ->
                FileLogger.e("templates", "模板加载失败", e)
            }
    }

    fun templateById(id: String): DeviceTemplate? =
        _templateList.value.firstOrNull { it.first.id == id }?.first

    // ---- QEMU 运行时安装 ----
    data class RuntimeUiState(
        val installed: Boolean = false,
        val busy: Boolean = false,
        val stage: String = "",
        val fileProgress: String = "",
        val version: String = "",
        val error: String? = null,
    )

    private val _runtimeState = MutableStateFlow(RuntimeUiState(installed = ctx.qemuRuntime.isInstalled))
    val runtimeState: StateFlow<RuntimeUiState> = _runtimeState

    private var installJob: Job? = null

    fun installRuntime(mirror: String) {
        if (installJob?.isActive == true) return
        _runtimeState.value = _runtimeState.value.copy(busy = true, error = null, stage = "准备下载…")
        runtime.progress = object : QemuRuntime.Progress {
            override fun onStage(stage: String) {
                _runtimeState.value = _runtimeState.value.copy(stage = stage)
            }
            override fun onFileProgress(name: String, downloaded: Long, total: Long) {
                _runtimeState.value = _runtimeState.value.copy(
                    fileProgress = if (total > 0) "$name  ${downloaded * 100 / total}%  (${fmtSize(downloaded)} / ${fmtSize(total)})" else "$name  ${fmtSize(downloaded)}"
                )
            }
            override fun onLog(line: String) { FileLogger.i("runtime", line) }
        }
        installJob = viewModelScope.launch(Dispatchers.IO) {
            FileLogger.i("runtime", "开始安装 QEMU 运行时 (mirror=$mirror)")
            runCatching { runtime.install(mirror) }
                .onSuccess {
                    FileLogger.i("runtime", "QEMU 运行时安装成功")
                    _runtimeState.value = RuntimeUiState(
                        installed = true, busy = false,
                        stage = "QEMU 运行时就绪",
                        version = runtime.qemuVersion(),
                    )
                }
                .onFailure { e ->
                    FileLogger.e("runtime", "QEMU 运行时安装失败", e)
                    _runtimeState.value = _runtimeState.value.copy(
                        busy = false, error = e.message ?: "安装失败"
                    )
                }
        }
    }

    fun refreshRuntimeStatus() {
        _runtimeState.value = RuntimeUiState(
            installed = runtime.isInstalled,
            version = runtime.qemuVersion(),
        )
    }

    // ---- 镜像 ----
    data class ImageUiState(
        val busy: Boolean = false,
        val stage: String = "",
        val fileProgress: String = "",
        val error: String? = null,
        /** 正在下载的清单条目 id：避免全局下载状态跨模板串扰（v0.2.5 修复假“已就绪”） */
        val imageId: String = "",
    )

    private val _imageState = MutableStateFlow(ImageUiState())
    val imageState: StateFlow<ImageUiState> = _imageState

    private var downloadJob: Job? = null

    fun downloadImage(entry: ImageManager.ImageEntry) {
        if (downloadJob?.isActive == true) return
        _imageState.value = ImageUiState(busy = true, stage = "开始下载…", imageId = entry.id)
        images.progress = object : QemuRuntime.Progress {
            override fun onStage(stage: String) {
                _imageState.value = _imageState.value.copy(stage = stage)
            }
            override fun onFileProgress(name: String, downloaded: Long, total: Long) {
                _imageState.value = _imageState.value.copy(
                    fileProgress = if (total > 0) {
                        val pct = downloaded * 100 / total
                        "$name  ${pct}%  (${fmtSize(downloaded)} / ${fmtSize(total)})"
                    } else "$name  ${fmtSize(downloaded)}"
                )
            }
            override fun onLog(line: String) {}
        }
        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching { images.download(entry) }
                .onSuccess {
                    FileLogger.i("image", "镜像下载完成: ${entry.id}")
                    _imageState.value = ImageUiState(stage = "镜像就绪", imageId = entry.id)
                }
                .onFailure {
                    FileLogger.e("image", "镜像下载失败: ${entry.id}", it)
                    _imageState.value = ImageUiState(error = it.message ?: "下载失败", imageId = entry.id)
                }
        }
    }

    fun importImage(uri: android.net.Uri, outName: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val r = images.import(uri, outName)
            onDone(r.isSuccess)
        }
    }

    /** 体积格式化：MB 级一位小数，KB 级整数 */
    private fun fmtSize(bytes: Long): String =
        if (bytes >= 1024L * 1024L) String.format(java.util.Locale.US, "%.1fMB", bytes / 1024.0 / 1024.0)
        else "${bytes / 1024}KB"

    // ---- 会话（v0.2.7 多实例：templateId → session，支持多台虚拟机同时运行） ----
    private val sessions = LinkedHashMap<String, QemuSession>()
    private val _sessionState = MutableStateFlow<Map<String, QemuSession>>(emptyMap())
    val sessionState: StateFlow<Map<String, QemuSession>> = _sessionState

    fun startSession(t: DeviceTemplate, launchApp: String? = null) {
        // 同一模板已在运行/启动中：直接复用现有会话（不重复启动）
        sessions[t.id]?.let { cur ->
            val st = cur.state.value
            if (st == QemuSession.State.RUNNING || st == QemuSession.State.BOOTING) return
            // 非运行态旧会话（已退出/失败）：释放后重建
            cur.release()
            sessions.remove(t.id)
            _sessionState.value = sessions.toMap()
        }
        // 多开：不再销毁其他模板的会话（v0.2.6 及之前会 session?.release() 单槽串行）
        val s = QemuSession(runtime, t, images.imagesDir)
        s.console.onText = { text -> s.appendLogRaw(text) }
        // v2.2: 工坊 rpk 指定启动包 > 用户按模板持久化选择 > 模板默认（com.vela.demo）
        s.launchApp = launchApp?.takeIf { it.isNotBlank() } ?: launchAppFor(t.id)
        sessions[t.id] = s
        _sessionState.value = sessions.toMap()
        // v2.2.4: guest 故障自愈 —— vapp 报 package not found / 解包失败 / 读取失败时，
        // 数据盘大概率已亚损坏（mtools 宽容可读、NuttX 拒绝）。自动：等会话退出 →
        // DiskDoctor 修复（重部署 + 备份恢复）→ 自动重启同一包（每会话仅一次，防循环）。
        // 用户从「重装也没用的黑屏循环」变为无感修复后直接出画。
        if (s.launchApp != null) {
            s.onGuestFailure = { kind ->
                viewModelScope.launch(Dispatchers.IO) { healAndRelaunch(s, t, kind) }
            }
        }
        // v2.2.2: 会话退出后数据盘健康自检 —— 命中 FAT 损坏签名立即自动修复
        //（重部署干净镜像 + 恢复备份包），防止用户下一次启动黑屏/安装永久失败
        viewModelScope.launch(Dispatchers.IO) {
            s.state.first { st ->
                st == QemuSession.State.EXITED || st == QemuSession.State.FAILED || st == QemuSession.State.IDLE
            }
            runCatching {
                awaitQemuExit()
                val (healthy, out) = RpkInstaller.diskHealthProbe(runtime, vmDataDisk)
                if (!healthy && RpkInstaller.isFatCorruptOutput(out)) {
                    FileLogger.w("rpk", "会话结束后数据盘 FAT 损坏，自动修复: ${out.trim().take(120)}")
                    if (repairDataDisk()) refreshVmPackages()
                }
            }.onFailure { FileLogger.w("rpk", "会话后健康自检异常（忽略）: ${it.message}") }
        }
        viewModelScope.launch(Dispatchers.IO) {
            // 任何启动异常都必须留在会话内，绝不能带崩整个应用
            runCatching {
                // v2.2.1: 启动前确保内置镜像与清单一致。data.img 在 v2.2.1 重建了
                // FAT 一致性（旧镜像 /VAPPS 目录簇未在 FAT 分配，guest 写数据盘时
                // 会破坏 FAT 表 → rpk 解包 EIO → 黑屏）。asset:// 重部署零网络，
                // 64MB 拷贝毫秒级；SHA 一致时无开销（仅一次 32~64MB 哈希，~百毫秒）。
                val otherRunning = sessions.values.any { o ->
                    o !== s && (o.state.value == QemuSession.State.RUNNING ||
                        o.state.value == QemuSession.State.BOOTING)
                }
                if (!otherRunning) {
                    awaitQemuExit()
                    // v2.2.8: 返回 DeployReport —— 状态盘被重部署（升级换资产）时
                    // 立即从 files/quickapps 备份恢复用户包（旧 Boolean 返回把迁移
                    // 静默化，是升级后包全丢的盲区）
                    val rep = images.ensureAssetsCurrent(t.imageId)
                    if (!rep.ok) {
                        s.failWith("内置镜像部署校验失败，请到设备详情页重新部署", null)
                        return@launch
                    }
                    if (rep.statefulMigrated) {
                        FileLogger.w("rpk", "数据盘资产升级迁移完成（备份=${rep.statefulBackup?.name ?: "无"})，开始恢复用户包")
                        _vmInstallState.value = _vmInstallState.value.copy(
                            busy = true, stage = "数据盘布局升级完成，正在恢复已装应用…", error = null,
                        )
                        try {
                            var n = restoreUserPackages()
                            // v2.2.10: files/quickapps 只在「导入」时写入 —— v2.2.4~2.2.7
                            // 时代的包可能只存在于数据盘。quickapps 恢复为 0 时，直接
                            // 从旧盘备份镜像提取 .rpk 重装（mtools 读镜像不经固件 FAT
                            // 驱动，跨簇布局照常可读）。真机 vela.log 2026-10-07 实锤：
                            // 迁移完成但恢复 0 个包，用户已装包全部丢失。
                            if (n == 0 && rep.statefulBackup != null && rep.statefulBackup.isFile) {
                                n += restorePackagesFromDiskBackup(rep.statefulBackup)
                            }
                            FileLogger.i("rpk", "数据盘迁移恢复完成：$n 个用户包")
                        } finally {
                            // v2.2.10 关键修复：busy 必须复位。原实现把 busy 置 true 后
                            // 永不释放，installRpkToVm/installRpkAndLaunch 的 busy 门禁
                            // 把后续所有安装操作静默吞掉 —— 用户看到的就是
                            // “导入后点安装无任何反应”（“数据盘无法导入”真机实锤）。
                            _vmInstallState.value = _vmInstallState.value.copy(
                                busy = false,
                                stage = if (_vmInstallState.value.error != null) _vmInstallState.value.stage else "",
                            )
                        }
                        refreshVmPackages()
                    }
                } else {
                    FileLogger.w("session", "其他虚拟机运行中，跳过镜像一致性校验（不重部署）")
                }
                s.start()
            }
                .onFailure {
                    FileLogger.e("session", "会话启动异常", it)
                    s.failWith(it.message ?: "未知错误", it)
                }
        }
    }

    /**
     * v2.2.4: guest 故障自动自愈 + 重启同一包。每模板 10 分钟内最多 2 次（防循环）。
     * 等待当前会话退出（vapp 故障后 QEMU 通常很快退出；限旆12s）→ 修复数据盘 →
     * 重新 startSession（新会话对象，若用户已手动重启则跳过）。
     */
    private data class HealGuard(var count: Int = 0, var windowStart: Long = 0)

    private val healGuards = HashMap<String, HealGuard>()

    private fun healAllowed(t: DeviceTemplate): Boolean {
        val g = healGuards.getOrPut(t.id) { HealGuard() }
        val now = System.currentTimeMillis()
        if (now - g.windowStart > 10 * 60_000) {
            g.windowStart = now; g.count = 0
        }
        if (g.count >= 2) return false
        g.count++
        return true
    }

    private suspend fun healAndRelaunch(failed: QemuSession, t: DeviceTemplate, kind: String) {
        if (failed.launchApp == null) return
        if (!healAllowed(t)) {
            FileLogger.e("rpk", "guest 故障($kind)且自愈次数已达上限，停止自动重启")
            _vmInstallState.value = _vmInstallState.value.copy(
                error = "自动修复未能解决启动失败，请到工坊卸载该包后重装，或到设备详情页重新部署内置镜像",
            )
            return
        }
        FileLogger.w("rpk", "guest 故障($kind)，自动自愈后重启 ${failed.launchApp}")
        _vmInstallState.value = _vmInstallState.value.copy(
            busy = true, stage = "虚拟机内包读取异常，正在自检数据盘…", error = null,
        )
        try {
            // 等当前会话退出（避免写盘竞争）
            val deadline = System.currentTimeMillis() + 12_000
            while (System.currentTimeMillis() < deadline) {
                val st = failed.state.value
                if (st == QemuSession.State.EXITED || st == QemuSession.State.FAILED || st == QemuSession.State.IDLE) break
                delay(300)
            }
            runCatching { failed.stop() }
            awaitQemuExit()
            // v2.2.8: 自愈不再盲目抹盘（22:17 真机日志实锤：package_not_found 时真盘
            // mtools 可读、包字节完好，是固件查找层问题 —— 抹盘救不了它，反而与启动
            // 链路并发竞争把恢复好的包又抹掉）。改为健康门控：
            //  · FAT 真损坏（探针失败 + 损坏签名）→ DiskDoctor（备份+重部署+恢复）
            //  · 盘健康 → 仅原样重启验证，用户数据分毫不动
            val (diskOk, probeOut) = RpkInstaller.diskHealthProbe(runtime, vmDataDisk)
            val corrupt = !diskOk && RpkInstaller.isFatCorruptOutput(probeOut)
            if (corrupt) {
                if (!repairDataDisk()) {
                    FileLogger.e("rpk", "自愈修复失败，保留手动指引")
                    return
                }
            } else {
                FileLogger.i("rpk", "guest 故障($kind)但数据盘健康（探针=${if (diskOk) "OK" else "非损坏签名错误"}），跳过抹盘自愈，仅重启验证")
            }
            refreshVmPackages()
            // 用户已手动重启了同一模板 → 不再重复启动
            val cur = sessions[t.id]
            if (cur != null && cur !== failed &&
                (cur.state.value == QemuSession.State.RUNNING || cur.state.value == QemuSession.State.BOOTING)) {
                FileLogger.i("rpk", "自愈期间用户已重启会话，跳过自动重启")
                return
            }
            _vmInstallState.value = _vmInstallState.value.copy(stage = "数据盘已修复，重新启动 ${t.name}…")
            FileLogger.i("rpk", "自愈完成，重新启动会话 ${t.id} launch=${failed.launchApp}")
            startSession(t, failed.launchApp)
        } finally {
            // v2.2.10: 自愈结束必须释放 busy 门禁。原实现所有退出路径（含修复失败/
            // 用户已重启）都不复位 busy，与迁移路径同样的静默门禁死锁。
            _vmInstallState.value = _vmInstallState.value.copy(
                busy = false,
                stage = if (_vmInstallState.value.error != null) _vmInstallState.value.stage else "",
            )
        }
    }

    /**
     * v2.2.1: 等待全部 QEMU 进程退出（写数据盘 / 重部署镜像前调用）。
     * 超时后按孤儿处理强杀。仅在会话表已无运行态会话时调用。
     */
    private suspend fun awaitQemuExit(timeoutMs: Long = 8_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runtime.findQemuPids().isEmpty()) return true
            delay(250)
        }
        runtime.killOrphanQemu()
        delay(300)
        return runtime.findQemuPids().isEmpty()
    }

    /**
     * v2.2.1: 数据盘互斥写前置 —— 停掉全部运行中会话并等待 QEMU 退出（含孤儿清理）。
     * 返回 null = 就绪；非 null = 失败原因。
     */
    private suspend fun prepareDiskExclusive(): String? {
        val running = sessions.values.filter {
            it.state.value == QemuSession.State.RUNNING || it.state.value == QemuSession.State.BOOTING
        }
        running.forEach { it.stop() }
        if (running.isNotEmpty()) {
            FileLogger.i("rpk", "写数据盘前自动停止 ${running.size} 台运行中的虚拟机")
        }
        val ok = awaitQemuExit()
        if (!ok) return "无法停止运行中的 QEMU 进程，请稍后重试"
        return null
    }

    fun stopSession(templateId: String) {
        sessions[templateId]?.stop()
    }

    /** 结束全部运行中的虚拟机 */
    fun stopAllSessions() {
        sessions.values.forEach { it.stop() }
        FileLogger.i("session", "已结束全部虚拟机（${sessions.size} 台）")
    }

    fun runningCount(): Int = sessions.values.count {
        it.state.value == QemuSession.State.RUNNING || it.state.value == QemuSession.State.BOOTING
    }

    fun currentSession(templateId: String): QemuSession? = sessions[templateId]

    // ---- 快应用 / 表盘 / 截图 ----
    data class ImportUiState<T>(
        val busy: Boolean = false,
        val pkg: T? = null,
        val error: String? = null,
    )

    private val _quickAppState = MutableStateFlow(ImportUiState<com.vela.simulator.quickapp.RpkManager.QuickAppPackage>())
    val quickAppState: StateFlow<ImportUiState<com.vela.simulator.quickapp.RpkManager.QuickAppPackage>> = _quickAppState

    private val _watchfaceState = MutableStateFlow(ImportUiState<com.vela.simulator.watchface.WatchfaceManager.WatchfacePackage>())
    val watchfaceState: StateFlow<ImportUiState<com.vela.simulator.watchface.WatchfaceManager.WatchfacePackage>> = _watchfaceState

    fun importQuickApp(uri: android.net.Uri) {
        if (_quickAppState.value.busy) return
        _quickAppState.value = ImportUiState(busy = true)
        viewModelScope.launch(Dispatchers.IO) {
            com.vela.simulator.quickapp.RpkManager.import(ctx, uri)
                .onSuccess { _quickAppState.value = ImportUiState(pkg = it) }
                .onFailure { _quickAppState.value = ImportUiState(error = it.message ?: "导入失败") }
        }
    }

    fun importWatchface(uri: android.net.Uri) {
        if (_watchfaceState.value.busy) return
        _watchfaceState.value = ImportUiState(busy = true)
        viewModelScope.launch(Dispatchers.IO) {
            com.vela.simulator.watchface.WatchfaceManager.import(ctx, uri)
                .onSuccess { _watchfaceState.value = ImportUiState(pkg = it) }
                .onFailure { _watchfaceState.value = ImportUiState(error = it.message ?: "导入失败") }
        }
    }

    /** 保存 VNC 截图到应用专属目录，返回路径 */
    fun saveScreenshot(bmp: android.graphics.Bitmap): String? = runCatching {
        val dir = ctx.getExternalFilesDir("screenshots") ?: ctx.filesDir
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val f = File(dir, "vela_shot_$ts.png")
        f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        f.absolutePath
    }.getOrNull()

    // ---- v2.2: 虚拟机数据盘 rpk 管理（工坊快应用页） ----

    /** 每模板上次启动的 vapp 包（重启/重开保留），files/launch_apps.json */
    private val launchAppsFile get() = File(ctx.filesDir, "launch_apps.json")

    private fun loadLaunchApps(): Map<String, String> = runCatching {
        if (!launchAppsFile.isFile) return@runCatching emptyMap()
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(launchAppsFile.readText()).jsonObject
        obj.mapValues { it.value.jsonPrimitive.content }
    }.getOrDefault(emptyMap())

    private val _launchApps = MutableStateFlow(loadLaunchApps())

    fun launchAppFor(templateId: String): String = _launchApps.value[templateId].orEmpty()

    fun setLaunchApp(templateId: String, appId: String) {
        _launchApps.value = _launchApps.value + (templateId to appId)
        runCatching {
            launchAppsFile.writeText(
                kotlinx.serialization.json.JsonObject(
                    _launchApps.value.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) },
                ).toString(),
            )
        }
    }

    /** 虚拟机数据盘镜像（全模板共用内置 data.img） */
    val vmDataDisk: File get() = File(images.imagesDir, "data.img")

    data class VmInstallUiState(
        val busy: Boolean = false,
        val stage: String = "",
        val error: String? = null,
        val installedPkgId: String? = null,
    )

    private val _vmInstallState = MutableStateFlow(VmInstallUiState())
    val vmInstallState: StateFlow<VmInstallUiState> = _vmInstallState

    /** v2.2.1: 用户确认后清除错误横幅 */
    fun clearVmInstallError() {
        val cur = _vmInstallState.value
        if (!cur.busy) _vmInstallState.value = cur.copy(error = null)
    }

    private val _vmPackages = MutableStateFlow<List<RpkInstaller.VmPackage>>(emptyList())
    val vmPackages: StateFlow<List<RpkInstaller.VmPackage>> = _vmPackages

    private val _vmDiskBusy = MutableStateFlow(false)
    val vmDiskBusy: StateFlow<Boolean> = _vmDiskBusy

    /** 刷新数据盘包列表（工坊页进入时/安装卸载后调用）。
     *  v2.2.2: 读取失败且输出命中 FAT 损坏特征时自动修复后重读
     *  v2.2.3: 空结果延迟重读一次 —— 真机日志（09-27 18:29:27→28）实证同一镜像
     *  先读出包、1 秒后 mcopy 成功返回空目录、30 秒后又自愈（mtools 对 FAT 目录
     *  项的瞬时解析抖动）。任何有效数据盘都预装 com.vela.demo，空列表必然异常。 */
    fun refreshVmPackages() {
        if (_vmDiskBusy.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _vmDiskBusy.value = true
            RpkInstaller.list(runtime, vmDataDisk)
                .onSuccess {
                    var result = it
                    if (it.isEmpty()) {
                        delay(400)
                        result = RpkInstaller.list(runtime, vmDataDisk).getOrNull() ?: it
                    }
                    _vmPackages.value = result
                    FileLogger.i("rpk", "数据盘包列表: ${result.joinToString { p -> p.packageId }}")
                }
                .onFailure { e ->
                    val msg = e.message ?: ""
                    FileLogger.w("rpk", "数据盘读取失败: $msg")
                    if (RpkInstaller.isFatCorruptOutput(msg) && repairDataDisk()) {
                        RpkInstaller.list(runtime, vmDataDisk).onSuccess { l -> _vmPackages.value = l }
                    }
                }
            _vmDiskBusy.value = false
        }
    }

    // ---- v2.2.2 DiskDoctor: 数据盘 FAT 损坏自动修复 ----
    // 真机实锤：guest 解包写盘中断 → FAT 表项半更新（0x0fff0013）→ 整盘报废，
    // 后续所有 list/install 永久失败，用户只能重装应用。修复 = 重部署内置干净
    // data.img（SHA 不匹配自动触发 assets 重拷，零网络）+ 从 files/quickapps
    // 备份恢复用户导入的包（install 现自带预解包，恢复即零写就绪）。

    private var diskRepairing = false

    private fun dataImageEntryId(): String =
        images.loadManifest().images.firstOrNull { e -> e.files.any { it.out == "data.img" } }?.id
            ?: "vela-vapp-demo"

    /**
     * 自动修复数据盘。返回 true = 已重建并恢复包（调用方可重试原操作）。
     * excludePackageId: 恢复时排除的包（卸载场景下不再回来）。
     */
    private suspend fun repairDataDisk(excludePackageId: String? = null): Boolean {
        if (diskRepairing) return false
        diskRepairing = true
        _vmDiskBusy.value = true
        try {
            _vmInstallState.value = _vmInstallState.value.copy(
                busy = true, stage = "检测到数据盘损坏，正在自动修复…", error = null,
            )
            FileLogger.w("rpk", "DiskDoctor: 数据盘 FAT 损坏，开始自动修复")
            prepareDiskExclusive()?.let {
                _vmInstallState.value = VmInstallUiState(error = "数据盘已损坏且无法停止运行中的虚拟机：$it")
                return false
            }
            // v2.2.7: DiskDoctor 修复必须强制重部署干净镜像（ensureAssetsCurrent
            // 默认按「部署标记」判定，用户数据盘原样保留 —— 只有显式 force 才重拷；
            // v2.2.8: 重部署前自动快照备份旧盘，重拷后从 files/quickapps 备份恢复用户包）
            val rep = images.ensureAssetsCurrent(dataImageEntryId(), forceRedeploy = true)
            if (!rep.ok || !vmDataDisk.isFile) {
                _vmInstallState.value = VmInstallUiState(
                    error = "数据盘修复失败：内置镜像重部署异常，请到设备详情页手动「重新部署」",
                )
                return false
            }
            if (rep.statefulBackup != null) {
                FileLogger.i("rpk", "DiskDoctor: 旧盘已备份为 ${rep.statefulBackup.name}（可手动回滚/取证）")
            }
            val restored = restoreUserPackages(excludePackageId)
            FileLogger.i("rpk", "DiskDoctor: 修复完成，恢复 $restored 个用户包")
            _vmInstallState.value = VmInstallUiState(
                stage = if (restored == 0) "数据盘已自动修复" else "数据盘已自动修复（恢复 $restored 个包）",
            )
            return true
        } finally {
            diskRepairing = false
            _vmDiskBusy.value = false
        }
    }

    /**
     * v2.2.10: 从旧数据盘备份镜像直接提取用户包重装（迁移恢复的第二条腿）。
     * files/quickapps 只在「导入」时写入 —— v2.2.4~2.2.7 时代的包可能只存在于
     * 数据盘；仅靠 quickapps 恢复会出现“迁移完成，恢复 0 个包”（真机实锤）。
     * mtools 读镜像不经固件 FAT 驱动，spc=8 旧盘照常可读。
     * 返回恢复成功数。调用方需保证无 QEMU 持盘。
     */
    private suspend fun restorePackagesFromDiskBackup(bak: java.io.File): Int {
        val pkgs = runCatching { RpkInstaller.list(runtime, bak) }
            .onFailure { FileLogger.e("rpk", "备份盘读取失败: ${bak.name}", it) }
            .getOrNull() ?: return 0
        var ok = 0
        for (p in pkgs) {
            // 出厂包无需恢复（新盘自带）；空包名无法安装
            if (p.packageId.isBlank() || p.packageId == "com.vela.demo") continue
            runCatching {
                val f = RpkInstaller.exportRpk(runtime, bak, p.fileName)
                    ?: error("备份盘提取 ${p.fileName} 失败")
                RpkInstaller.install(runtime, vmDataDisk, f).getOrThrow()
            }
                .onSuccess { ok++ }
                .onFailure { FileLogger.e("rpk", "备份盘恢复 ${p.packageId} 失败", it) }
        }
        return ok
    }

    /**
     * v2.2.8: 从 files/quickapps 导入备份恢复用户包到数据盘（幂等）。
     * 供 DiskDoctor 修复与「数据盘迁移重部署」两条链路共用 —— 旧实现只有
     * DiskDoctor 会恢复，升级换资产的重部署把用户包静默清空（丢盘主诉之一）。
     * 返回恢复成功数。调用方需保证无 QEMU 持盘（先 prepareDiskExclusive/未启动）。
     */
    private suspend fun restoreUserPackages(excludePackageId: String? = null): Int {
        // 同包多份历史导入取最新（listImported 已按时间降序）
        val backups = RpkManager.listImported(ctx)
            .mapNotNull { f -> RpkManager.parse(f).getOrNull()?.let { it.packageId to f } }
            .groupBy({ it.first }) { it.second }
            .map { (id, files) -> id to files.first() }
            .filter { it.first.isNotBlank() && it.first != excludePackageId }
        var okCount = 0
        for ((id, f) in backups) {
            runCatching { RpkInstaller.install(runtime, vmDataDisk, f) }
                .onSuccess { okCount++ }
                .onFailure { FileLogger.e("rpk", "恢复 $id 失败", it) }
        }
        return okCount
    }

    /** 安装已导入的 rpk 到数据盘；完成后自动刷新列表。写盘前自动停止运行中的虚拟机 */
    fun installRpkToVm(pkg: RpkManager.QuickAppPackage) {
        if (_vmInstallState.value.busy) {
            // v2.2.10: busy 门禁拒绝时必须留痕（此前静默 return，用户点击无反馈且无日志可查）
            FileLogger.w("rpk", "安装请求被忽略：另一数据盘操作进行中（stage=${_vmInstallState.value.stage}）")
            return
        }
        _vmInstallState.value = VmInstallUiState(busy = true, stage = "准备…")
        viewModelScope.launch(Dispatchers.IO) {
            prepareDiskExclusive()?.let {
                _vmInstallState.value = VmInstallUiState(error = it)
                return@launch
            }
            val progress = object : QemuRuntime.Progress {
                override fun onStage(stage: String) {
                    _vmInstallState.value = _vmInstallState.value.copy(stage = stage)
                }
                override fun onFileProgress(name: String, downloaded: Long, total: Long) {}
                override fun onLog(line: String) { FileLogger.i("rpk", line) }
            }
            RpkInstaller.install(runtime, vmDataDisk, File(pkg.filePath), progress)
                .onSuccess {
                    FileLogger.i("rpk", "rpk 安装完成: $it")
                    _vmInstallState.value = VmInstallUiState(installedPkgId = it, stage = "已安装到数据盘")
                    refreshVmPackages()
                }
                .onFailure { e ->
                    val msg = e.message ?: ""
                    FileLogger.e("rpk", "rpk 安装失败", e)
                    // v2.2.2: FAT 损坏 → 自动修复 + 重试一次
                    if (RpkInstaller.isFatCorruptOutput(msg) && repairDataDisk()) {
                        RpkInstaller.install(runtime, vmDataDisk, File(pkg.filePath), progress)
                            .onSuccess {
                                _vmInstallState.value = VmInstallUiState(installedPkgId = it, stage = "数据盘修复后安装成功")
                                refreshVmPackages()
                            }
                            .onFailure { e2 ->
                                FileLogger.e("rpk", "修复后重试仍失败", e2)
                                _vmInstallState.value = VmInstallUiState(error = e2.message ?: "安装失败")
                            }
                    } else {
                        _vmInstallState.value = VmInstallUiState(error = msg.ifBlank { "安装失败" })
                    }
                }
        }
    }

    /** v2.2.5: 参数 = safeId（磁盘层短 ID，来自 VmPackage.safeId） */
    fun removeRpkFromVm(safeId: String) {
        if (_vmDiskBusy.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _vmDiskBusy.value = true
            prepareDiskExclusive()?.let {
                _vmInstallState.value = VmInstallUiState(error = it)
                _vmDiskBusy.value = false
                refreshVmPackages()
                return@launch
            }
            RpkInstaller.remove(runtime, vmDataDisk, safeId)
                .onFailure { e ->
                    val msg = e.message ?: ""
                    FileLogger.e("rpk", "rpk 卸载失败: $safeId", e)
                    // v2.2.2: 盘已损坏时修复后无需重删 —— 恢复时排除该包即等效卸载。
                    // 注意：恢复排除按真实包名过滤，safeId → 真实包名由备份文件反查
                    if (RpkInstaller.isFatCorruptOutput(msg)) repairDataDisk(excludePackageId = realIdOf(safeId))
                }
            _vmDiskBusy.value = false
            refreshVmPackages()
        }
    }

    /** safeId → 真实包名（DiskDoctor 恢复排除用）：优先从数据盘列表反查，
     *  列表不可用时按备份文件 manifest 反查 */
    private fun realIdOf(safeId: String): String {
        _vmPackages.value.firstOrNull { it.safeId == safeId }?.let { return it.packageId }
        return RpkManager.listImported(ctx)
            .mapNotNull { f -> RpkManager.parse(f).getOrNull()?.let { it.packageId to f } }
            .firstOrNull { QuickAppIds.safeId(it.first) == safeId }?.first ?: safeId
    }

    /**
     * v2.2 工坊主入口：安装 rpk 到数据盘 → 指定模板启动该包 → 回调导航到运行页。
     * v2.2.4: 不再信任 vmPackages 缓存列表跳过安装 —— 亚损坏条目 mtools 可读但
     * NuttX 不可见，跳过安装 = 直接黑屏。每次都干净重装（mdel+mcopy+预解包+LFN），
     * 1~2 秒内完成，同时天然修复旧损伤。
     * v2.2.5: install 返回 safeId（磁盘层短 ID），启动/记录都用它 —— 真实包名
     * 会使 vapp 查找路径超过 48 字符命中固件 LFN 缺陷（详见 QuickAppIds）。
     */
    fun installRpkAndLaunch(t: DeviceTemplate, pkg: RpkManager.QuickAppPackage, onLaunched: () -> Unit) {
        if (_vmInstallState.value.busy) {
            // v2.2.10: 同 installRpkToVm —— 门禁拒绝留痕，不再静默吞掉
            FileLogger.w("rpk", "安装启动请求被忽略：另一数据盘操作进行中（stage=${_vmInstallState.value.stage}）")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _vmInstallState.value = VmInstallUiState(busy = true, stage = "准备…")
            installFileAndLaunch(t, File(pkg.filePath), onLaunched)
        }
    }

    /** v2.2.5: 数据盘包列表的「启动」 —— 新格式（safeId 命名）直接启动（零写）；
     *  旧格式（v2.2.4 及更早的真实包名文件）先导出迁移重装再启动，
     *  否则 vapp 按真实包名查找会命中固件 LFN 路径长度缺陷。 */
    fun launchVmPackage(t: DeviceTemplate, p: RpkInstaller.VmPackage, onLaunched: () -> Unit) {
        if (p.fileName == p.safeId + ".rpk") {
            setLaunchApp(t.id, p.safeId)
            startSession(t, p.safeId)
            onLaunched()
            return
        }
        if (_vmInstallState.value.busy) return
        viewModelScope.launch(Dispatchers.IO) {
            _vmInstallState.value = VmInstallUiState(busy = true, stage = "迁移旧格式包 ${p.packageId}…")
            prepareDiskExclusive()?.let {
                _vmInstallState.value = VmInstallUiState(error = it)
                return@launch
            }
            val legacy = RpkInstaller.exportRpk(runtime, vmDataDisk, p.fileName)
            if (legacy == null) {
                _vmInstallState.value = VmInstallUiState(error = "旧格式包读取失败，请到工坊重新导入安装")
                return@launch
            }
            installFileAndLaunch(t, legacy, onLaunched)
        }
    }

    /** 安装 + 启动公共流（须在 IO 协程、prepareDiskExclusive 之后调用）。
     *  含 FAT 损坏自愈重试一次（v2.2.2 语义）。成功后 setLaunchApp + startSession。 */
    private suspend fun installFileAndLaunch(
        t: DeviceTemplate,
        rpkFile: File,
        onLaunched: () -> Unit,
    ) {
        val progress = object : QemuRuntime.Progress {
            override fun onStage(stage: String) {
                _vmInstallState.value = _vmInstallState.value.copy(busy = true, stage = stage, error = null)
            }
            override fun onFileProgress(name: String, downloaded: Long, total: Long) {}
            override fun onLog(line: String) { FileLogger.i("rpk", line) }
        }
        val r0 = RpkInstaller.install(runtime, vmDataDisk, rpkFile, progress)
        val r = if (r0.isFailure && RpkInstaller.isFatCorruptOutput(r0.exceptionOrNull()?.message ?: "")) {
            // FAT 损坏 → 自动修复 + 重试一次
            if (repairDataDisk()) RpkInstaller.install(runtime, vmDataDisk, rpkFile, progress) else r0
        } else r0
        val safeId = r.getOrNull()
        if (r.isFailure || safeId.isNullOrBlank()) {
            _vmInstallState.value = VmInstallUiState(error = r.exceptionOrNull()?.message ?: "安装失败")
            return
        }
        refreshVmPackages()
        setLaunchApp(t.id, safeId)
        _vmInstallState.value = VmInstallUiState(installedPkgId = safeId, stage = "已启动 ${t.name}")
        startSession(t, safeId)
        onLaunched()
    }

    override fun onCleared() {
        sessions.values.forEach { it.release() }
        sessions.clear()
        super.onCleared()
    }
}
