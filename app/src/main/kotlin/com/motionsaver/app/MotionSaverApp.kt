package com.motionsaver.app

import android.app.Application
import com.motionsaver.app.data.SaveMediaUseCase
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
                .readTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
        val saveMedia: SaveMediaUseCase by lazy { SaveMediaUseCase(app, http) }
    }

    override fun onCreate() {
        super.onCreate()
        container = Container(this)
    }
}
