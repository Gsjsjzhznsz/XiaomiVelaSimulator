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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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
        _templateList.value = templates.loadAll()
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
                    fileProgress = if (total > 0) "$name  ${(downloaded / 1024 / 1024)}MB / ${total / 1024 / 1024}MB" else "$name  ${downloaded / 1024}KB"
                )
            }
            override fun onLog(line: String) { /* 运行时安装日志可并入阶段 */ }
        }
        installJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching { runtime.install(mirror) }
                .onSuccess {
                    _runtimeState.value = RuntimeUiState(
                        installed = true, busy = false,
                        stage = "QEMU 运行时就绪",
                        version = runtime.qemuVersion(),
                    )
                }
                .onFailure { e ->
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
    )

    private val _imageState = MutableStateFlow(ImageUiState())
    val imageState: StateFlow<ImageUiState> = _imageState

    private var downloadJob: Job? = null

    fun downloadImage(entry: ImageManager.ImageEntry) {
        if (downloadJob?.isActive == true) return
        _imageState.value = ImageUiState(busy = true, stage = "开始下载…")
        images.progress = object : QemuRuntime.Progress {
            override fun onStage(stage: String) {
                _imageState.value = _imageState.value.copy(stage = stage)
            }
            override fun onFileProgress(name: String, downloaded: Long, total: Long) {
                _imageState.value = _imageState.value.copy(
                    fileProgress = if (total > 0) {
                        val pct = downloaded * 100 / total
                        "$name  ${pct}%  (${downloaded / 1024}KB)"
                    } else "$name  ${downloaded / 1024}KB"
                )
            }
            override fun onLog(line: String) {}
        }
        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching { images.download(entry) }
                .onSuccess { _imageState.value = ImageUiState(stage = "镜像就绪") }
                .onFailure { _imageState.value = ImageUiState(error = it.message ?: "下载失败") }
        }
    }

    fun importImage(uri: android.net.Uri, outName: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val r = images.import(uri, outName)
            onDone(r.isSuccess)
        }
    }

    // ---- 会话 ----
    private var session: QemuSession? = null
    private val _sessionState = MutableStateFlow<QemuSession?>(null)
    val sessionState: StateFlow<QemuSession?> = _sessionState

    fun startSession(t: DeviceTemplate) {
        val cur = session?.state?.value
        if (cur == QemuSession.State.RUNNING || cur == QemuSession.State.BOOTING) return
        session?.release()
        val s = QemuSession(runtime, t, images.imagesDir)
        s.console.onText = { text -> s.appendLogRaw(text) }
        session = s
        _sessionState.value = s
        viewModelScope.launch(Dispatchers.IO) { s.start() }
    }

    fun stopSession() {
        session?.stop()
    }

    fun currentSession(): QemuSession? = session

    override fun onCleared() {
        session?.release()
        super.onCleared()
    }
}
