package com.motionsaver.app.data

import android.content.Context
import com.motionsaver.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File

/**
 * 编排：解析 → 下载 → （实况图才）无损合成动态照片 → 按作品标题命名 → 写入系统相册。
 *
 * 命名规则由 core 层的 FileNameAllocator / TitleSanitizer 决定，与平台无关、已单元测试覆盖。
 */
class SaveMediaUseCase(
    private val context: Context,
    private val client: OkHttpClient
) {
    private val downloader = Downloader(client)
    private val saver = MediaStoreSaver(context)
    private val registry = ParserRegistry()

    sealed interface Outcome {
        data class Success(val fileName: String, val savedTo: String, val detail: String) : Outcome
        data class Failure(val reason: String) : Outcome
        data class NeedAttention(val warnings: List<String>) : Outcome
    }

    suspend fun run(
        shareText: String,
        existingNames: Set<String>,
        onProgress: (Downloader.Progress) -> Unit = {}
    ): Outcome = withContext(Dispatchers.IO) {
        try {
            val fetcher = OkHttpFetcher(client)
            val parsed = registry.parse(shareText, fetcher)
            if (parsed.warnings.isNotEmpty()) {
                return@withContext Outcome.NeedAttention(parsed.warnings)
            }

            val cache = File(context.cacheDir, "ms_dl").apply { mkdirs() }
            val result = if (parsed.isLivePhoto) {
                saveLivePhoto(parsed, cache, existingNames, onProgress)
            } else {
                saveRegular(parsed, cache, existingNames, onProgress)
            }
            result
        } catch (e: ParseException) {
            Outcome.Failure(e.message ?: "解析失败")
        } catch (e: Exception) {
            Outcome.Failure("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** 实况图：下载封面 JPEG + 动态 MP4 → 合成单个 .jpg 动态照片 → 相册。 */
    private suspend fun saveLivePhoto(
        parsed: ParsedMedia,
        cache: File,
        existing: Set<String>,
        onProgress: (Downloader.Progress) -> Unit
    ): Outcome {
        val cover = parsed.liveCover ?: return Outcome.Failure("缺少动态照片封面")
        val video = parsed.liveVideo ?: return Outcome.Failure("缺少动态片段")

        val coverFile = downloader.download(cover.url, cover.headers, File(cache, "cover.jpg"), onProgress)
        val videoFile = downloader.download(video.url, video.headers, File(cache, "clip.mp4"), onProgress)

        val jpeg = coverFile.readBytes()
        val mp4 = videoFile.readBytes()
        val composed = MotionPhotoWriter.compose(jpeg, mp4)
        val check = MotionPhotoVerifier.verify(composed.bytes)
        if (!check.valid) return Outcome.Failure("动态照片自检失败：${check.problems.joinToString()}")

        val name = FileNameAllocator.allocate(parsed.title, "jpg", existing)
        val tmp = File(cache, name).apply { writeBytes(composed.bytes) }
        val saved = saver.save(tmp, name, "image/jpeg", isVideo = false)
        tmp.delete(); coverFile.delete(); videoFile.delete()

        return Outcome.Success(
            fileName = name,
            savedTo = saved,
            detail = "小米动态照片 · 单文件 ${composed.bytes.size} 字节（JPEG ${composed.jpegLength} + MP4 ${composed.videoLength}，无损）"
        )
    }

    /** 普通视频 / 图集：按标题命名直接落盘。 */
    private suspend fun saveRegular(
        parsed: ParsedMedia,
        cache: File,
        existing: Set<String>,
        onProgress: (Downloader.Progress) -> Unit
    ): Outcome {
        val first = parsed.resources.firstOrNull()
            ?: return Outcome.Failure("公开页未暴露可下载资源")
        val ext = if (first.mime.startsWith("video")) "mp4" else "jpg"
        val name = FileNameAllocator.allocate(parsed.title, ext, existing)
        val tmp = downloader.download(first.url, first.headers, File(cache, name), onProgress)
        val size = tmp.length()
        val saved = saver.save(tmp, name, first.mime, isVideo = ext == "mp4")
        tmp.delete()
        return Outcome.Success(name, saved, "${parsed.platform.label} · ${parsed.type} · $size 字节")
    }
}
