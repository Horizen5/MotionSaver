package com.motionsaver.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.motionsaver.app.data.*
import com.motionsaver.core.MediaResource
import com.motionsaver.core.MotionPhotoVerifier
import com.motionsaver.core.MotionPhotoWriter
import com.motionsaver.core.ParsedMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class QueueItem(
    val id: String,
    val label: String,
    val phase: String,
    val fraction: Float,
    val detail: String
)

data class MainUiState(
    val input: String = "",
    val isAnalyzing: Boolean = false,
    val isDownloading: Boolean = false,
    val parsed: ParsedMedia? = null,
    val selectedIds: Set<String> = emptySet(),
    val queue: List<QueueItem> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val message: String = "",
    val isError: Boolean = false,
    val selfCheck: String = ""
)

class MainViewModel(
    private val useCase: SaveMediaUseCase,
    private val settingsStore: SettingsStore,
    private val historyStore: HistoryStore,
    private val appContext: Context
) : ViewModel() {

    private val _ui = MutableStateFlow(MainUiState())
    val ui: StateFlow<MainUiState> = _ui.asStateFlow()

    val supportedPlatforms = "抖音、小红书、通用网页" + "（可自填解析端点）"

    init {
        _ui.value = MainUiState(settings = settingsStore.load(), history = historyStore.load())
    }

    fun onInputChange(v: String) {
        _ui.update { it.copy(input = v, message = "", isError = false) }
    }

    /** 第一步：只解析，把四类资源展示出来供勾选，不下载。 */
    fun analyze() {
        val text = _ui.value.input.trim()
        if (text.isEmpty()) {
            _ui.update { it.copy(message = "请先粘贴分享链接或整段口令", isError = true) }
            return
        }
        if (_ui.value.isAnalyzing || _ui.value.isDownloading) return

        viewModelScope.launch {
            _ui.update {
                it.copy(
                    isAnalyzing = true, message = "", isError = false,
                    parsed = null, selectedIds = emptySet(), queue = emptyList()
                )
            }
            val settings = _ui.value.settings
            val outcome = withContext(Dispatchers.IO) { useCase.analyze(text, settings) }
            when (outcome) {
                is SaveMediaUseCase.ParseOutcome.Ok -> {
                    val p = outcome.media
                    _ui.update {
                        it.copy(
                            isAnalyzing = false,
                            parsed = p,
                            selectedIds = p.resources.map { r -> r.url }.toSet(),
                            message = buildAnalyzeMessage(p),
                            isError = p.warnings.isNotEmpty()
                        )
                    }
                }
                is SaveMediaUseCase.ParseOutcome.Err -> {
                    _ui.update {
                        it.copy(isAnalyzing = false, message = "解析失败：${outcome.reason}", isError = true)
                    }
                }
            }
        }
    }

    private fun buildAnalyzeMessage(p: ParsedMedia): String = buildString {
        appendLine("【${p.type.label}】${p.title}")
        if (p.author.isNotBlank()) appendLine("作者：${p.author}")
        appendLine("图文 ${p.byKind(com.motionsaver.core.ResourceKind.IMAGE).size} · 视频 ${p.videos.size} · 音频 ${p.audios.size} · 动图 ${p.byKind(com.motionsaver.core.ResourceKind.ANIMATED).size}")
        if (p.isLivePhoto) appendLine("检测到实况图：可合成小米动态照片")
        p.notes.forEach { appendLine("· $it") }
        p.warnings.forEach { appendLine("⚠ $it") }
    }.trim()

    fun toggle(id: String) {
        _ui.update {
            val next = it.selectedIds.toMutableSet()
            if (!next.add(id)) next.remove(id)
            it.copy(selectedIds = next)
        }
    }

    fun selectAll() {
        _ui.update { it.copy(selectedIds = it.parsed?.resources?.map { r -> r.url }?.toSet() ?: emptySet()) }
    }

    fun selectNone() {
        _ui.update { it.copy(selectedIds = emptySet()) }
    }

    /** 第二步：下载勾选项，队列实时刷新，完成后写入历史。 */
    fun downloadSelected() {
        val p = _ui.value.parsed ?: return
        if (_ui.value.isDownloading) return
        val chosen = p.resources.filter { it.url in _ui.value.selectedIds }

        viewModelScope.launch {
            val existing = withContext(Dispatchers.IO) {
                historyStore.load().map { it.fileName }.toSet()
            }
            _ui.update {
                it.copy(isDownloading = true, queue = emptyList(), message = "", isError = false)
            }
            val outcome = useCase.download(
                p, chosen, _ui.value.settings, existing
            ) { ev ->
                _ui.update { s ->
                    val item = QueueItem(ev.id, ev.label, ev.phase, ev.fraction, ev.detail)
                    val list = s.queue.toMutableList()
                    val idx = list.indexOfFirst { it.id == ev.id }
                    if (idx >= 0) list[idx] = item else list.add(item)
                    s.copy(queue = list)
                }
            }
            when (outcome) {
                is SaveMediaUseCase.DownloadOutcome.Ok -> {
                    val entries = outcome.files.map {
                        HistoryEntry(it.name, it.location, "${it.category} · ${it.bytes} 字节")
                    }
                    withContext(Dispatchers.IO) { historyStore.add(entries) }
                    val msg = buildString {
                        appendLine("已保存 ${entries.size} 个文件")
                        outcome.files.take(8).forEach { appendLine("· ${it.name} → ${it.location}") }
                        if (outcome.files.size > 8) appendLine("… 其余 ${outcome.files.size - 8} 个")
                        outcome.warnings.forEach { appendLine("⚠ $it") }
                    }.trim()
                    _ui.update {
                        it.copy(
                            isDownloading = false,
                            history = historyStore.load(),
                            message = msg,
                            isError = outcome.warnings.isNotEmpty()
                        )
                    }
                }
                is SaveMediaUseCase.DownloadOutcome.Err -> {
                    _ui.update {
                        it.copy(isDownloading = false, message = outcome.reason, isError = true)
                    }
                }
            }
        }
    }

    fun updateSettings(s: AppSettings) {
        settingsStore.save(s)
        _ui.update { it.copy(settings = s, message = "设置已保存，下次解析生效") }
    }

    fun clearMessage() {
        _ui.update { it.copy(message = "", isError = false) }
    }

    fun clearParsed() {
        _ui.update { it.copy(parsed = null, selectedIds = emptySet(), queue = emptyList()) }
    }

    fun clearHistory() {
        historyStore.clear()
        _ui.update { it.copy(history = emptyList(), message = "下载记录已清空") }
    }

    /** 真机自检：用内置素材合成一份动态照片并逐项校验，验证 XMP 与尾部 MP4 结构。 */
    fun selfCheck() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val jpeg = appContext.assets.open("selfcheck/cover.jpg").use { it.readBytes() }
                    val mp4 = appContext.assets.open("selfcheck/clip.mp4").use { it.readBytes() }
                    val composed = MotionPhotoWriter.compose(jpeg, mp4)
                    val r = MotionPhotoVerifier.verify(composed.bytes)
                    buildString {
                        appendLine("合成文件：${composed.bytes.size} 字节")
                        appendLine("JPEG 段：${composed.jpegLength} / MP4 段：${composed.videoLength}")
                        appendLine("MicroVideoOffset：${r.microVideoOffset}")
                        appendLine("MotionPhoto=1：${r.motionPhotoFlag}")
                        appendLine("ftyp 正确：${r.hasFtyp} / 段尾 EOI：${r.hasEoiBeforeVideo}")
                        appendLine(if (r.valid) "结论：符合小米动态照片单文件结构" else "结论：异常 ${r.problems}")
                    }.trim()
                }.getOrElse { "自检失败：${it.message}" }
            }
            _ui.update { it.copy(selfCheck = result) }
        }
    }

    class Factory(
        private val useCase: SaveMediaUseCase,
        private val settingsStore: SettingsStore,
        private val historyStore: HistoryStore,
        private val context: Context
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MainViewModel(useCase, settingsStore, historyStore, context) as T
    }
}
