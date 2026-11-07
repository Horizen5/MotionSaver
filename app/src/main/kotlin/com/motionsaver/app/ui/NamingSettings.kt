package com.motionsaver.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motionsaver.app.MainUiState
import com.motionsaver.app.MainViewModel
import com.motionsaver.core.NameTemplate
import com.motionsaver.core.NameToken

/**
 * 「设置 → 命名方案」独立页：用户自由勾选字段、调整顺序、选分隔符，
 * 并实时预览生成的文件名。所有组合都走 core 层 NameTemplate，
 * 落盘名始终合法、限长、去重可复现。
 */
@Composable
fun NamingSettingsScreen(
    state: MainUiState,
    vm: MainViewModel,
    onBack: () -> Unit
) {
    val dark = isSystemInDarkTheme()
    var tokens by remember(state.settings) {
        mutableStateOf(NameTemplate.parse(state.settings.namingPattern))
    }
    var separator by remember(state.settings) {
        mutableStateOf(state.settings.namingSeparator)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Text("命名方案", style = MaterialTheme.typography.titleLarge,
                color = Glass.onGlass(dark))
        }
        Text(
            "勾选字段并调整顺序，下载的文件名就按这个顺序生成。字段为空时自动跳过；" +
                "默认带 6 位短码，不同作品不会堆叠覆盖。",
            style = MaterialTheme.typography.bodySmall,
            color = Glass.onGlassSub(dark)
        )

        // ---- 快捷预设 ----
        Text("快捷预设", style = MaterialTheme.typography.titleSmall,
            color = Glass.onGlass(dark))
        NameTemplate.PRESETS.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, pattern, sep) ->
                    OutlinedButton(
                        onClick = {
                            tokens = NameTemplate.parse(pattern)
                            separator = sep
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text(label, fontSize = 12.sp) }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        // ---- 分隔符 ----
        Text("字段分隔符", style = MaterialTheme.typography.titleSmall,
            color = Glass.onGlass(dark))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NameTemplate.SEPARATORS.chunked(3).forEach { row ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { (value, label) ->
                        FilterChip(
                            selected = separator == value,
                            onClick = { separator = value },
                            label = { Text(label, fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // ---- 可用字段 ----
        Text("可用字段（点击添加）", style = MaterialTheme.typography.titleSmall,
            color = Glass.onGlass(dark))
        NameToken.entries.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { t ->
                    AssistChip(
                        onClick = { tokens = tokens + t },
                        label = { Text(t.label, fontSize = 11.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        // ---- 当前顺序 ----
        Text("当前顺序（↑ ↓ 调整，✕ 移除）", style = MaterialTheme.typography.titleSmall,
            color = Glass.onGlass(dark))
        GlassBox(dark, modifier = Modifier.fillMaxWidth(), shape = Glass.TileShape) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (tokens.isEmpty()) {
                    Text("还没有字段，点上方字段添加；保存时会自动恢复默认组合。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Glass.onGlassSub(dark))
                }
                tokens.forEachIndexed { i, t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}. ${t.label}", Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Glass.onGlass(dark))
                        TextButton(onClick = {
                            if (i > 0) tokens = tokens.toMutableList().also { it.swap(i, i - 1) }
                        }, enabled = i > 0) { Text("↑") }
                        TextButton(onClick = {
                            if (i < tokens.lastIndex) tokens = tokens.toMutableList().also { it.swap(i, i + 1) }
                        }, enabled = i < tokens.lastIndex) { Text("↓") }
                        TextButton(onClick = { tokens = tokens - t }) { Text("✕") }
                    }
                    Text(t.hint, style = MaterialTheme.typography.labelSmall,
                        color = Glass.onGlassSub(dark),
                        modifier = Modifier.padding(start = 20.dp))
                }
            }
        }

        // ---- 实时预览 ----
        Text("实时预览", style = MaterialTheme.typography.titleSmall,
            color = Glass.onGlass(dark))
        GlassBox(dark, modifier = Modifier.fillMaxWidth(), shape = Glass.TileShape) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val base = NameTemplate.preview(tokens, separator, 0)
                val series = NameTemplate.preview(tokens, separator, 1)
                Text("图片：$base.jpg", style = MaterialTheme.typography.bodySmall,
                    color = Glass.onGlass(dark))
                Text("图集：$series.jpg", style = MaterialTheme.typography.bodySmall,
                    color = Glass.onGlass(dark))
                Text("正文：$base-正文.txt", style = MaterialTheme.typography.bodySmall,
                    color = Glass.onGlassSub(dark))
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = {
                    tokens = NameTemplate.default()
                    separator = NameTemplate.DEFAULT_SEPARATOR
                },
                modifier = Modifier.weight(1f)
            ) { Text("恢复默认") }
            Button(
                onClick = {
                    val finalTokens = tokens.ifEmpty { NameTemplate.default() }
                    vm.updateSettings(
                        state.settings.copy(
                            namingPattern = NameTemplate.encode(finalTokens),
                            namingSeparator = separator
                        )
                    )
                    onBack()
                },
                modifier = Modifier.weight(1f)
            ) { Text("保存方案") }
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun <T> MutableList<T>.swap(a: Int, b: Int) {
    val tmp = this[a]
    this[a] = this[b]
    this[b] = tmp
}
