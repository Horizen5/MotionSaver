package com.motionsaver.app

import android.app.Application
import coil.Coil
import coil.ImageLoader
import com.motionsaver.app.data.HistoryStore
import com.motionsaver.app.data.SaveMediaUseCase
import com.motionsaver.app.data.SettingsStore
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** 轻量依赖容器：不引入 Hilt/KSP，减少构建环节与包体，职责与 DI 一致。 */
class MotionSaverApp : Application() {

    lateinit var container: Container
        private set

    class Container(app: MotionSaverApp) {
        val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
        val settings: SettingsStore by lazy { SettingsStore(app) }
        val history: HistoryStore by lazy { HistoryStore(app) }
        val saveMedia: SaveMediaUseCase by lazy { SaveMediaUseCase(app, http) }
    }

    override fun onCreate() {
        super.onCreate()
        container = Container(this)
        // 预览缩略图走独立客户端：CDN 直链需要常规 UA 与 Referer
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .okHttpClient(previewClient())
                .crossfade(true)
                .build()
        )
    }

    private fun previewClient(): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", UA)
                .header("Referer", "https://www.douyin.com/")
                .build()
            chain.proceed(req)
        }
        .build()

    private companion object {
        const val UA =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
    }
}
