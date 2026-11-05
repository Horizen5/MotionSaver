package com.motionsaver.app.data

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * 音频解析的补强路径：平台没直接给音频地址时，从已下载视频里无损抽出音轨。
 *
 * 用 MediaExtractor + MediaMuxer 做「流拷贝」，只对样本字节搬移、不解码不重编码，
 * 速度快、不掉音质、无需引入 FFmpegKit（省约 40MB AAR）。输出 .m4a（MP4 容器 / AAC）。
 */
object AudioExtractor {

    data class Result(val file: File, val codec: String, val durationUs: Long)

    /** @param destNoExt 不含扩展名的目标路径，实际写出为 .m4a；无音轨或格式不支持时返回 null。 */
    fun extract(video: File, destNoExt: File): Result? {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(video.absolutePath)

            var audioTrack = -1
            var mime = ""
            var durationUs = 0L
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val m = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (m.startsWith("audio/")) {
                    audioTrack = i
                    mime = m
                    if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
                        durationUs = fmt.getLong(MediaFormat.KEY_DURATION)
                    }
                    break
                }
            }
            if (audioTrack < 0) return null

            // MediaMuxer 只封装 MP4 容器支持的音轨（AAC / mp4a-latm）
            if (!mime.contains("mp4a") && !mime.contains("aac")) return null

            val fmt = extractor.getTrackFormat(audioTrack)
            extractor.selectTrack(audioTrack)

            val out = File(destNoExt.absolutePath + ".m4a")
            if (out.exists()) out.delete()

            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val track = muxer.addTrack(fmt)
            muxer.start()

            val info = MediaCodec.BufferInfo()
            val buf = ByteBuffer.allocate(256 * 1024)
            var maxTime = 0L
            while (true) {
                buf.clear()
                val size = extractor.readSampleData(buf, 0)
                if (size < 0) break
                val time = extractor.sampleTime
                info.set(0, size, time, extractor.sampleFlags)
                muxer.writeSampleData(track, buf, info)
                if (time > maxTime) maxTime = time
                extractor.advance()
            }
            if (durationUs <= 0) durationUs = maxTime

            return runCatching {
                muxer!!.stop()
                Result(out, mime, durationUs)
            }.getOrNull()
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { extractor.release() }
            runCatching { muxer?.release() }
        }
    }
}
