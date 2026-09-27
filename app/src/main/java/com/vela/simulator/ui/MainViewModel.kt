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
import com.vela.simulator.quickapp.RpkInstaller
import com.vela.simulator.quickapp.RpkManager
import com.vela.simulator.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
                    val ok = images.ensureAssetsCurrent(t.imageId)
                    if (!ok) {
                        s.failWith("内置镜像部署校验失败，请到设备详情页重新部署", null)
                        return@launch
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

    /** 刷新数据盘包列表（工坊页进入时/安装卸载后调用） */
    fun refreshVmPackages() {
        if (_vmDiskBusy.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _vmDiskBusy.value = true
            RpkInstaller.list(runtime, vmDataDisk)
                .onSuccess {
                    _vmPackages.value = it
                    FileLogger.i("rpk", "数据盘包列表: ${it.joinToString { p -> p.packageId }}")
                }
                .onFailure { FileLogger.w("rpk", "数据盘读取失败: ${it.message}") }
            _vmDiskBusy.value = false
        }
    }

    /** 安装已导入的 rpk 到数据盘；完成后自动刷新列表。写盘前自动停止运行中的虚拟机 */
    fun installRpkToVm(pkg: RpkManager.QuickAppPackage) {
        if (_vmInstallState.value.busy) return
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
                .onFailure {
                    FileLogger.e("rpk", "rpk 安装失败", it)
                    _vmInstallState.value = VmInstallUiState(error = it.message ?: "安装失败")
                }
        }
    }

    fun removeRpkFromVm(packageId: String) {
        if (_vmDiskBusy.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _vmDiskBusy.value = true
            prepareDiskExclusive()?.let {
                _vmInstallState.value = VmInstallUiState(error = it)
                _vmDiskBusy.value = false
                refreshVmPackages()
                return@launch
            }
            RpkInstaller.remove(runtime, vmDataDisk, packageId)
                .onFailure { FileLogger.e("rpk", "rpk 卸载失败: $packageId", it) }
            _vmDiskBusy.value = false
            refreshVmPackages()
        }
    }

    /**
     * v2.2 工坊主入口：安装 rpk 到数据盘 → 指定模板启动该包 → 回调导航到运行页。
     * 已装过的包直接启动（跳过写入）；写盘前自动停止全部运行中虚拟机（v2.2.1 互斥保护）。
     */
    fun installRpkAndLaunch(t: DeviceTemplate, pkg: RpkManager.QuickAppPackage, onLaunched: () -> Unit) {
        if (_vmInstallState.value.busy) return
        viewModelScope.launch(Dispatchers.IO) {
            val installed = _vmPackages.value.any { it.packageId == pkg.packageId }
            if (!installed) {
                _vmInstallState.value = VmInstallUiState(busy = true, stage = "准备…")
                prepareDiskExclusive()?.let {
                    _vmInstallState.value = VmInstallUiState(error = it)
                    return@launch
                }
                val progress = object : QemuRuntime.Progress {
                    override fun onStage(stage: String) {
                        _vmInstallState.value = _vmInstallState.value.copy(busy = true, stage = stage, error = null)
                    }
                    override fun onFileProgress(name: String, downloaded: Long, total: Long) {}
                    override fun onLog(line: String) { FileLogger.i("rpk", line) }
                }
                val r = RpkInstaller.install(runtime, vmDataDisk, File(pkg.filePath), progress)
                if (r.isFailure) {
                    _vmInstallState.value = VmInstallUiState(error = r.exceptionOrNull()?.message ?: "安装失败")
                    return@launch
                }
                refreshVmPackages()
            }
            setLaunchApp(t.id, pkg.packageId)
            _vmInstallState.value = VmInstallUiState(installedPkgId = pkg.packageId, stage = "已启动 ${t.name}")
            startSession(t, pkg.packageId)
            onLaunched()
        }
    }

    override fun onCleared() {
        sessions.values.forEach { it.release() }
        sessions.clear()
        super.onCleared()
    }
}
