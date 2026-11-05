package com.motionsaver.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
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

private val Scrim = Color(0x66000000)

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
    var preview by remember { mutableStateOf<MediaResource?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("粘贴分享链接（可一次贴多条）", Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium)
            TextButton(
                onClick = vm::clearAll,
                enabled = state.input.isNotEmpty() || state.results.isNotEmpty()
            ) { Text("清空") }
        }

        OutlinedTextField(
            value = state.input,
            onValueChange = vm::onInputChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
            placeholder = { Text("抖音 / 小红书 / 任意网页链接，可粘贴 2~3 条整段口令") },
            enabled = !state.isAnalyzing && !state.isDownloading,
            maxLines = 8
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
                enabled = state.results.isNotEmpty() && !state.isDownloading
            ) { Text(if (state.isDownloading) "下载中…" else "② 下载选中 ${state.selectedIds.size}") }
        }

        if (state.isAnalyzing || state.isDownloading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        // 多条链接 → 每个作品一张结果卡
        state.results.forEachIndexed { i, p ->
            ResultCard(i, p, state, vm) { preview = it }
        }

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
                        if (state.results.isNotEmpty()) {
                            TextButton(onClick = vm::clearResults) { Text("清空结果") }
                        }
                    }
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text("支持：${vm.supportedPlatforms}", style = MaterialTheme.typography.bodySmall)
        Text(
            "点缩略图直接预览；右上角圆圈点一下选中、再点取消；选中卡片会浮起放大。" +
                "图集默认选最高质量 JPEG 原图，自动排除水印与缩放版本。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "仅供保存自己有权保存的内容；请遵守平台条款与适用法律。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    preview?.let { r -> PreviewDialog(r) { preview = null } }
}

@Composable
private fun ResultCard(
    index: Int,
    p: ParsedMedia,
    state: MainUiState,
    vm: MainViewModel,
    onPreview: (MediaResource) -> Unit
) {
    val all = p.resources
    val allSelected = all.isNotEmpty() && all.all { it.url in state.selectedIds }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 标题行 + 右上角全选方框 + 清空
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("【${p.type.label}】${p.title}", fontWeight = FontWeight.SemiBold)
                    if (p.author.isNotBlank()) {
                        Text("作者：${p.author}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = allSelected, onCheckedChange = { vm.toggleAllIn(index) })
                    Text("全选", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = vm::clearResults) { Text("清空") }
            }

            // 解析后是否提取音频
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("提取音频（视频无损抽音轨）", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall)
                Switch(checked = state.extractAudio, onCheckedChange = vm::setExtractAudio)
            }

            val groups = listOf(
                "图文" to p.byKind(ResourceKind.IMAGE),
                "视频" to p.videos,
                "音频" to p.audios,
                "动图" to p.byKind(ResourceKind.ANIMATED)
            ).filter { it.second.isNotEmpty() }

            if (groups.isEmpty() && !p.isLivePhoto) {
                Text(
                    "本次未拿到媒体地址：平台返回了未渲染的页面壳，已自动切换多种公开取页策略并重试。可再点一次解析，或在高级设置里填入自己的解析端点。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            groups.forEach { (title, list) ->
                Text("$title（${list.size}）", fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium)
                // 每排 3 个缩略图，避免嵌套滚动冲突
                list.chunked(3).forEach { rowItems ->
                    Row(Modifier.fillMaxWidth()) {
                        rowItems.forEach { r ->
                            ResourceTile(
                                r = r,
                                selected = r.url in state.selectedIds,
                                onToggle = { vm.toggle(r.url) },
                                onPreview = { onPreview(r) }
                            )
                        }
                        repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f).padding(4.dp)) }
                    }
                }
            }

            if (p.isLivePhoto) {
                Text("检测到实况图 · 将合成为小米动态照片",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = vm::selectAll) { Text("全部作品全选") }
                TextButton(onClick = vm::selectNone) { Text("全不选") }
            }

            p.warnings.forEach {
                Text("⚠ $it", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error)
            }
            p.notes.take(3).forEach {
                Text("· $it", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 缩略图卡片：点开预览，右上角圆形按钮切换选中，选中后整体浮起放大。 */
@Composable
private fun RowScope.ResourceTile(
    r: MediaResource,
    selected: Boolean,
    onToggle: () -> Unit,
    onPreview: () -> Unit
) {
    Card(
        modifier = Modifier
            .weight(1f)
            .padding(4.dp)
            .graphicsLayer {
                scaleX = if (selected) 1.04f else 1f
                scaleY = if (selected) 1.04f else 1f
            }
            .clickable { onPreview() },
        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 10.dp else 1.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Box {
            if (r.isImage || r.kind == ResourceKind.COVER || r.kind == ResourceKind.ANIMATED) {
                AsyncImage(
                    model = r.url,
                    contentDescription = r.label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(110.dp)
                )
            } else {
                Box(
                    Modifier.fillMaxWidth().height(110.dp).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (r.isVideo) "▶ 视频" else "♫ 音频",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }

            // 右上角圆形选中按钮：选中显示对勾，未选中是空心圆
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.primary else Scrim)
                    .border(2.dp, Color.White, CircleShape)
                    .clickable { onToggle() },
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Text("✓", color = Color.White, style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold)
                }
            }

            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Scrim).padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                Text(r.label, color = Color.White,
                    style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
        if (r.qualityLabel.isNotBlank()) {
            Text(r.qualityLabel, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp), maxLines = 1)
        }
    }
}

/** 点开直接看：图片内嵌全屏预览，视频/音频交给系统播放器。 */
@Composable
private fun PreviewDialog(r: MediaResource, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val playable = r.isVideo || r.isAudio
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        if (playable) {
            LaunchedEffect(r.url) {
                openExternal(ctx, r.url, if (r.isVideo) "video/*" else "audio/*")
                onDismiss()
            }
            Box(Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center) {
                Text("正在打开播放器…", color = Color.White)
            }
        } else {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AsyncImage(
                    model = r.url,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
                TextButton(onClick = onDismiss,
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                    Text("✕ 关闭", color = Color.White)
                }
                Text(r.qualityLabel.ifBlank { r.label }, color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
            }
        }
    }
}

private fun openExternal(ctx: Context, url: String, mime: String) {
    runCatching {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), mime)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }
}

private fun openSaved(ctx: Context, e: HistoryEntry) {
    if (e.previewUri.isBlank()) return
    runCatching {
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(e.previewUri), e.mime.ifBlank { "image/*" })
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
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
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.history.isNotEmpty()) {
                TextButton(onClick = vm::clearHistory) { Text("清空下载记录") }
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
                items(state.history) { e ->
                    HistoryCard(e, fmt) { openSaved(ctx, e) }
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(e: HistoryEntry, fmt: SimpleDateFormat, onOpen: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable { onOpen() }
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(e.fileName, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (e.previewUri.isNotBlank()) {
                    Text("预览 ›", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
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

/** 主设置页保持极简，全部细节收进「高级设置」二级页。 */
@Composable
private fun SettingsScreen(state: MainUiState, vm: MainViewModel) {
    var advanced by remember { mutableStateOf(false) }
    if (advanced) {
        AdvancedSettingsScreen(state, vm) { advanced = false }
        return
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("设置", style = MaterialTheme.typography.titleLarge)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("MotionSaver 2.2.0", fontWeight = FontWeight.SemiBold)
                Text(
                    "粘贴链接 → 解析 → 点缩略图预览 → 圆圈选中 → 下载。" +
                        "解析结果页可直接切换「提取音频」与全选。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Button(onClick = { advanced = true }, Modifier.fillMaxWidth()) {
            Text("高级设置 →")
        }
        Text(
            "端点配置、字段映射、并发数、保存行为、动态照片自检等都收在高级设置里，主页面不再堆砌。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        OutlinedButton(onClick = vm::clearHistory, Modifier.fillMaxWidth()) {
            Text("清空下载记录")
        }
    }
}

@Composable
private fun AdvancedSettingsScreen(
    state: MainUiState,
    vm: MainViewModel,
    onBack: () -> Unit
) {
    var draft by remember(state.settings) { mutableStateOf(state.settings) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Text("高级设置", style = MaterialTheme.typography.titleMedium)
        }

        Text("解析端点（可选）", style = MaterialTheme.typography.titleMedium)
        Text(
            "公开分享页通常不声明视频/音频直链。填入你自己的合规数据源（自建服务 / yt-dlp 自托管 / 已授权 API），即可一次性拿到四类完整资源。App 不预置任何第三方私有接口。",
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
        SettingsSwitch("解析结果页默认勾选提取音频", draft.extractAudioFromVideo) {
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
        Text("用内置素材合成一份动态照片并逐项校验 XMP 与尾部 MP4，无需联网。",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = vm::selfCheck, Modifier.fillMaxWidth()) { Text("运行自检") }
        if (state.selfCheck.isNotBlank()) {
            Card(Modifier.fillMaxWidth()) {
                Text(state.selfCheck, Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall)
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text("关于", style = MaterialTheme.typography.titleMedium)
        InfoRow("解析方式", "公开页访客渲染数据 / JSON-LD / og:* 三层兜底；不构造私有签名、不绕过鉴权")
        InfoRow("画质", "图集自动选最高质量 JPEG 候选，排除水印与缩放模板；多 CDN 直链自动回退")
        InfoRow("动态照片", "JPEG + 尾部 MP4 + XMP 偏移元数据，字节级无损拼接")
        InfoRow("架构", "core 纯 JVM 核心库（70 项单测）+ app Compose 层；arm64-v8a；minSdk 26")
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
