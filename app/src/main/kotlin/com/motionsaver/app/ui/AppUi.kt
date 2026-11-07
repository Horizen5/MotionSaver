package com.motionsaver.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.motionsaver.app.MainUiState
import com.motionsaver.app.MainViewModel
import com.motionsaver.app.MotionSaverApp
import com.motionsaver.app.QueueItem
import com.motionsaver.app.data.AppSettings
import com.motionsaver.app.data.HistoryEntry
import com.motionsaver.core.MediaResource
import com.motionsaver.core.MediaType
import com.motionsaver.core.ParsedMedia
import com.motionsaver.core.ResourceKind
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

private val Scrim = Color(0x66000000)
private val TAB_LABELS = listOf("解析", "队列", "记录", "设置")
private val TAB_ICONS = listOf("↓", "≡", "◷", "⚙")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(
    container: MotionSaverApp.Container,
    initialSharedText: String? = null
) {
    val ctx = LocalContext.current
    val vm: MainViewModel = viewModel(
        factory = MainViewModel.Factory(
            container.saveMedia,
            container.settings,
            container.history,
            ctx.applicationContext
        )
    )
    // lifecycle 感知：App 退到后台时不重组、不刷新，降低占用与耗电
    val state by vm.ui.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }

    // 外部分享文本只灌一次，避免旋转/重组时反复触发
    LaunchedEffect(initialSharedText) {
        if (!initialSharedText.isNullOrBlank()) vm.onInputChange(initialSharedText)
    }

    val dark = isSystemInDarkTheme()
    // 全屏彩色光晕作「壁纸」，上面的顶栏 / 卡片 / 底栏是半透明柔光玻璃，
    // 形成 HyperOS 4 要求的 ≥2 层玻璃层次；静态绘制，不逐帧模糊。
    Box(Modifier.fillMaxSize()) {
        Glass.GlowBackground(dark)
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = Glass.onGlass(dark),
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                "动态存",
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp,
                                color = Glass.onGlass(dark)
                            )
                            Text(
                                "MotionSaver · 图文 / 视频 / 音频 / 动图",
                                fontSize = 11.sp,
                                color = Glass.onGlassSub(dark)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = Glass.onGlass(dark)
                    )
                )
            },
            bottomBar = { GlassNavBar(dark, tab) { tab = it } }
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (tab) {
                    0 -> HomeScreen(state, vm)
                    1 -> QueueScreen(state)
                    2 -> HistoryScreen(state, vm)
                    else -> SettingsScreen(state, vm)
                }
            }
        }
    }
}

/** 胶囊形柔光玻璃底栏：选中项是主题色玻璃胶囊，未选中透明。 */
@Composable
private fun GlassNavBar(dark: Boolean, tab: Int, onSelect: (Int) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 24.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .shadow(10.dp, Glass.PillShape, clip = false, ambientColor = Color(0x12000000))
                .edgeGlass(
                    dark,
                    shape = Glass.PillShape,
                    centerTint = if (dark) Color.White.copy(alpha = 0.04f)
                    else Color.White.copy(alpha = 0.07f)
                )
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TAB_LABELS.forEachIndexed { i, label ->
                val selected = tab == i
                Row(
                    Modifier
                        .weight(1f)
                        .clip(Glass.PillShape)
                        .background(
                            if (selected) Glass.accentBrush else Brush.linearGradient(
                                listOf(Color.Transparent, Color.Transparent)
                            ),
                            Glass.PillShape
                        )
                        .clickable { onSelect(i) }
                        .padding(vertical = 9.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        TAB_ICONS[i],
                        color = if (selected) Color.White else Glass.onGlassSub(dark),
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        label,
                        color = if (selected) Color.White else Glass.onGlassSub(dark),
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 解析下载

@Composable
private fun HomeScreen(state: MainUiState, vm: MainViewModel) {
    var preview by remember { mutableStateOf<MediaResource?>(null) }

    // 稳定回调：ViewModel 实例不变时复用同一个 lambda，让子组件能跳过重组
    val onAnalyze = remember(vm) { { vm.analyze() } }
    val onDownload = remember(vm) { { vm.downloadSelected() } }
    val onClearAll = remember(vm) { { vm.clearAll() } }
    val onClearMessage = remember(vm) { { vm.clearMessage() } }
    val onClearResults = remember(vm) { { vm.clearResults() } }
    val onPreview: (MediaResource) -> Unit = remember { { preview = it } }
    val onDismissPreview = remember { { preview = null } }

    // LazyColumn 只组合可见项：粘贴多条链接时不再一次性把所有缩略图全量组合，
    // 滚动/选中/输入时的重组范围大幅缩小，配合高刷与 AOT 消除掉帧。
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "input_header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("粘贴分享链接", style = MaterialTheme.typography.titleMedium,
                        color = Glass.onGlass(isSystemInDarkTheme()))
                    Text("可一次贴多条口令 · 自动用话题 / 文案命名",
                        style = MaterialTheme.typography.labelSmall,
                        color = Glass.onGlassSub(isSystemInDarkTheme()))
                }
                TextButton(
                    onClick = onClearAll,
                    enabled = state.input.isNotEmpty() || state.results.isNotEmpty()
                ) { Text("清空") }
            }
        }

        item(key = "input_field") {
            val dark = isSystemInDarkTheme()
            // 中心 100% 透明：只保留轮廓边缘的折射柔光带与镜面高光，不铺任何半透明白基底。
            // 旧版把 baseBrush 铺满整块、又叠了 decorationBox 容器，在浅色渐变上
            // 显出一块边界清晰的浅白矩形（真机截图里的「白色方框」）；现在结构性消除：
            //   edgeGlass 中心全透 + 去掉 decorationBox + BasicTextField 显式透明背景。
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 112.dp)
                    .edgeGlass(dark, shape = Glass.CardShape)
                    .padding(horizontal = 18.dp, vertical = 16.dp)
            ) {
                if (state.input.isEmpty()) {
                    Text(
                        "抖音 / 小红书 / 任意网页链接，可粘贴 2~3 条整段口令",
                        color = Glass.onGlassSub(dark),
                        fontSize = 14.sp,
                        lineHeight = 23.sp
                    )
                }
                BasicTextField(
                    value = state.input,
                    onValueChange = vm::onInputChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp)
                        .background(Color.Transparent),
                    enabled = !state.isAnalyzing && !state.isDownloading,
                    maxLines = 8,
                    textStyle = TextStyle(
                        color = Glass.onGlass(dark),
                        fontSize = 15.sp,
                        lineHeight = 23.sp
                    ),
                    cursorBrush = SolidColor(Glass.Accent)
                )
            }
        }

        item(key = "action_row") {
            val dark = isSystemInDarkTheme()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // 胶囊渐变主按钮：柔和大阴影 + 主题色渐变，HyperOS 胶囊哲学
                Box(
                    Modifier
                        .weight(1f)
                        .height(50.dp)
                        .shadow(14.dp, Glass.PillShape, clip = false,
                            ambientColor = Glass.Accent.copy(alpha = 0.35f),
                            spotColor = Glass.Accent.copy(alpha = 0.35f))
                        .liquidButton(Glass.accentBrush, Glass.PillShape)
                        .clickable(enabled = !state.isAnalyzing && !state.isDownloading) { onAnalyze() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (state.isAnalyzing) "解析中…" else "① 解析",
                        color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .height(50.dp)
                        .shadow(10.dp, Glass.PillShape, clip = false, ambientColor = Color(0x14000000))
                        .liquidButton(Glass.accentBrushMint, Glass.PillShape)
                        .clickable(enabled = state.results.isNotEmpty() && !state.isDownloading) { onDownload() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (state.isDownloading) "下载中…" else "② 下载选中 ${state.selectedIds.size}",
                        color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }

        if (state.isAnalyzing || state.isDownloading) {
            item(key = "progress") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }

        itemsIndexed(
            items = state.results,
            // 稳定 key：解析结果顺序变化或勾选状态变化时复用同一张卡组合
            key = { _, p -> "result_${p.itemId.ifBlank { p.sourceUrl }}" }
        ) { i, p ->
            ResultCard(i, p, state, vm, onPreview, onClearResults)
        }

        if (state.message.isNotEmpty()) {
            item(key = "message") {
                // 消息条保留语义色（错误红/成功青），保证可读性；只套大圆角玻璃描边
                val dark = isSystemInDarkTheme()
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (state.isError) MaterialTheme.colorScheme.errorContainer
                        else Color.Transparent
                    ),
                    shape = Glass.TileShape,
                    modifier = Modifier.fillMaxWidth()
                        .glassSurface(dark, shape = Glass.TileShape)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(state.message, style = MaterialTheme.typography.bodySmall,
                            color = Glass.onGlass(dark))
                        Row {
                            TextButton(onClick = onClearMessage) { Text("关闭") }
                            if (state.results.isNotEmpty()) {
                                TextButton(onClick = onClearResults) { Text("清空结果") }
                            }
                        }
                    }
                }
            }
        }

        item(key = "footer") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text("支持：${vm.supportedPlatforms}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "点缩略图直接预览；轮播动图点开就播放、播完自动暂停；右上角圆圈点一下选中、再点取消。" +
                        "记录页里的动态照片可在 App 内直接播放内嵌视频。文件名默认按文案 / 话题 + 短码去重，" +
                        "可在「设置 → 命名方案」里自由组合日期、作者、序号等字段。单张图文作品不会误报动图。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Glass.onGlassSub(isSystemInDarkTheme())
                )
                Text(
                    "仅供保存自己有权保存的内容；请遵守平台条款与适用法律。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    preview?.let { r -> PreviewDialog(r, onDismissPreview) }
}

@Composable
private fun ResultCard(
    index: Int,
    p: ParsedMedia,
    state: MainUiState,
    vm: MainViewModel,
    onPreview: (MediaResource) -> Unit,
    onClearResults: () -> Unit
) {
    val all = p.resources
    // 只在 selectedIds 变化时重算，避免输入文字等无关状态改动触发整卡重组
    val allSelected by remember(p, state.selectedIds) {
        derivedStateOf { all.isNotEmpty() && all.all { it.url in state.selectedIds } }
    }
    val onToggleAll = remember(vm, index) { { vm.toggleAllIn(index) } }
    val onSetExtractAudio: (Boolean) -> Unit = remember(vm) { { v: Boolean -> vm.setExtractAudio(v) } }
    val onSelectAll = remember(vm) { { vm.selectAll() } }
    val onSelectNone = remember(vm) { { vm.selectNone() } }
    // 分组按 resources 引用缓存，勾选状态变化不重复切分列表
    val groups = remember(p.resources) {
        listOf(
            "图文" to p.byKind(ResourceKind.IMAGE),
            "视频" to p.videos,
            "音频" to p.audios,
            // 只有多图作品才有本地合成的轮播动图；明确标注「合成」，
            // 不暗示作品本身带 GIF 动图（用户明确反馈不该假识别）。
            "轮播动图（合成）" to p.byKind(ResourceKind.ANIMATED)
        ).filter { it.second.isNotEmpty() }
    }

    val dark = isSystemInDarkTheme()
    GlassBox(
        dark = dark,
        modifier = Modifier
            .fillMaxWidth()
            .shadow(12.dp, Glass.CardShape, clip = false, ambientColor = Color(0x14000000))
            .padding(1.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 标题行 + 右上角全选方框 + 清空
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("【${p.type.label}】${p.title}", fontWeight = FontWeight.Bold,
                        color = Glass.onGlass(dark))
                    if (p.author.isNotBlank()) {
                        Text("作者：${p.author}", style = MaterialTheme.typography.bodySmall,
                            color = Glass.onGlassSub(dark))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = allSelected, onCheckedChange = { onToggleAll() })
                    Text("全选", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = onClearResults) { Text("清空") }
            }

            // 解析后是否提取音频
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("提取音频（视频无损抽音轨）", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall)
                Switch(checked = state.extractAudio, onCheckedChange = onSetExtractAudio)
            }

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
                                onToggle = remember(vm, r.url) { { vm.toggle(r.url) } },
                                onPreview = remember(r, onPreview) { { onPreview(r) } }
                            )
                        }
                        repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f).padding(4.dp)) }
                    }
                }
            }

            when {
                p.isLivePhoto -> Text("检测到实况图 · 将合成为小米动态照片",
                    style = MaterialTheme.typography.bodySmall,
                    color = Glass.Accent)
                // 诚实标注：只在真有多张静态帧时提供「合成」动图，
                // 单图作品不会出现这一栏，也不宣称「识别到动图」。
                p.resources.any { it.isLocalCompose } ->
                    Text("多图作品 · 可将静态帧合成为轮播 GIF / 小米动态照片（本地生成，非平台动图）",
                        style = MaterialTheme.typography.bodySmall,
                        color = Glass.Accent)
                p.livePhotoWork -> Text("单张图文 + 原声 · 本作品不含动图，静态图与原声可下载",
                    style = MaterialTheme.typography.bodySmall,
                    color = Glass.onGlassSub(dark))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onSelectAll) { Text("全部作品全选") }
                TextButton(onClick = onSelectNone) { Text("全不选") }
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
    // 缩略图限制解码尺寸：原图可达 1660×2142（解码后约 14MB/张），
    // 多张同时解码会引发大量 GC 造成滚动掉帧。缩略图只需 320px 级别。
    val ctx = LocalContext.current
    // 本地合成动图的 url 是伪地址，缩略图用首帧直链
    val thumb = r.thumbnailUrl.ifBlank { r.url }
    val imageRequest = remember(thumb) {
        ImageRequest.Builder(ctx)
            .data(thumb)
            .size(320)
            .crossfade(false)
            .build()
    }

    val dark = isSystemInDarkTheme()
    Column(
        modifier = Modifier
            .weight(1f)
            .padding(4.dp)
            // graphicsLayer 的 lambda 形式在绘制阶段读取 selected，选中变化不触发重组
            .graphicsLayer {
                scaleX = if (selected) 1.04f else 1f
                scaleY = if (selected) 1.04f else 1f
            }
            .shadow(if (selected) 14.dp else 6.dp, Glass.TileShape, clip = false,
                ambientColor = if (selected) Glass.Accent.copy(alpha = 0.3f) else Color(0x14000000))
            .glassSurface(dark, selected, Glass.TileShape)
            .clip(Glass.TileShape)
            .clickable(onClick = onPreview)
    ) {
        Box {
            if (r.isImage || r.kind == ResourceKind.COVER || r.kind == ResourceKind.ANIMATED) {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = r.label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(110.dp)
                        .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                )
            } else {
                Box(
                    Modifier.fillMaxWidth().height(110.dp)
                        .background(Glass.surfaceBrush(dark)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (r.isVideo) "▶ 视频" else "♫ 音频",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Glass.onGlassSub(dark))
                }
            }

            // 右上角圆形选中按钮：选中用主题色渐变 + 对勾，未选中是磨砂空心圆
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(if (selected) Glass.accentBrush else Brush.linearGradient(
                        listOf(Scrim, Scrim)), CircleShape)
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
                color = Glass.onGlassSub(dark),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), maxLines = 1)
        }
    }
}

/** 点开直接看：动图轮播（播完自动暂停）、静态图全屏、视频/音频交给系统播放器。 */
@Composable
private fun PreviewDialog(r: MediaResource, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        when {
            r.isLocalCompose && r.composeFrames.isNotEmpty() -> AnimatedFramesPreview(r, onDismiss)
            r.isVideo || r.isAudio -> ExternalPlayerPreview(r, onDismiss)
            else -> StaticImagePreview(r, onDismiss)
        }
    }
}

/**
 * 本地合成动图预览：点开（或点画面）就按帧轮播，播完一轮自动暂停、不循环。
 * 帧本来就是公开页合法取得的静态原图直链，预览无需先编码 GIF，点一下立刻就能动。
 */
@Composable
private fun AnimatedFramesPreview(r: MediaResource, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val frames = r.composeFrames
    // 整轮控制在 2~4 秒：3 帧约 2.7s，9 帧约 3.6s，观感接近真机动态照片
    val perFrameMs = (3600L / frames.size.coerceAtLeast(1)).coerceIn(200L, 900L)
    var frame by remember(frames) { mutableStateOf(0) }
    var playing by remember(frames) { mutableStateOf(false) }
    var finished by remember(frames) { mutableStateOf(false) }

    LaunchedEffect(playing, frames) {
        if (!playing) return@LaunchedEffect
        finished = false
        frame = 0
        for (i in frames.indices) {
            frame = i
            delay(perFrameMs)
        }
        // 只播一次：结束后停在最后一帧，即「播放完就暂停」
        playing = false
        finished = true
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AsyncImage(
            model = remember(frames, frame) {
                ImageRequest.Builder(ctx).data(frames[frame]).crossfade(false).build()
            },
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().clickable { playing = true }
        )
        TextButton(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
        ) { Text("✕ 关闭", color = Color.White) }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                when {
                    playing -> "播放中 · 第 ${frame + 1}/${frames.size} 帧"
                    finished -> "播放完毕 · 已暂停（停在最后一帧）"
                    else -> "▶ 点画面播放动图 · ${frames.size} 帧轮播"
                },
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { playing = true }) {
                Text(if (finished) "↻ 再播一次" else "▶ 播放", color = Color.White)
            }
        }
    }
}

@Composable
private fun ExternalPlayerPreview(r: MediaResource, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(r.url) {
        openExternal(ctx, r.url, if (r.isVideo) "video/*" else "audio/*")
        onDismiss()
    }
    Box(Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center) {
        Text("正在打开播放器…", color = Color.White)
    }
}

@Composable
private fun StaticImagePreview(r: MediaResource, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AsyncImage(
            model = r.thumbnailUrl.ifBlank { r.url },
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
    val dark = isSystemInDarkTheme()
    GlassBox(dark, modifier = Modifier.fillMaxWidth().padding(1.dp), shape = Glass.TileShape) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(q.label, fontWeight = FontWeight.SemiBold, color = Glass.onGlass(dark))
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
    // 动态照片走 App 内无损播放（读 XMP 定位尾部 MP4），其余仍交给系统相册
    var motionPreview by remember { mutableStateOf<Uri?>(null) }
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
                    HistoryCard(e, fmt) {
                        if (isMotionPhotoEntry(e) && e.previewUri.isNotBlank()) {
                            motionPreview = Uri.parse(e.previewUri)
                        } else {
                            openSaved(ctx, e)
                        }
                    }
                }
            }
        }
    }
    motionPreview?.let { u -> LocalMotionPhotoDialog(u) { motionPreview = null } }
}

/** 文件名带 MVIMG_ 前缀或「动态照片」字样的，视为动态照片，走 App 内播放。 */
private fun isMotionPhotoEntry(e: HistoryEntry): Boolean =
    e.mime.startsWith("image/") &&
        (e.fileName.startsWith("MVIMG_", ignoreCase = true) || e.fileName.contains("动态照片"))

@Composable
private fun HistoryCard(e: HistoryEntry, fmt: SimpleDateFormat, onOpen: () -> Unit) {
    val dark = isSystemInDarkTheme()
    GlassBox(
        dark,
        shape = Glass.TileShape,
        modifier = Modifier.fillMaxWidth().padding(1.dp)
            .clip(Glass.TileShape).clickable { onOpen() }
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(e.fileName, fontWeight = FontWeight.SemiBold,
                    color = Glass.onGlass(dark), modifier = Modifier.weight(1f))
                if (e.previewUri.isNotBlank()) {
                    Text("预览 ›", style = MaterialTheme.typography.labelSmall,
                        color = Glass.Accent)
                }
            }
            Text(e.detail, style = MaterialTheme.typography.bodySmall,
                color = Glass.onGlassSub(dark))
            Text(e.savedTo, style = MaterialTheme.typography.labelSmall,
                color = Glass.onGlassSub(dark))
            Text(fmt.format(Date(e.timestamp)), style = MaterialTheme.typography.labelSmall,
                color = Glass.onGlassSub(dark))
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
    var naming by remember { mutableStateOf(false) }
    if (advanced) {
        AdvancedSettingsScreen(state, vm) { advanced = false }
        return
    }
    if (naming) {
        NamingSettingsScreen(state, vm) { naming = false }
        return
    }
    val dark = isSystemInDarkTheme()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("设置", style = MaterialTheme.typography.titleLarge, color = Glass.onGlass(dark))
        GlassBox(dark, modifier = Modifier.fillMaxWidth().padding(1.dp), shape = Glass.TileShape) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("MotionSaver 2.7.1 · 无缝玻璃版", fontWeight = FontWeight.Bold,
                    color = Glass.onGlass(dark))
                Text(
                    "粘贴链接 → 解析 → 点缩略图预览 → 圆圈选中 → 下载。" +
                        "解析结果页可直接切换「提取音频」与全选。\n" +
                        "iOS 26 液态玻璃 + HyperOS 生命感美学：边缘折射、镜面高光、菲涅尔内发光；" +
                        "命名方案可自由组合。单图作品不再误报动图。release：R8 全量优化 + Baseline Profile AOT。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Glass.onGlassSub(dark)
                )
            }
        }
        Button(onClick = { naming = true }, Modifier.fillMaxWidth()) {
            Text("命名方案 →  自由组合日期 / 作者 / 序号 / 标题")
        }
        Button(onClick = { advanced = true }, Modifier.fillMaxWidth()) {
            Text("高级设置 →")
        }
        Text(
            "命名方案里可排列组合日期、时间、序号、标题、作者、平台、作品 ID、短码等字段并实时预览；" +
                "端点配置、字段映射、并发数、保存行为、动态照片自检等收在高级设置里。",
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
            val dark = isSystemInDarkTheme()
            GlassBox(dark, modifier = Modifier.fillMaxWidth().padding(1.dp), shape = Glass.TileShape) {
                Text(state.selfCheck, Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Glass.onGlass(dark))
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text("关于", style = MaterialTheme.typography.titleMedium)
        InfoRow("解析方式", "公开页访客渲染数据 / JSON-LD / og:* 三层兜底；不构造私有签名、不绕过鉴权")
        InfoRow("画质", "图集自动选最高质量 JPEG 候选，排除水印与缩放模板；多 CDN 直链自动回退")
        InfoRow("动态照片", "JPEG + 尾部 MP4 + XMP 偏移元数据，字节级无损拼接")
        InfoRow("架构", "core 纯 JVM 核心库（单测全绿）+ app Compose 层；arm64-v8a；minSdk 26")
        InfoRow("性能", "release + R8 全量优化 + Baseline Profile AOT；请求设备高刷新率；液态玻璃为静态渐变、drawWithCache 缓存画笔、零逐帧模糊")
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
