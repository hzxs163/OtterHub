package com.otterhub.app

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * targetSdk 35 在安卓 15 上被强制边到边，不补 inset 的话网页顶部会被状态栏和刘海压住。
 * 这里把系统栏高度叠加到根视图原有的内边距上，横屏时的刘海/导航栏左右间距也一起处理。
 */
fun View.padForSystemBars() {
    val baseLeft = paddingLeft
    val baseTop = paddingTop
    val baseRight = paddingRight
    val baseBottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        view.setPadding(
            baseLeft + bars.left,
            baseTop + bars.top,
            baseRight + bars.right,
            baseBottom + bars.bottom,
        )
        insets
    }
}
