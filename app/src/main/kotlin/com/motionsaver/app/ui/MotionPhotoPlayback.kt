package com.motionsaver.app.ui

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.MediaPlayer
import android.net.Uri
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.motionsaver.core.MotionPhotoLayout
import com.motionsaver.core.MotionPhotoReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 在 App 内直接播放本地「动态照片」（含用户手机里 MVIMG_*.jpg 这类真机文件）。
 *
 * 流程：读 XMP → 算出尾部 MP4 的起点与长度 → 用
 * `MediaPlayer.setDataSource(fd, offset, length)` 从**原文件的偏移处**播放内嵌视频。
 * 不切文件、不重编码，逐字节无损；即使 XMP 被转发剥掉也会回退到 ftyp 扫描。
 *
 * 播放行为按需求固定：打开即播一次、**不循环**，播放结束停在最后一帧（播完自动暂停），
 * 可点「重播」再看一遍。
 */
@Composable
fun LocalMotionPhotoDialog(uri: Uri, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var layout by remember(uri) { mutableStateOf<MotionPhotoLayout?>(null) }
    var error by remember(uri) { mutableStateOf("") }
    var status by remember(uri) { mutableStateOf("正在读取动态照片…") }
    var replayKey by remember(uri) { mutableStateOf(0) }

    LaunchedEffect(uri) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val afd = ctx.contentResolver.openAssetFileDescriptor(uri, "r")
                val total = if (afd != null && afd.length >= 0) afd.length else {
                    ctx.contentResolver.openInputStream(uri)?.use { ins ->
                        val buf = ByteArray(64 * 1024)
                        var n = 0L
                        while (true) {
                            val r = ins.read(buf)
                            if (r < 0) break
                            n += r
                        }
                        n
                    } ?: -1L
                }
                afd?.close()
                require(total > 0) { "无法读取文件大小" }

                val head = ByteArray(minOf(total, 256L * 1024).toInt())
                val n = ctx.contentResolver.openInputStream(uri)?.use { it.read(head) } ?: -1
                require(n > 0) { "无法读取文件头" }

                // 先只读头部解析；XMP 缺失时再读全文件做 ftyp 扫描兜底
                MotionPhotoReader.parseHeader(head.copyOf(n), total)
                    ?: ctx.contentResolver.openInputStream(uri)?.use { MotionPhotoReader.parse(it.readBytes()) }
                    ?: error("没有找到动态照片元数据，可能不是动态照片")
            }
        }
        result.onSuccess {
            layout = it
            status = "动态照片 · 内嵌 ${it.videoLength / 1024} KB 视频（${it.source}）"
        }.onFailure {
            error = it.message ?: "解析失败"
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when {
                error.isNotEmpty() -> Text(
                    error, color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
                layout == null -> Text(
                    status, color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
                else -> key(replayKey) {
                    MotionPhotoSurface(ctx, uri, layout!!) { status = it }
                }
            }

            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
            ) { Text("✕ 关闭", color = Color.White) }

            if (error.isEmpty()) {
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        status, color = Color.White,
                        modifier = Modifier.weight(1f)
                    )
                    if (layout != null) {
                        TextButton(onClick = { replayKey++ }) {
                            Text("↻ 重播", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

/** SurfaceView + MediaPlayer：从原 JPEG 文件的偏移处播放尾部 MP4，只播一次。 */
@Composable
private fun MotionPhotoSurface(
    ctx: Context,
    uri: Uri,
    layout: MotionPhotoLayout,
    onStatus: (String) -> Unit
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { c ->
            SurfaceView(c).apply {
                var afd: AssetFileDescriptor? = null
                var player: MediaPlayer? = null
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) {
                        runCatching {
                            afd = ctx.contentResolver.openAssetFileDescriptor(uri, "r")
                                ?: error("无法打开文件")
                            player = MediaPlayer().apply {
                                setDataSource(
                                    afd!!.fileDescriptor,
                                    afd!!.startOffset + layout.videoOffset,
                                    layout.videoLength
                                )
                                setDisplay(h)
                                // 关键：不循环，播完停在最后一帧 = 自动暂停
                                isLooping = false
                                setOnPreparedListener {
                                    start()
                                    onStatus("播放中 · ${String.format("%.1f", it.duration / 1000.0)} 秒")
                                }
                                setOnCompletionListener {
                                    onStatus("播放完毕 · 已暂停（停在最后一帧）")
                                }
                                setOnErrorListener { _, what, _ ->
                                    onStatus("播放失败 ($what)，可点「重播」重试")
                                    true
                                }
                                prepareAsync()
                            }
                        }.onFailure { onStatus("无法播放：${it.message}") }
                    }

                    override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, h2: Int) = Unit

                    override fun surfaceDestroyed(h: SurfaceHolder) {
                        runCatching { player?.release() }
                        player = null
                        runCatching { afd?.close() }
                        afd = null
                    }
                })
            }
        }
    )
}
