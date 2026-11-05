package com.motionsaver.app.data

import android.content.Context
import com.motionsaver.core.EndpointSpec
import com.motionsaver.core.FieldMap

/**
 * App 设置。所有可配置项都能在设置页改，且实时生效，无需重编译。
 * 「解析端点」是让四类资源完整落地的关键：使用者填自己的合规数据源即可。
 */
data class AppSettings(
    val endpointEnabled: Boolean = false,
    val endpointUrl: String = "",
    val endpointHeaders: String = "",
    val fieldImages: String = "images",
    val fieldVideos: String = "videos",
    val fieldAudios: String = "audios",
    val fieldAnimated: String = "animated",
    val fieldCover: String = "cover",
    val fieldLiveVideo: String = "live_video",
    val saveCaption: Boolean = true,
    val motionPhotoEnabled: Boolean = true,
    /** 平台没给音频地址时，从已下载视频里无损抽音轨 */
    val extractAudioFromVideo: Boolean = false,
    val concurrency: Int = 3
) {
    fun toEndpointSpec(): EndpointSpec? {
        if (!endpointEnabled || !endpointUrl.contains("{url}")) return null
        return EndpointSpec(
            enabled = true,
            urlTemplate = endpointUrl,
            headers = EndpointSpec.parseHeaders(endpointHeaders),
            fields = FieldMap(
                images = fieldImages,
                videos = fieldVideos,
                audios = fieldAudios,
                animated = fieldAnimated,
                cover = fieldCover,
                liveVideo = fieldLiveVideo
            )
        )
    }
}

class SettingsStore(context: Context) {

    private val sp = context.getSharedPreferences("ms_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        endpointEnabled = sp.getBoolean(K_ENDPOINT_ON, false),
        endpointUrl = sp.getString(K_ENDPOINT_URL, "").orEmpty(),
        endpointHeaders = sp.getString(K_ENDPOINT_HEADERS, "").orEmpty(),
        fieldImages = sp.getString(K_F_IMAGES, "images").orEmpty(),
        fieldVideos = sp.getString(K_F_VIDEOS, "videos").orEmpty(),
        fieldAudios = sp.getString(K_F_AUDIOS, "audios").orEmpty(),
        fieldAnimated = sp.getString(K_F_ANIMATED, "animated").orEmpty(),
        fieldCover = sp.getString(K_F_COVER, "cover").orEmpty(),
        fieldLiveVideo = sp.getString(K_F_LIVE, "live_video").orEmpty(),
        saveCaption = sp.getBoolean(K_SAVE_CAPTION, true),
        motionPhotoEnabled = sp.getBoolean(K_MOTION, true),
        extractAudioFromVideo = sp.getBoolean(K_EXTRACT_AUDIO, false),
        concurrency = sp.getInt(K_CONCURRENCY, 3)
    )

    fun save(s: AppSettings) {
        sp.edit()
            .putBoolean(K_ENDPOINT_ON, s.endpointEnabled)
            .putString(K_ENDPOINT_URL, s.endpointUrl)
            .putString(K_ENDPOINT_HEADERS, s.endpointHeaders)
            .putString(K_F_IMAGES, s.fieldImages)
            .putString(K_F_VIDEOS, s.fieldVideos)
            .putString(K_F_AUDIOS, s.fieldAudios)
            .putString(K_F_ANIMATED, s.fieldAnimated)
            .putString(K_F_COVER, s.fieldCover)
            .putString(K_F_LIVE, s.fieldLiveVideo)
            .putBoolean(K_SAVE_CAPTION, s.saveCaption)
            .putBoolean(K_MOTION, s.motionPhotoEnabled)
            .putBoolean(K_EXTRACT_AUDIO, s.extractAudioFromVideo)
            .putInt(K_CONCURRENCY, s.concurrency)
            .apply()
    }

    private companion object {
        const val K_ENDPOINT_ON = "endpoint_on"
        const val K_ENDPOINT_URL = "endpoint_url"
        const val K_ENDPOINT_HEADERS = "endpoint_headers"
        const val K_F_IMAGES = "f_images"
        const val K_F_VIDEOS = "f_videos"
        const val K_F_AUDIOS = "f_audios"
        const val K_F_ANIMATED = "f_animated"
        const val K_F_COVER = "f_cover"
        const val K_F_LIVE = "f_live"
        const val K_SAVE_CAPTION = "save_caption"
        const val K_MOTION = "motion"
        const val K_EXTRACT_AUDIO = "extract_audio"
        const val K_CONCURRENCY = "concurrency"
    }
}
