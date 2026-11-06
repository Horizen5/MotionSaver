package com.motionsaver.app

import android.app.Activity
import android.os.Build
import android.view.Display
import android.view.WindowManager

/**
 * 请求设备支持的最高刷新率档位（至少 60Hz），让 Compose 的滚动/动画按屏幕
 * 原生刷新节奏走，而不是被系统压到低刷新率档产生"卡卡的"感觉。
 *
 * 说明：Android 没有"强制设置 60/120Hz"的公开 API（刷新率由系统与厂商显示
 * 策略管理）。可做的是通过 Window 的 preferredDisplayModeId 选一个高刷
 * Display.Mode；系统允许时生效，不允许时静默回退，不会崩溃也不影响功能。
 */
object HighRefreshRate {

    fun apply(activity: Activity) {
        runCatching { applyInternal(activity) }
    }

    private fun applyInternal(activity: Activity) {
        val window = activity.window
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay
        } ?: return

        val current = display.mode
        val modes = display.supportedModes
        if (modes.isEmpty()) return

        // 只在不降低分辨率的前提下挑刷新率最高的档位（>= 60Hz）
        val best = modes
            .asSequence()
            .filter { it.refreshRate >= 59.0f }
            .filter {
                it.physicalWidth >= current.physicalWidth &&
                    it.physicalHeight >= current.physicalHeight
            }
            .maxWithOrNull(
                compareBy<Display.Mode> { it.refreshRate }
                    .thenBy { it.physicalWidth.toLong() * it.physicalHeight }
            ) ?: return

        if (best.modeId == current.modeId) return

        val lp = window.attributes
        lp.preferredDisplayModeId = best.modeId
        window.attributes = lp
    }
}
