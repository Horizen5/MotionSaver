package com.motionsaver.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import com.motionsaver.app.ui.AppRoot
import com.motionsaver.app.ui.MotionSaverTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 请求设备最高刷新率档位（>=60Hz），让滚动/动画按屏幕原生节奏渲染
        HighRefreshRate.apply(this)
        // 状态栏 / 导航栏完全透明，并关闭系统在导航栏区强制叠加的浅色对比度保护带
        // （contrast scrim）——那正是底部「明显分层」的来源，与 App 连续渐变横切。
        // 顺序要求：enableEdgeToEdge() 内部会把 isNavigationBarContrastEnforced 置为
        // true，因此必须在它之后关掉（Android 官方 edge-to-edge / system bars 指南）。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ) { res ->
                (res.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
            },
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ) { res ->
                (res.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

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
