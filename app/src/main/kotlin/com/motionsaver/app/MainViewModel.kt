package com.motionsaver.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.motionsaver.app.data.Downloader
import com.motionsaver.app.data.SaveMediaUseCase
import com.motionsaver.core.ParserRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HistoryEntry(
    val fileName: String,
    val savedTo: String,
    val detail: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class MainUiState(
    val input: String = "",
    val isBusy: Boolean = false,
    val progressText: String = "",
    val message: String = "",
    val isError: Boolean = false,
    val history: List<HistoryEntry> = emptyList()
)

class MainViewModel(private val useCase: SaveMediaUseCase) : ViewModel() {

    private val _ui = MutableStateFlow(MainUiState())
    val ui: StateFlow<MainUiState> = _ui.asStateFlow()

    val supportedPlatforms: String = ParserRegistry().supported()

    fun onInputChange(v: String) {
        _ui.value = _ui.value.copy(input = v, message = "", isError = false)
    }

    fun submit() {
        val text = _ui.value.input.trim()
        if (text.isEmpty()) {
            _ui.value = _ui.value.copy(message = "请先粘贴分享链接或口令", isError = true)
            return
        }
        if (_ui.value.isBusy) return

        viewModelScope.launch {
            _ui.value = _ui.value.copy(isBusy = true, isError = false, message = "", progressText = "解析中…")
            val existing = _ui.value.history.map { it.fileName }.toSet()
            when (val outcome = useCase.run(text, existing) { p ->
                _ui.value = _ui.value.copy(
                    progressText = if (p.total > 0) "下载中 ${p.downloaded / 1024} / ${p.total / 1024} KB" else "下载中 ${p.downloaded / 1024} KB"
                )
            }) {
                is SaveMediaUseCase.Outcome.Success -> {
                    val entry = HistoryEntry(outcome.fileName, outcome.savedTo, outcome.detail)
                    _ui.value = _ui.value.copy(
                        isBusy = false,
                        progressText = "",
                        message = "已保存：${outcome.fileName}\n${outcome.detail}\n位置：${outcome.savedTo}",
                        history = listOf(entry) + _ui.value.history
                    )
                }
                is SaveMediaUseCase.Outcome.Failure -> {
                    _ui.value = _ui.value.copy(
                        isBusy = false, progressText = "", isError = true,
                        message = "失败：${outcome.reason}"
                    )
                }
                is SaveMediaUseCase.Outcome.NeedAttention -> {
                    _ui.value = _ui.value.copy(
                        isBusy = false, progressText = "", isError = true,
                        message = outcome.warnings.joinToString("\n")
                    )
                }
            }
        }
    }

    fun clearMessage() {
        _ui.value = _ui.value.copy(message = "", isError = false)
    }

    class Factory(private val useCase: SaveMediaUseCase) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(useCase) as T
    }
}
