package com.motionsaver.app.data

import android.content.Context
import com.motionsaver.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File

/**
 * 全流程编排：解析（支持多条链接）→ 用户勾选 → 批量下载 → 音频无损抽取 /
 * 动态照片合成 → 标题命名 → 媒体库落盘。
 *
 * 四类资源（图文 / 视频 / 音频 / 动图）走同一条下载链路，只按 kind 决定
 * 落盘目录与扩展名。命名规则在 core 层，已由 JVM 单测覆盖。
 */
class SaveMediaUseCase(
    private val context: Context,
    private val client: OkHttpClient
) {
    private val downloader = Downloader(client)
    private val saver = MediaStoreSaver(context)
    private val composer = LocalAnimatedComposer()

    sealed interface ParseOutcome {
        data class Ok(val media: ParsedMedia) : ParseOutcome
        data class Err(val reason: String) : ParseOutcome
    }

    /** 一次粘贴多条分享链接时，返回每个作品的解析结果。 */
    sealed interface ParseAllOutcome {
        data class Ok(val items: List<ParsedMedia>) : ParseAllOutcome
        data class Err(val reason: String) : ParseAllOutcome
    }

    data class SavedFile(
        val name: String,
        val location: String,
        val category: SaveCategory,
        val bytes: Long,
        /** 媒体库 content Uri，点开下载记录即可预览 */
        val previewUri: String = "",
        val mime: String = ""
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

    /** 支持 2~3 条链接一起粘贴：每条独立解析，失败的单条不影响其它。 */
    fun analyzeAll(shareText: String, settings: AppSettings): ParseAllOutcome {
        val registry = ParserRegistry(ParserRegistry.defaultParsers(settings.toEndpointSpec()))
        return try {
            val items = registry.parseAll(shareText, OkHttpFetcher(client))
            if (items.isEmpty()) ParseAllOutcome.Err("没有解析出任何作品，请检查链接是否有效")
            else ParseAllOutcome.Ok(items)
        } catch (e: ParseException) {
            ParseAllOutcome.Err(e.message ?: "解析失败")
        } catch (e: Exception) {
            ParseAllOutcome.Err("${e.javaClass.simpleName}: ${e.message}")
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
        // 落盘名按「设置 → 命名方案」里用户自由排列的字段生成；
        // 默认模板仍是「标题-短码」，保证不同作品不会互相覆盖、堆叠。
        // UI 上显示的始终是干净的 parsed.title（文案 / 话题）。
        val namingTokens = NameTemplate.parse(settings.namingPattern)
        val namingSep = settings.namingSeparator
        val nameCtx = NameContext(
            title = parsed.title,
            author = parsed.author.ifBlank { WorkTitle.authorOf(parsed.caption) },
            platform = parsed.platform.label,
            itemId = parsed.itemId,
            sourceUrl = parsed.sourceUrl,
            typeLabel = parsed.type.label,
            topics = WorkTitle.topics(parsed.caption).ifBlank { parsed.title }
        )
        val composedTitle = NameTemplate.format(namingTokens, namingSep, nameCtx)
        val fileTitle = composedTitle.ifBlank {
            FileNameAllocator.scopedTitle(parsed.title, parsed.itemId, parsed.sourceUrl)
        }
        /** 模板里含「序号」时由模板决定序号位置，不再额外追加 _01 后缀 */
        val hasIndexToken = namingTokens.contains(NameToken.INDEX)
        val saved = mutableListOf<SavedFile>()
        val failures = mutableListOf<String>()

        // 本地合成动图不走网络下载，单独处理；其余为真实直链资源
        val composeTasks = selected.filter { it.isLocalCompose }
        val realSelected = selected.filter { !it.isLocalCompose }
        val imageCount = realSelected.count { it.isImage }
        /** 已下载的静态帧，按 URL 复用，避免合成分支重复下载同一张图 */
        val framesByUrl = mutableMapOf<String, File>()

        // 1) 逐个下载用户勾选的资源；同一资源的备用直链会自动回退
        realSelected.forEachIndexed { i, r ->
            val id = "r$i"
            val label = r.label.ifBlank { "资源 ${i + 1}" }
            try {
                val ext = r.suggestedExtension()
                val name = nameFor(
                    fileTitle, r, ext, imageCount, used,
                    namingTokens, namingSep, nameCtx, hasIndexToken
                )
                val tmp = File(cache, "dl_$id.$ext")
                downloader.download(listOf(r.url) + r.alternatives, r.headers, tmp, label) { p ->
                    onEvent(
                        DownloadEvent(
                            id, label, "running", p.fraction,
                            "${p.downloaded / 1024} / ${p.total / 1024} KB"
                        )
                    )
                }

                // 用户勾选「提取音频」时，从视频无损抽音轨
                if (settings.extractAudioFromVideo && r.kind == ResourceKind.VIDEO) {
                    val audioName = FileNameAllocator.allocateAuxiliary(fileTitle, "原声", "m4a", used)
                    val audio = AudioExtractor.extract(tmp, File(cache, "audio_$id"))
                    if (audio != null) {
                        val loc = saver.save(audio.file, audioName, "audio/mp4", SaveCategory.AUDIO)
                        used.add(audioName)
                        saved.add(
                            SavedFile(
                                audioName, loc, SaveCategory.AUDIO, audio.file.length(),
                                saver.savedUri(audioName, SaveCategory.AUDIO)?.toString().orEmpty(),
                                "audio/mp4"
                            )
                        )
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
                saved.add(
                    SavedFile(
                        name, location, category, tmp.length(),
                        saver.savedUri(name, category)?.toString().orEmpty(),
                        r.mime
                    )
                )
                // 静态帧留一份副本给本地动图合成复用（合成结束统一清理）
                if (r.isImage && !r.isLocalCompose) {
                    runCatching {
                        val keep = File(cache, "frame_$id.jpg")
                        tmp.copyTo(keep, overwrite = true)
                        framesByUrl[r.url] = keep
                    }
                }
                tmp.delete()
                onEvent(DownloadEvent(id, label, "done", 1f, name))
            } catch (e: Exception) {
                failures.add("$label：${e.message}")
                onEvent(DownloadEvent(id, label, "failed", 0f, e.message ?: "失败"))
            }
        }

        // 2) 本地合成动图：静态帧 → GIF 轮播 + 小米动态照片
        //    公开页只给静态帧与原声，动态片段由设备本地编码，不依赖平台私有接口。
        for (task in composeTasks) {
            val id = "anim"
            val label = task.label.ifBlank { "动图合成" }
            try {
                onEvent(DownloadEvent(id, label, "running", 0.05f, "准备静态帧"))
                val frames = mutableListOf<File>()
                task.composeFrames.forEachIndexed { fi, url ->
                    val cached = framesByUrl[url]
                    if (cached != null && cached.exists() && cached.length() > 0) {
                        frames += cached
                    } else {
                        // 用户没勾选该静态图时按需补下载，并带上解析器给出的备用直链
                        val src = parsed.images.firstOrNull { it.url == url }
                        val candidates = listOf(url) + (src?.alternatives ?: emptyList())
                        val dest = File(cache, "anim_frame_$fi.jpg")
                        runCatching {
                            downloader.download(
                                candidates, src?.headers ?: task.headers, dest, "动图帧 ${fi + 1}"
                            )
                        }.getOrNull()?.let { frames += it }
                    }
                }
                if (frames.isEmpty()) {
                    failures.add("动图：静态帧下载失败")
                    onEvent(DownloadEvent(id, label, "failed", 0f, "静态帧下载失败"))
                    continue
                }

                val result = composer.compose(
                    frames = frames,
                    coverJpeg = frames.first(),
                    wantMotionPhoto = settings.motionPhotoEnabled,
                    cache = cache
                ) { phase -> onEvent(DownloadEvent(id, label, "running", 0.4f, phase)) }

                val outputs = listOfNotNull(
                    result.gif?.let {
                        Triple(
                            it,
                            FileNameAllocator.allocateAuxiliary(fileTitle, "动图", "gif", used),
                            "image/gif"
                        )
                    },
                    result.motionPhoto?.let {
                        Triple(
                            it,
                            // MVIMG_ 前缀 + MP 结尾：小米相册与 Android 1.0 都能识别为动态照片
                            FileNameAllocator.allocateMotionPhoto(fileTitle, used),
                            "image/jpeg"
                        )
                    }
                )
                for ((file, name, mime) in outputs) {
                    val location = saver.save(file, name, mime, SaveCategory.IMAGE)
                    used.add(name)
                    saved.add(
                        SavedFile(
                            name, location, SaveCategory.IMAGE, file.length(),
                            saver.savedUri(name, SaveCategory.IMAGE)?.toString().orEmpty(), mime
                        )
                    )
                }
                if (outputs.isEmpty()) {
                    failures.add("动图：${result.detail}")
                    onEvent(DownloadEvent(id, label, "failed", 0f, result.detail))
                } else {
                    onEvent(DownloadEvent(id, label, "done", 1f, result.detail))
                }
            } catch (e: Exception) {
                failures.add("动图：${e.message}")
                onEvent(DownloadEvent(id, label, "failed", 0f, e.message ?: "失败"))
            }
        }
        // 合成用的中间帧统一清理，避免缓存堆积
        framesByUrl.values.forEach { runCatching { it.delete() } }
        cache.listFiles()?.filter { it.name.startsWith("anim_frame_") }
            ?.forEach { runCatching { it.delete() } }

        // 3) 端点/页面给出真实动态片段时 → 小米动态照片（单文件 JPG，无损拼接）
        if (settings.motionPhotoEnabled && parsed.isLivePhoto) {
            val cover = parsed.liveCover!!
            val video = parsed.liveVideo!!
            val id = "live"
            try {
                onEvent(DownloadEvent(id, "动态照片合成", "running", 0.1f, "下载封面与动态片段"))
                val cf = downloader.download(
                    listOf(cover.url) + cover.alternatives, cover.headers,
                    File(cache, "live_cover.jpg"), "动态封面"
                )
                val vf = downloader.download(
                    listOf(video.url) + video.alternatives, video.headers,
                    File(cache, "live_clip.mp4"), "动态片段"
                )
                val composed = MotionPhotoWriter.compose(cf.readBytes(), vf.readBytes())
                val report = MotionPhotoVerifier.verify(composed.bytes)
                if (!report.valid) error("自检失败：${report.problems.joinToString()}")

                val name = FileNameAllocator.allocateMotionPhoto(fileTitle, used)
                val out = File(cache, name).apply { writeBytes(composed.bytes) }
                val location = saver.save(out, name, "image/jpeg", SaveCategory.IMAGE)
                used.add(name)
                saved.add(
                    SavedFile(
                        name, location, SaveCategory.IMAGE, out.length(),
                        saver.savedUri(name, SaveCategory.IMAGE)?.toString().orEmpty(),
                        "image/jpeg"
                    )
                )
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
                val name = FileNameAllocator.allocateAuxiliary(fileTitle, "正文", "txt", used)
                val f = File(cache, name).apply { writeText(parsed.caption, Charsets.UTF_8) }
                val location = saver.save(f, name, "text/plain", SaveCategory.TEXT)
                used.add(name)
                saved.add(
                    SavedFile(
                        name, location, SaveCategory.TEXT, f.length(),
                        saver.savedUri(name, SaveCategory.TEXT)?.toString().orEmpty(),
                        "text/plain"
                    )
                )
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
        used: Set<String>,
        namingTokens: List<NameToken>,
        sep: String,
        ctx: NameContext,
        hasIndexToken: Boolean
    ): String {
        // 用户把「序号」放进自定义模板时，按该资源序号重算一次，
        // 且不再走 allocateSeries，避免文件名出现两处序号。
        if (hasIndexToken && r.index > 0) {
            val per = NameTemplate.format(namingTokens, sep, ctx.copy(index = r.index))
            return FileNameAllocator.allocate(per.ifBlank { title }, ext, used)
        }
        return if (r.index > 0 && imageCount > 1) {
            FileNameAllocator.allocateSeries(title, ext, r.index - 1, imageCount, used)
        } else {
            FileNameAllocator.allocate(title, ext, used)
        }
    }
}
