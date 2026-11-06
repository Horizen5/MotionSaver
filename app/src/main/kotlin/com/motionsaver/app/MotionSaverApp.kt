package com.motionsaver.app

import android.app.Application
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.motionsaver.app.data.HistoryStore
import com.motionsaver.app.data.SaveMediaUseCase
import com.motionsaver.app.data.SettingsStore
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** 轻量依赖容器：不引入 Hilt/KSP，减少构建环节与包体，职责与 DI 一致。 */
class MotionSaverApp : Application() {

    lateinit var container: Container
        private set

    class Container(app: MotionSaverApp) {
        // 单一连接池复用，避免每次解析/下载都新建 TCP+TLS 连接
        private val pool = ConnectionPool(5, 5, TimeUnit.MINUTES)

        val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectionPool(pool)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .build()
        }
        val settings: SettingsStore by lazy { SettingsStore(app) }
        val history: HistoryStore by lazy { HistoryStore(app) }
        val saveMedia: SaveMediaUseCase by lazy { SaveMediaUseCase(app, http) }
    }

    override fun onCreate() {
        super.onCreate()
        container = Container(this)
        // 缩略图走独立客户端（CDN 需要常规 UA 与 Referer）。
        // 关闭 crossfade 与弱引用之外的动画开销，限制内存/磁盘缓存，降低 GC 与占用。
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .okHttpClient(previewClient())
                .crossfade(false)
                .memoryCache {
                    MemoryCache.Builder(this)
                        // 最多占用堆的 12%，避免大图缓存把可用内存吃满触发 GC 掉帧
                        .maxSizePercent(0.12)
                        .build()
                }
                .diskCache {
                    DiskCache.Builder()
                        .directory(cacheDir.resolve("ms_image_cache"))
                        .maxSizeBytes(32L * 1024 * 1024)
                        .build()
                }
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
