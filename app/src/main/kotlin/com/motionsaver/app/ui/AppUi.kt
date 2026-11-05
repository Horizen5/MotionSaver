package com.motionsaver.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.motionsaver.app.MainUiState
import com.motionsaver.app.MainViewModel
import com.motionsaver.app.QueueItem
import com.motionsaver.app.data.AppSettings
import com.motionsaver.app.data.HistoryEntry
import com.motionsaver.core.MediaResource
import com.motionsaver.core.ParsedMedia
import com.motionsaver.core.ResourceKind
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: MainViewModel) {
    val state by vm.ui.collectAsState()
    var tab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("动态存 · MotionSaver", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        bottomBar = {
            NavigationBar {
                listOf("解析下载", "队列", "记录", "设置").forEachIndexed { i, label ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        label = { Text(label) },
                        icon = { Text(listOf("↓", "≡", "◷", "⚙")[i]) }
                    )
                }
            }
        }
    ) { padding ->
        Surface(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> HomeScreen(state, vm)
                1 -> QueueScreen(state)
                2 -> HistoryScreen(state, vm)
                else -> SettingsScreen(state, vm)
            }
        }
    }
}

// ------------------------------------------------------------------ 解析下载

@Composable
private fun HomeScreen(state: MainUiState, vm: MainViewModel) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("粘贴分享链接，先解析再勾选下载", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = state.input,
            onValueChange = vm::onInputChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
            placeholder = { Text("抖音 / 小红书 / 任意网页链接，或直接粘贴整段口令") },
            enabled = !state.isAnalyzing && !state.isDownloading,
            maxLines = 6
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = vm::analyze,
                modifier = Modifier.weight(1f),
                enabled = !state.isAnalyzing && !state.isDownloading
            ) { Text(if (state.isAnalyzing) "解析中…" else "① 解析") }

            Button(
                onClick = vm::downloadSelected,
                modifier = Modifier.weight(1f),
                enabled = state.parsed != null && !state.isDownloading
            ) { Text(if (state.isDownloading) "下载中…" else "② 下载选中 ${state.selectedIds.size}") }
        }

        if (state.isAnalyzing || state.isDownloading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        state.parsed?.let { p -> ResultCard(p, state, vm) }

        if (state.message.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (state.isError) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.secondaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(state.message, style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = vm::clearMessage) { Text("关闭") }
                        if (state.parsed != null) {
                            TextButton(onClick = vm::clearParsed) { Text("清空结果") }
                        }
                    }
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text("支持：${vm.supportedPlatforms}", style = MaterialTheme.typography.bodySmall)
        Text(
            "图文 / 视频 / 音频 / 动图四类资源一次解析、按需勾选；" +
                "视频与图片按作品标题命名；实况图合成小米可识别的单文件动态照片（.jpg，长按播放）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "仅供保存自己有权保存的内容；请遵守平台条款与适用法律。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ResultCard(p: ParsedMedia, state: MainUiState, vm: MainViewModel) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("【${p.type.label}】${p.title}", fontWeight = FontWeight.SemiBold)
            if (p.author.isNotBlank()) {
                Text("作者：${p.author}", style = MaterialTheme.typography.bodySmall)
            }

            val groups = listOf(
                "图文" to p.byKind(ResourceKind.IMAGE),
                "视频" to p.videos,
                "音频" to p.audios,
                "动图" to p.byKind(ResourceKind.ANIMATED)
            ).filter { it.second.isNotEmpty() }

            if (groups.isEmpty() && !p.isLivePhoto) {
                Text(
                    "该页面未暴露可下载资源。可在设置里填入自己的解析端点。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            groups.forEach { (title, list) ->
                Text("$title（${list.size}）", fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium)
                list.forEach { r -> ResourceRow(r, state, vm) }
            }

            if (p.isLivePhoto) {
                Text(
                    "检测到实况图 · 将合成为小米动态照片（可在设置里关闭）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = vm::selectAll) { Text("全选") }
                TextButton(onClick = vm::selectNone) { Text("全不选") }
            }

            p.warnings.forEach {
                Text("⚠ $it", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ResourceRow(r: MediaResource, state: MainUiState, vm: MainViewModel) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = r.url in state.selectedIds,
            onCheckedChange = { vm.toggle(r.url) }
        )
        Column(Modifier.weight(1f)) {
            Text(r.label.ifBlank { "资源" }, style = MaterialTheme.typography.bodyMedium)
            Text(
                r.url.take(64).let { if (r.url.length > 64) "$it…" else it },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ------------------------------------------------------------------ 队列

@Composable
private fun QueueScreen(state: MainUiState) {
    if (state.queue.isEmpty()) {
        EmptyHint("还没有下载任务。先到「解析下载」解析并勾选资源。")
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(state.queue, key = { it.id }) { q -> QueueCard(q) }
    }
}

@Composable
private fun QueueCard(q: QueueItem) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(q.label, fontWeight = FontWeight.SemiBold)
                Text(
                    when (q.phase) {
                        "done" -> "✓ 完成"
                        "failed" -> "✗ 失败"
                        else -> "下载中"
                    },
                    style = MaterialTheme.typography.labelSmall
                )
            }
            if (q.phase == "running") {
                LinearProgressIndicator(
                    progress = { q.fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (q.detail.isNotBlank()) {
                Text(q.detail, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ------------------------------------------------------------------ 记录

@Composable
private fun HistoryScreen(state: MainUiState, vm: MainViewModel) {
    Column(Modifier.fillMaxSize()) {
        if (state.history.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End) {
                TextButton(onClick = vm::clearHistory) { Text("清空记录") }
            }
        }
        if (state.history.isEmpty()) {
            EmptyHint("还没有下载记录")
        } else {
            val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.history) { e -> HistoryCard(e, fmt) }
            }
        }
    }
}

@Composable
private fun HistoryCard(e: HistoryEntry, fmt: SimpleDateFormat) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(e.fileName, fontWeight = FontWeight.SemiBold)
            Text(e.detail, style = MaterialTheme.typography.bodySmall)
            Text(e.savedTo, style = MaterialTheme.typography.labelSmall)
            Text(fmt.format(Date(e.timestamp)), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ------------------------------------------------------------------ 设置

@Composable
private fun SettingsScreen(state: MainUiState, vm: MainViewModel) {
    var draft by remember(state.settings) { mutableStateOf(state.settings) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("解析端点（可选）", style = MaterialTheme.typography.titleMedium)
        Text(
            "公开分享页常常只暴露封面和标题。填入你自己的合规数据源（自建服务 / yt-dlp 自托管 / 已授权 API），" +
                "即可一次性拿到图文、视频、音频、动图完整资源。App 不预置任何第三方私有接口。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SettingsSwitch("启用自填端点", draft.endpointEnabled) {
            draft = draft.copy(endpointEnabled = it)
        }

        LabeledField("URL 模板（必须含 {url}）", draft.endpointUrl,
            "https://your.host/parse?url={url}") { draft = draft.copy(endpointUrl = it) }
        LabeledField("请求头（每行一个，可留空）", draft.endpointHeaders,
            "Authorization: Bearer xxx", singleLine = false) {
            draft = draft.copy(endpointHeaders = it)
        }

        Text("JSON 字段映射", style = MaterialTheme.typography.titleSmall)
        LabeledField("图文数组", draft.fieldImages, "images") { draft = draft.copy(fieldImages = it) }
        LabeledField("视频数组", draft.fieldVideos, "videos") { draft = draft.copy(fieldVideos = it) }
        LabeledField("音频数组", draft.fieldAudios, "audios") { draft = draft.copy(fieldAudios = it) }
        LabeledField("动图数组", draft.fieldAnimated, "animated") { draft = draft.copy(fieldAnimated = it) }
        LabeledField("实况封面字段", draft.fieldCover, "cover") { draft = draft.copy(fieldCover = it) }
        LabeledField("实况视频字段", draft.fieldLiveVideo, "live_video") {
            draft = draft.copy(fieldLiveVideo = it)
        }

        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text("保存行为", style = MaterialTheme.typography.titleMedium)

        SettingsSwitch("实况图合成小米动态照片", draft.motionPhotoEnabled) {
            draft = draft.copy(motionPhotoEnabled = it)
        }
        SettingsSwitch("图文笔记同时保存正文 .txt", draft.saveCaption) {
            draft = draft.copy(saveCaption = it)
        }
        SettingsSwitch("平台无音频时从视频无损抽音轨", draft.extractAudioFromVideo) {
            draft = draft.copy(extractAudioFromVideo = it)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("并发数：${draft.concurrency}", Modifier.weight(1f))
            OutlinedButton(onClick = {
                draft = draft.copy(concurrency = (draft.concurrency - 1).coerceIn(1, 8))
            }) { Text("−") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                draft = draft.copy(concurrency = (draft.concurrency + 1).coerceIn(1, 8))
            }) { Text("+") }
        }

        Button(onClick = { vm.updateSettings(draft) }, Modifier.fillMaxWidth()) {
            Text("保存设置")
        }

        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text("动态照片自检", style = MaterialTheme.typography.titleMedium)
        Text(
            "用内置素材合成一份动态照片并逐项校验 XMP 与尾部 MP4，无需联网。",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedButton(onClick = vm::selfCheck, Modifier.fillMaxWidth()) { Text("运行自检") }
        if (state.selfCheck.isNotBlank()) {
            Card(Modifier.fillMaxWidth()) {
                Text(state.selfCheck, Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall)
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text("关于", style = MaterialTheme.typography.titleMedium)
        InfoRow("四类解析", "图文 / 视频 / 音频 / 动图，按资源种类自动分组、按需勾选")
        InfoRow("文件命名", "取作品标题，清洗 emoji 与非法字符、折叠空白、超长截断、图集加序号、重名自动加后缀")
        InfoRow("动态照片", "小米 / Google Motion Photo 单文件标准：JPEG + 尾部 MP4 + XMP 偏移元数据，字节级无损拼接")
        InfoRow("音频", "平台给音频直链则直接下载；否则可用 MediaExtractor 从视频流拷贝抽 AAC 音轨，不重编码")
        InfoRow("解析方式", "只读取公开页元信息，或走你自填的合规端点；不构造平台私有签名、不绕过鉴权")
        InfoRow("架构", "core 纯 JVM 核心库（54 项单测）+ app Compose 层；arm64-v8a 单架构；minSdk 26")
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SettingsSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    placeholder: String,
    singleLine: Boolean = true,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = singleLine,
        maxLines = if (singleLine) 1 else 4
    )
}

@Composable
private fun InfoRow(k: String, v: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        Text(v, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
