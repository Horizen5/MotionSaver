package com.motionsaver.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.motionsaver.app.HistoryEntry
import com.motionsaver.app.MainUiState
import com.motionsaver.app.MainViewModel
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
                listOf("解析下载", "下载记录", "设置").forEachIndexed { i, label ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        label = { Text(label) },
                        icon = { Text(if (i == 0) "↓" else if (i == 1) "≡" else "⚙") }
                    )
                }
            }
        }
    ) { padding ->
        Surface(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> HomeScreen(state, vm)
                1 -> HistoryScreen(state)
                else -> SettingsScreen(vm)
            }
        }
    }
}

@Composable
private fun HomeScreen(state: MainUiState, vm: MainViewModel) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("粘贴分享链接", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = state.input,
            onValueChange = vm::onInputChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
            placeholder = { Text("抖音 / 小红书分享链接，或直接粘贴整段口令") },
            enabled = !state.isBusy,
            maxLines = 6
        )

        Button(
            onClick = vm::submit,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isBusy
        ) {
            Text(if (state.isBusy) "处理中…" else "解析并下载")
        }

        if (state.progressText.isNotEmpty()) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(state.progressText, style = MaterialTheme.typography.bodySmall)
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
                    Text(state.message, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = vm::clearMessage) { Text("关闭") }
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("当前支持：${vm.supportedPlatforms}", style = MaterialTheme.typography.bodySmall)
        Text(
            "实况图会合成小米可识别的单文件动态照片（.jpg，长按播放）；" +
                "视频与图片按作品标题自动命名。知乎暂未接入。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "仅供个人学习研究与保存自己有权保存的内容；请遵守平台条款与著作权法。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HistoryScreen(state: MainUiState) {
    if (state.history.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Text("还没有下载记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(state.history) { e ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(e.fileName, fontWeight = FontWeight.SemiBold)
                    Text(e.detail, style = MaterialTheme.typography.bodySmall)
                    Text(e.savedTo, style = MaterialTheme.typography.labelSmall)
                    Text(fmt.format(Date(e.timestamp)), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(vm: MainViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("关于与边界", style = MaterialTheme.typography.titleMedium)
        InfoRow("动态照片", "小米 / Google Motion Photo 单文件标准；JPEG + 尾部 MP4 + XMP 偏移元数据，无损不重编码")
        InfoRow("文件命名", "取作品标题，清洗 emoji 与非法字符、折叠空白、超长截断、重名自动加序号")
        InfoRow("解析方式", "只读取公开分享页元信息（og:* / JSON-LD），不构造平台私有签名、不绕过鉴权")
        InfoRow("平台", "抖音优先，小红书次之；解析器可插拔，知乎暂停")
        InfoRow("架构", "core 纯 JVM 核心库 + app Compose 层；arm64-v8a 单架构；minSdk 26")
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(
            "合规说明：本工具不破解任何签名算法、不请求非公开接口、不预置第三方解析端点。" +
                "公开页未暴露直链时会明确告知，需由使用者接入自己的合规数据源。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun InfoRow(k: String, v: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(k, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        Text(v, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
