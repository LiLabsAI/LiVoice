package com.aliahad.wovoice.ime

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import kotlin.math.abs

open class SwipeFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    var onHorizontalSwipe: ((direction: Int) -> Unit)? = null
    private val threshold = ViewConfiguration.get(context).scaledTouchSlop * 3f
    private var downX = 0f
    private var downY = 0f
    private var intercepting = false

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                intercepting = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (abs(dx) > threshold && abs(dx) > abs(dy) * 1.35f) {
                    // Never steal horizontal swipes from scrollable descendants such as
                    // the emoji category strip.
                    if (canDescendantScrollHorizontally(this, false, -dx.toInt(), event.x.toInt(), event.y.toInt())) {
                        return false
                    }
                    intercepting = true
                    return true
                }
            }
        }
        return intercepting || super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!intercepting) return super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val dx = event.x - downX
            if (abs(dx) > threshold) onHorizontalSwipe?.invoke(if (dx > 0) 1 else -1)
            performClick()
            intercepting = false
        } else if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            intercepting = false
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun canDescendantScrollHorizontally(
        view: View,
        checkSelf: Boolean,
        dx: Int,
        x: Int,
        y: Int,
    ): Boolean {
        if (view is ViewGroup) {
            val scrollX = view.scrollX
            val scrollY = view.scrollY
            for (i in view.childCount - 1 downTo 0) {
                val child = view.getChildAt(i)
                if (child.visibility != View.VISIBLE) continue
                val childX = x + scrollX - child.left
                val childY = y + scrollY - child.top
                if (childX >= 0 && childX < child.width && childY >= 0 && childY < child.height) {
                    if (canDescendantScrollHorizontally(child, true, dx, childX, childY)) {
                        return true
                    }
                }
            }
        }
        return checkSelf && (view is HorizontalScrollView || view.canScrollHorizontally(dx))
    }
}
