package com.motionsaver.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.motionsaver.app.ui.AppRoot
import com.motionsaver.app.ui.MotionSaverTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 请求设备最高刷新率档位（>=60Hz），让滚动/动画按屏幕原生节奏渲染
        HighRefreshRate.apply(this)
        enableEdgeToEdge()

        // 容器与 ViewModel 只创建一次：放在 setContent 外，避免重组时重复构造。
        // ViewModel 由 viewModel() 按 Activity 生命周期缓存，配置变更（旋转/深色）
        // 不重新解析、不丢状态。
        val app = application as MotionSaverApp
        val container = app.container
        val sharedText = intent.takeIf { it.action == Intent.ACTION_SEND }
            ?.getStringExtra(Intent.EXTRA_TEXT)
        // 分享进来的文本只消费一次，旋转后不重复灌入
        val consumedShared = savedInstanceState?.getBoolean(KEY_SHARED_CONSUMED, false) == true
        if (sharedText != null && !consumedShared) {
            savedInstanceState?.putBoolean(KEY_SHARED_CONSUMED, true)
        }

        setContent {
            MotionSaverTheme {
                AppRoot(
                    container = container,
                    initialSharedText = if (consumedShared) null else sharedText
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_SHARED_CONSUMED, true)
    }

    private companion object {
        const val KEY_SHARED_CONSUMED = "shared_consumed"
    }
}
