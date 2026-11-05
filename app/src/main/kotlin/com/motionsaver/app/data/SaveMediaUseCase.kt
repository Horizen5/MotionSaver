package com.motionsaver.app.data

import android.content.Context
import com.motionsaver.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File

/**
 * 全流程编排：解析 →（用户勾选）→ 批量下载 → 音频无损抽取 / 动态照片合成 → 标题命名 → 媒体库落盘。
 *
 * 四类资源（图文 / 视频 / 音频 / 动图）走同一条下载链路，只按 kind 决定落盘目录与扩展名。
 * 命名规则在 core 层，已由 JVM 单测覆盖。
 */
class SaveMediaUseCase(
    private val context: Context,
    private val client: OkHttpClient
) {
    private val downloader = Downloader(client)
    private val saver = MediaStoreSaver(context)

    sealed interface ParseOutcome {
        data class Ok(val media: ParsedMedia) : ParseOutcome
        data class Err(val reason: String) : ParseOutcome
    }

    data class SavedFile(
        val name: String,
        val location: String,
        val category: SaveCategory,
        val bytes: Long
    )

    sealed interface DownloadOutcome {
        data class Ok(val files: List<SavedFile>, val warnings: List<String>) : DownloadOutcome
        data class Err(val reason: String) : DownloadOutcome
    }

    data class DownloadEvent(
        val id: String,
        val label: String,
        val phase: String,
        val fraction: Float,
        val detail: String = ""
    )

    /** 只解析、不下载，让 UI 先把四类结果展示给用户勾选。 */
    fun analyze(shareText: String, settings: AppSettings): ParseOutcome {
        val registry = ParserRegistry(ParserRegistry.defaultParsers(settings.toEndpointSpec()))
        return try {
            ParseOutcome.Ok(registry.parse(shareText, OkHttpFetcher(client)))
        } catch (e: ParseException) {
            ParseOutcome.Err(e.message ?: "解析失败")
        } catch (e: Exception) {
            ParseOutcome.Err("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    suspend fun download(
        parsed: ParsedMedia,
        selected: List<MediaResource>,
        settings: AppSettings,
        existing: Set<String>,
        onEvent: (DownloadEvent) -> Unit
    ): DownloadOutcome = withContext(Dispatchers.IO) {
        if (selected.isEmpty() && !parsed.isLivePhoto && parsed.caption.isBlank()) {
            return@withContext DownloadOutcome.Err("没有勾选任何资源")
        }
        val cache = File(context.cacheDir, "ms_dl").apply { mkdirs() }
        val used = existing.toMutableSet()
        val saved = mutableListOf<SavedFile>()
        val failures = mutableListOf<String>()
        val imageCount = selected.count { it.isImage }

        // 1) 逐个下载用户勾选的资源
        selected.forEachIndexed { i, r ->
            val id = "r$i"
            val label = r.label.ifBlank { "资源 ${i + 1}" }
            try {
                val ext = r.suggestedExtension()
                val name = nameFor(parsed.title, r, ext, imageCount, used)
                val tmp = File(cache, "dl_$id.$ext")
                downloader.download(r.url, r.headers, tmp, label) { p ->
                    onEvent(
                        DownloadEvent(
                            id, label, "running", p.fraction,
                            "${p.downloaded / 1024} / ${p.total / 1024} KB"
                        )
                    )
                }

                // 音频解析补强：平台没给音频地址时从视频无损抽音轨
                if (settings.extractAudioFromVideo && r.kind == ResourceKind.VIDEO && parsed.audios.isEmpty()) {
                    val audioName = FileNameAllocator.allocateAuxiliary(parsed.title, "原声", "m4a", used)
                    val audio = AudioExtractor.extract(tmp, File(cache, "audio_$id"))
                    if (audio != null) {
                        val loc = saver.save(audio.file, audioName, "audio/mp4", SaveCategory.AUDIO)
                        used.add(audioName)
                        saved.add(SavedFile(audioName, loc, SaveCategory.AUDIO, audio.file.length()))
                        audio.file.delete()
                        onEvent(DownloadEvent("${id}_a", "$label · 原声", "done", 1f, "无损抽音轨"))
                    }
                }

                val category = when {
                    r.isVideo -> SaveCategory.VIDEO
                    r.isAudio -> SaveCategory.AUDIO
                    else -> SaveCategory.IMAGE
                }
                val location = saver.save(tmp, name, r.mime, category)
                used.add(name)
                saved.add(SavedFile(name, location, category, tmp.length()))
                tmp.delete()
                onEvent(DownloadEvent(id, label, "done", 1f, name))
            } catch (e: Exception) {
                failures.add("$label：${e.message}")
                onEvent(DownloadEvent(id, label, "failed", 0f, e.message ?: "失败"))
            }
        }

        // 2) 实况图 → 小米动态照片（单文件 JPG，无损拼接）
        if (settings.motionPhotoEnabled && parsed.isLivePhoto) {
            val cover = parsed.liveCover!!
            val video = parsed.liveVideo!!
            val id = "live"
            try {
                onEvent(DownloadEvent(id, "动态照片合成", "running", 0.1f, "下载封面与动态片段"))
                val cf = downloader.download(cover.url, cover.headers, File(cache, "live_cover.jpg"), "动态封面")
                val vf = downloader.download(video.url, video.headers, File(cache, "live_clip.mp4"), "动态片段")
                val composed = MotionPhotoWriter.compose(cf.readBytes(), vf.readBytes())
                val report = MotionPhotoVerifier.verify(composed.bytes)
                if (!report.valid) error("自检失败：${report.problems.joinToString()}")

                val name = FileNameAllocator.allocate(parsed.title, "jpg", used)
                val out = File(cache, name).apply { writeBytes(composed.bytes) }
                val location = saver.save(out, name, "image/jpeg", SaveCategory.IMAGE)
                used.add(name)
                saved.add(SavedFile(name, location, SaveCategory.IMAGE, out.length()))
                listOf(cf, vf, out).forEach { it.delete() }
                onEvent(
                    DownloadEvent(
                        id, "动态照片合成", "done", 1f,
                        "JPEG ${composed.jpegLength} + MP4 ${composed.videoLength} 字节，无损"
                    )
                )
            } catch (e: Exception) {
                failures.add("动态照片：${e.message}")
                onEvent(DownloadEvent(id, "动态照片合成", "failed", 0f, e.message ?: "失败"))
            }
        }

        // 3) 图文正文单独存一份 .txt
        if (settings.saveCaption && parsed.caption.isNotBlank()) {
            try {
                val name = FileNameAllocator.allocateAuxiliary(parsed.title, "正文", "txt", used)
                val f = File(cache, name).apply { writeText(parsed.caption, Charsets.UTF_8) }
                val location = saver.save(f, name, "text/plain", SaveCategory.TEXT)
                used.add(name)
                saved.add(SavedFile(name, location, SaveCategory.TEXT, f.length()))
                f.delete()
            } catch (e: Exception) {
                failures.add("正文保存：${e.message}")
            }
        }

        val warnings = parsed.warnings + failures.map { "部分失败 · $it" }
        if (saved.isEmpty()) {
            DownloadOutcome.Err((warnings + "没有任何文件成功保存").joinToString("\n"))
        } else {
            DownloadOutcome.Ok(saved, warnings)
        }
    }

    private fun nameFor(
        title: String,
        r: MediaResource,
        ext: String,
        imageCount: Int,
        used: Set<String>
    ): String =
        if (r.index > 0 && imageCount > 1) {
            FileNameAllocator.allocateSeries(title, ext, r.index - 1, imageCount, used)
        } else {
            FileNameAllocator.allocate(title, ext, used)
        }
}
