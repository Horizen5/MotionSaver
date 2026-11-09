package com.motionsaver.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motionsaver.app.MainUiState
import com.motionsaver.app.MainViewModel
import com.motionsaver.core.NameTemplate
import com.motionsaver.core.NameToken

/**
 * 「设置 → 命名方案」独立页：Miuix SmallTitle + Card 分组。
 * 用户自由勾选字段、调整顺序、选分隔符，并实时预览生成的文件名。
 * 所有组合都走 core 层 NameTemplate，落盘名始终合法、限长、去重可复现。
 * 本版只改外观容器，字段 / 预设 / 预览 / 保存逻辑与上一版完全一致。
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
            .padding(horizontal = 16.dp)
    ) {
        MiuixPageTitle(
            "命名方案",
            "勾选字段并调整顺序，下载的文件名就按这个顺序生成",
            onBack = onBack
        )
        Text(
            "字段为空时自动跳过；默认带 6 位短码，不同作品不会堆叠覆盖。",
            fontSize = 12.5.sp, color = Glass.onGlassSub(dark),
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp)
        )

        // ---- 快捷预设 ----
        MiuixSmallTitle("快捷预设")
        MiuixCard {
            NameTemplate.PRESETS.chunked(2).forEach { row ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { (label, pattern, sep) ->
                        Button(
                            onClick = {
                                tokens = NameTemplate.parse(pattern)
                                separator = sep
                            },
                            modifier = Modifier.weight(1f),
                            shape = Glass.PillShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Glass.fieldColor(dark),
                                contentColor = Glass.onGlass(dark)
                            )
                        ) { Text(label, fontSize = 12.sp) }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        // ---- 分隔符 ----
        MiuixSmallTitle("字段分隔符")
        MiuixCard {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                NameTemplate.SEPARATORS.chunked(3).forEach { row ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        row.forEach { (value, label) ->
                            FilterChip(
                                selected = separator == value,
                                onClick = { separator = value },
                                label = { Text(label, fontSize = 11.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }

        // ---- 可用字段 ----
        MiuixSmallTitle("可用字段（点击添加）")
        MiuixCard {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                NameToken.entries.chunked(3).forEach { row ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
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
            }
        }

        // ---- 当前顺序 ----
        MiuixSmallTitle("当前顺序（↑ ↓ 调整，✕ 移除）")
        MiuixCard {
            Column(Modifier.padding(vertical = 4.dp)) {
                if (tokens.isEmpty()) {
                    Text(
                        "还没有字段，点上方字段添加；保存时会自动恢复默认组合。",
                        fontSize = 12.5.sp, color = Glass.onGlassSub(dark),
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                    )
                }
                tokens.forEachIndexed { i, t ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${i + 1}. ${t.label}", Modifier.weight(1f),
                            fontSize = 15.sp, color = Glass.onGlass(dark))
                        TextButton(onClick = {
                            if (i > 0) tokens = tokens.toMutableList().also { it.swap(i, i - 1) }
                        }, enabled = i > 0) { Text("↑", color = Glass.Accent) }
                        TextButton(onClick = {
                            if (i < tokens.lastIndex) tokens = tokens.toMutableList().also { it.swap(i, i + 1) }
                        }, enabled = i < tokens.lastIndex) { Text("↓", color = Glass.Accent) }
                        TextButton(onClick = { tokens = tokens - t }) { Text("✕", color = Glass.Accent) }
                    }
                    Text(t.hint, fontSize = 11.5.sp, color = Glass.onGlassSub(dark),
                        modifier = Modifier.padding(start = 30.dp, bottom = 6.dp))
                }
            }
        }

        // ---- 实时预览 ----
        MiuixSmallTitle("实时预览")
        MiuixCard {
            val base = NameTemplate.preview(tokens, separator, 0)
            val series = NameTemplate.preview(tokens, separator, 1)
            MiuixInfoRow("图片：$base.jpg", "")
            MiuixInfoRow("图集：$series.jpg", "")
            MiuixInfoRow("正文：$base-正文.txt", "")
        }

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    tokens = NameTemplate.default()
                    separator = NameTemplate.DEFAULT_SEPARATOR
                },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = Glass.PillShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Glass.fieldColor(dark),
                    contentColor = Glass.onGlass(dark)
                )
            ) { Text("恢复默认", fontWeight = FontWeight.SemiBold) }
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
                modifier = Modifier.weight(1f).height(48.dp),
                shape = Glass.PillShape,
                colors = ButtonDefaults.buttonColors(containerColor = Glass.Accent)
            ) { Text("保存方案", color = Color.White, fontWeight = FontWeight.SemiBold) }
        }
        Spacer(Modifier.height(20.dp))
    }
}

private fun <T> MutableList<T>.swap(a: Int, b: Int) {
    val tmp = this[a]
    this[a] = this[b]
    this[b] = tmp
}
