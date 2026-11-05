package com.motionsaver.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.motionsaver.app.data.*
import com.motionsaver.core.*
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
    /** 一次可解析多条链接，每个作品一张结果卡 */
    val results: List<ParsedMedia> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    /** 本次下载是否提取音频（解析结果页可直接切换） */
    val extractAudio: Boolean = false,
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

    val supportedPlatforms = "抖音、小红书、通用网页（可自填解析端点）"

    init {
        _ui.value = MainUiState(settings = settingsStore.load(), history = historyStore.load())
    }

    fun onInputChange(v: String) {
        _ui.update { it.copy(input = v, message = "", isError = false) }
    }

    /** 第一步：解析全部粘贴的链接，把四类资源展示出来供勾选，不下载。 */
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
                    results = emptyList(), selectedIds = emptySet(), queue = emptyList()
                )
            }
            val settings = _ui.value.settings
            val outcome = withContext(Dispatchers.IO) { useCase.analyzeAll(text, settings) }
            when (outcome) {
                is SaveMediaUseCase.ParseAllOutcome.Ok -> {
                    val items = outcome.items
                    val allIds = items.flatMap { p -> p.resources.map { r -> r.url } }.toSet()
                    val warns = items.flatMap { it.warnings }
                    _ui.update {
                        it.copy(
                            isAnalyzing = false,
                            results = items,
                            selectedIds = allIds,
                            message = buildAnalyzeMessage(items, warns),
                            isError = warns.isNotEmpty()
                        )
                    }
                }
                is SaveMediaUseCase.ParseAllOutcome.Err -> {
                    _ui.update {
                        it.copy(isAnalyzing = false, message = "解析失败：${outcome.reason}", isError = true)
                    }
                }
            }
        }
    }

    private fun buildAnalyzeMessage(items: List<ParsedMedia>, warns: List<String>): String = buildString {
        appendLine("共解析 ${items.size} 个作品")
        items.forEachIndexed { i, p ->
            appendLine("${i + 1}. 【${p.type.label}】${p.title}")
            appendLine("   图文 ${p.byKind(ResourceKind.IMAGE).size} · 视频 ${p.videos.size} · " +
                "音频 ${p.audios.size} · 动图 ${p.byKind(ResourceKind.ANIMATED).size}")
            p.notes.take(2).forEach { appendLine("   · $it") }
        }
        warns.forEach { appendLine("⚠ $it") }
    }.trim()

    fun toggle(id: String) {
        _ui.update {
            val next = it.selectedIds.toMutableSet()
            if (!next.add(id)) next.remove(id)
            it.copy(selectedIds = next)
        }
    }

    /** 单个作品的全选 / 取消全选（结果卡右上角的方框）。 */
    fun toggleAllIn(index: Int) {
        val p = _ui.value.results.getOrNull(index) ?: return
        val ids = p.resources.map { it.url }.toSet()
        _ui.update {
            val next = it.selectedIds.toMutableSet()
            if (ids.all { id -> id in next }) next.removeAll(ids) else next.addAll(ids)
            it.copy(selectedIds = next)
        }
    }

    fun selectAll() {
        _ui.update {
            it.copy(selectedIds = it.results.flatMap { p -> p.resources.map { r -> r.url } }.toSet())
        }
    }

    fun selectNone() {
        _ui.update { it.copy(selectedIds = emptySet()) }
    }

    fun setExtractAudio(v: Boolean) {
        _ui.update { it.copy(extractAudio = v) }
    }

    /** 第二步：逐个作品下载勾选项，队列实时刷新，完成后写入历史。 */
    fun downloadSelected() {
        val state = _ui.value
        if (state.isDownloading || state.results.isEmpty()) return
        val pairs = state.results.map { p ->
            p to p.resources.filter { r -> r.url in state.selectedIds }
        }.filter { (p, chosen) ->
            chosen.isNotEmpty() || p.isLivePhoto || p.caption.isNotBlank()
        }
        if (pairs.isEmpty()) {
            _ui.update { it.copy(message = "请先勾选要下载的资源", isError = true) }
            return
        }

        viewModelScope.launch {
            val existing = withContext(Dispatchers.IO) {
                historyStore.load().map { it.fileName }.toMutableSet()
            }
            _ui.update {
                it.copy(isDownloading = true, queue = emptyList(), message = "", isError = false)
            }
            val effective = state.settings.copy(extractAudioFromVideo = state.extractAudio)
            val allSaved = mutableListOf<SaveMediaUseCase.SavedFile>()
            val allWarnings = mutableListOf<String>()

            pairs.forEachIndexed { group, (p, chosen) ->
                val outcome = useCase.download(p, chosen, effective, existing) { ev ->
                    val eid = "g${group}_${ev.id}"
                    _ui.update { s ->
                        val item = QueueItem(eid, ev.label, ev.phase, ev.fraction, ev.detail)
                        val list = s.queue.toMutableList()
                        val idx = list.indexOfFirst { it.id == eid }
                        if (idx >= 0) list[idx] = item else list.add(item)
                        s.copy(queue = list)
                    }
                }
                when (outcome) {
                    is SaveMediaUseCase.DownloadOutcome.Ok -> {
                        allSaved += outcome.files
                        allWarnings += outcome.warnings
                        existing.addAll(outcome.files.map { it.name })
                    }
                    is SaveMediaUseCase.DownloadOutcome.Err -> allWarnings += outcome.reason
                }
            }

            val entries = allSaved.map {
                HistoryEntry(
                    fileName = it.name,
                    savedTo = it.location,
                    detail = "${it.category} · ${it.bytes} 字节",
                    previewUri = it.previewUri,
                    mime = it.mime
                )
            }
            if (entries.isNotEmpty()) {
                withContext(Dispatchers.IO) { historyStore.add(entries) }
            }
            val msg = buildString {
                appendLine("已保存 ${entries.size} 个文件")
                entries.take(8).forEach { appendLine("· ${it.fileName} → ${it.savedTo}") }
                if (entries.size > 8) appendLine("… 其余 ${entries.size - 8} 个")
                allWarnings.forEach { appendLine("⚠ $it") }
            }.trim()
            _ui.update {
                it.copy(
                    isDownloading = false,
                    history = historyStore.load(),
                    message = msg,
                    isError = allWarnings.isNotEmpty()
                )
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

    /** 清空解析结果，保留输入框内容。 */
    fun clearResults() {
        _ui.update {
            it.copy(results = emptyList(), selectedIds = emptySet(), queue = emptyList(), message = "")
        }
    }

    /** 一键清空输入框 + 结果，方便粘贴下一批链接。 */
    fun clearAll() {
        _ui.update {
            it.copy(
                input = "", results = emptyList(), selectedIds = emptySet(),
                queue = emptyList(), message = "", isError = false
            )
        }
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
