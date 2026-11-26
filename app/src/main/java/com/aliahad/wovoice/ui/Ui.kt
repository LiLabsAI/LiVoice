package com.aliahad.wovoice.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

fun Context.systemAnimationsEnabled(): Boolean = runCatching {
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
}.getOrDefault(true)

fun rounded(color: Int, radius: Float, strokeColor: Int? = null, strokeWidth: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radius
        if (strokeColor != null && strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }

fun TextView.styleText(sizeSp: Float, color: Int = Color.WHITE) {
    textSize = sizeSp
    setTextColor(color)
    // Keep any horizontal alignment chosen by the caller. The old implementation replaced
    // Gravity.CENTER with CENTER_VERTICAL, which pushed keyboard labels and icons to the start.
    gravity = (gravity and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK) or Gravity.CENTER_VERTICAL
    includeFontPadding = false
}

fun View.setButtonAccessibilityRole() {
    ViewCompat.setAccessibilityDelegate(
        this,
        object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
            }
        },
    )
}

fun View.setHorizontalMargins(value: Int) {
    (layoutParams as? ViewGroup.MarginLayoutParams)?.let {
        it.marginStart = value
        it.marginEnd = value
        layoutParams = it
    }
}
