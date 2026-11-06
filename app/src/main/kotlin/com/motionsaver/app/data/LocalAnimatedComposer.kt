package com.motionsaver.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.motionsaver.core.AnimatedGifEncoder
import com.motionsaver.core.MotionPhotoVerifier
import com.motionsaver.core.MotionPhotoWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 设备本地动图合成器。
 *
 * 背景：抖音「实况图 / 图文图集」的公开分享页只暴露静态帧与原声，
 * 动态视频片段（MP4）不在公开数据里——实测 play_addr 是 ftypM4A 纯音频、
 * bit_rate=null、image_infos=null，且带签名的私有接口不对公开页开放。
 * 所以这里用**公开页合法取得的静态帧**在设备本地编码，产出两样真东西：
 *
 *  1. GIF89a 轮播动图：纯 JVM 编码器，任何相册都能看会动，字节完全可控。
 *  2. 小米 / Google 动态照片：MediaCodec 硬编码 H.264 轮播 MP4 + 原图 JPEG
 *     封面 + XMP 元数据，无损拼接，小米相册可识别并播放。
 *
 * 绝不把 M4A 音频伪装成视频（那会产出相册里 0 秒 / 损坏的文件）。
 * MP4 硬编码在个别机型不可用时，自动降级为「只出 GIF」，绝不写坏文件。
 */
class LocalAnimatedComposer {

    data class Output(
        val gif: File?,
        val motionPhoto: File?,
        val detail: String
    )

    /**
     * @param frames    已下载的静态帧文件（按图集顺序）
     * @param coverJpeg 首选原图 JPEG 字节，用作动态照片封面（不重新编码，画质零损失）
     * @param wantMotionPhoto 是否额外合成小米动态照片（受设置开关控制）
     */
    suspend fun compose(
        frames: List<File>,
        coverJpeg: File?,
        wantMotionPhoto: Boolean,
        cache: File,
        onProgress: (String) -> Unit
    ): Output = withContext(Dispatchers.Default) {
        if (frames.isEmpty()) return@withContext Output(null, null, "没有可用的静态帧")

        onProgress("解码静态帧")
        val decoded = frames.mapNotNull { decode(it) }.take(MAX_FRAMES)
        if (decoded.isEmpty()) return@withContext Output(null, null, "静态帧解码失败")

        // 统一画布：取首帧比例，最长边 640，宽高按 16 对齐（MediaCodec 兼容性最好）
        val (w, h) = targetSize(decoded.first())
        val fitted = decoded.map { fit(it, w, h) }
        val pixels = fitted.map { bmp ->
            val p = IntArray(w * h)
            bmp.getPixels(p, 0, w, 0, 0, w, h)
            if (bmp !== decoded.first() && !bmp.isRecycled) bmp.recycle()
            p
        }
        decoded.forEach { if (!it.isRecycled) it.recycle() }

        // 1) GIF 轮播动图 —— 主产物，必然成功
        onProgress("编码 GIF 动图")
        val gif = runCatching {
            val gifFrames = pixels.map { AnimatedGifEncoder.Frame(it, w, h, FRAME_DELAY_CS) }
            File(cache, "compose_animated.gif").apply {
                writeBytes(AnimatedGifEncoder.encode(gifFrames))
            }
        }.getOrNull()

        // 2) 小米动态照片 —— 增强产物，尽力而为
        var motion: File? = null
        var mp4Note = ""
        if (wantMotionPhoto && gif != null) {
            onProgress("硬编码动态片段 MP4")
            val mp4 = File(cache, "compose_clip.mp4")
            val ok = encodeMp4(pixels, w, h, mp4)
            if (ok && coverJpeg != null && coverJpeg.exists()) {
                onProgress("合成小米动态照片")
                motion = runCatching {
                    val composed = MotionPhotoWriter.compose(coverJpeg.readBytes(), mp4.readBytes())
                    val report = MotionPhotoVerifier.verify(composed.bytes)
                    if (!report.valid) error("动态照片自检失败：${report.problems.joinToString()}")
                    File(cache, "compose_motion.jpg").apply { writeBytes(composed.bytes) }
                }.getOrNull()
            }
            mp4Note = if (motion != null) " · 小米动态照片已合成" else " · 动态照片未合成（设备编码器不支持）"
            mp4.delete()
        }

        val detail = buildString {
            append("${pixels.size} 帧 · ${w}×${h} · GIF ${gif?.let { it.length() / 1024 } ?: 0} KB")
            append(mp4Note)
        }
        Output(gif, motion, detail)
    }

    // ---------------------------------------------------------------- 解码 / 缩放

    private fun decode(file: File): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val sw = bounds.outWidth
        val sh = bounds.outHeight
        if (sw <= 0 || sh <= 0) return null
        var sample = 1
        while (maxOf(sw, sh) / sample > MAX_EDGE * 2) sample *= 2
        val opt = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeFile(file.absolutePath, opt)
    }.getOrNull()

    private fun targetSize(first: Bitmap): Pair<Int, Int> {
        val sw = first.width.toFloat()
        val sh = first.height.toFloat()
        val scale = MAX_EDGE.toFloat() / maxOf(sw, sh)
        var w = (sw * scale).toInt()
        var h = (sh * scale).toInt()
        w = maxOf(16, (w / 16) * 16)
        h = maxOf(16, (h / 16) * 16)
        return w to h
    }

    /** 居中留白贴到统一画布，避免不同长宽比的图被拉伸。 */
    private fun fit(src: Bitmap, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        val scale = minOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val dw = (src.width * scale).toInt()
        val dh = (src.height * scale).toInt()
        val resized = Bitmap.createScaledBitmap(src, maxOf(1, dw), maxOf(1, dh), true)
        c.drawBitmap(resized, ((w - dw) / 2f), ((h - dh) / 2f), null)
        if (resized !== src && !resized.isRecycled) resized.recycle()
        return out
    }

    // ---------------------------------------------------------------- H.264 编码

    /**
     * 用 MediaCodec 把静态帧序列编码成合法 MP4（video/avc，NV12 输入）。
     * 每张图停留 1 秒（4 fps × 4 帧），pts 连续递增，相册能得到正确时长。
     * 任何异常都返回 false，由上层降级为只交付 GIF。
     */
    private fun encodeMp4(frames: List<IntArray>, w: Int, h: Int, out: File): Boolean {
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        return try {
            if (out.exists()) out.delete()
            val mime = MediaFormat.MIMETYPE_VIDEO_AVC
            val fmt = MediaFormat.createVideoFormat(mime, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
                setInteger(MediaFormat.KEY_BIT_RATE, 1_800_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec = MediaCodec.createEncoderByType(mime)
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val info = MediaCodec.BufferInfo()
            var track = -1
            var muxStarted = false
            var frameNo = 0
            var inputDone = false
            var outputDone = false
            var idle = 0
            val totalFrames = frames.size * FPS

            while (!outputDone && idle < 40) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (idx >= 0) {
                        val imageIdx = frameNo / FPS
                        if (imageIdx < frames.size) {
                            val yuv = toNv12(frames[imageIdx], w, h)
                            val buf = codec.getInputBuffer(idx)
                            if (buf == null) { inputDone = true }
                            else {
                                buf.clear()
                                buf.put(yuv)
                                codec.queueInputBuffer(
                                    idx, 0, yuv.size, frameNo * 1_000_000L / FPS, 0
                                )
                            }
                            frameNo++
                        } else {
                            codec.queueInputBuffer(
                                idx, 0, 0, frameNo * 1_000_000L / FPS,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        }
                    }
                }

                when (val oi = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxStarted) {
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxStarted = true
                        }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (inputDone) idle++
                    }
                    else -> if (oi >= 0) {
                        idle = 0
                        val buf = codec.getOutputBuffer(oi)
                        if (buf != null && info.size > 0 && track >= 0 && muxStarted &&
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            if (muxStarted) runCatching { muxer.stop() }
            val ok = outputDone && out.length() > 1024 && MotionPhotoWriter.isFtyp(out.readBytes())
            if (!ok) out.delete()
            ok
        } catch (e: Exception) {
            out.delete()
            false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { muxer?.release() }
        }
    }

    /** ARGB_8888 → NV12（Y 平面 + UV 交错），2×2 块共享一组色度。 */
    private fun toNv12(argb: IntArray, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h * 3 / 2)
        val base = w * h
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = argb[y * w + x]
                val r = (c ushr 16) and 0xFF
                val g = (c ushr 8) and 0xFF
                val b = c and 0xFF
                out[y * w + x] = ((77 * r + 150 * g + 29 * b) ushr 8).toByte()
                if ((y and 1) == 0 && (x and 1) == 0) {
                    val u = (((-43 * r - 85 * g + 128 * b) ushr 8) + 128).coerceIn(0, 255)
                    val v = (((128 * r - 107 * g - 21 * b) ushr 8) + 128).coerceIn(0, 255)
                    val p = base + (y / 2) * w + x
                    out[p] = u.toByte()
                    out[p + 1] = v.toByte()
                }
            }
        }
        return out
    }

    private companion object {
        const val MAX_EDGE = 640
        const val MAX_FRAMES = 12
        const val FRAME_DELAY_CS = 120   // 每帧 1.2 秒
        const val FPS = 4
        const val TIMEOUT_US = 10_000L
    }
}
