package com.aliahad.wovoice.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.aliahad.wovoice.ui.dp
import com.aliahad.wovoice.ui.systemAnimationsEnabled
import kotlin.math.log10
import kotlin.math.sin

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val targetLevels = FloatArray(9) { 0.14f }
    private val displayedLevels = FloatArray(9) { 0.14f }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(28, 28, 31)
        strokeCap = Paint.Cap.ROUND
        strokeWidth = context.dp(3).toFloat()
    }
    private val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(246, 245, 244) }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(76, 76, 79) }
    private val bounds = RectF()
    private var active = false
    private var animationsEnabled = true
    private var startedAt = 0L

    fun setAnimationsEnabled(enabled: Boolean) {
        animationsEnabled = enabled
        invalidate()
    }

    fun setLevel(rms: Float) {
        if (!motionAllowed()) return
        targetLevels.copyInto(targetLevels, destinationOffset = 0, startIndex = 1)
        // Use a logarithmic display scale. Real speech on the target Redmi sits around -46 dBFS,
        // which looked motionless with the old linear multiplier even though recording was valid.
        val db = 20f * log10(rms.coerceAtLeast(0.00001f))
        targetLevels[targetLevels.lastIndex] = ((db + 60f) / 25f).coerceIn(0.14f, 1f)
        postInvalidateOnAnimation()
    }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (value) {
            startedAt = SystemClock.uptimeMillis()
            targetLevels.fill(0.14f)
            displayedLevels.fill(0.14f)
            invalidate()
        }
    }

    private fun motionAllowed(): Boolean = animationsEnabled && context.systemAnimationsEnabled()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val motion = active && motionAllowed()
        val elapsed = SystemClock.uptimeMillis() - startedAt
        val pulse = if (motion) (sin(elapsed / 260.0).toFloat() + 1f) / 2f else 0f
        val haloInset = context.dp(2) - context.dp(2) * pulse
        haloPaint.alpha = (105 - 42 * pulse).toInt()
        bounds.set(haloInset, haloInset, width - haloInset, height - haloInset)
        canvas.drawOval(bounds, haloPaint)

        val surfaceInset = context.dp(9).toFloat()
        bounds.set(surfaceInset, surfaceInset, width - surfaceInset, height - surfaceInset)
        canvas.drawOval(bounds, surfacePaint)

        val availableHeight = height * 0.43f
        val contentWidth = width * 0.56f
        val startX = (width - contentWidth) / 2f
        val spacing = contentWidth / (displayedLevels.size - 1f)
        displayedLevels.forEachIndexed { index, displayed ->
            val value = if (motion) {
                displayedLevels[index] = displayed + (targetLevels[index] - displayed) * 0.24f
                displayedLevels[index]
            } else {
                STATIC_BAR_PATTERN[index]
            }
            val barHeight = (availableHeight * value).coerceAtLeast(context.dp(8).toFloat())
            val x = startX + spacing * index
            canvas.drawLine(x, height / 2f - barHeight / 2f, x, height / 2f + barHeight / 2f, barPaint)
        }
        if (motion && isAttachedToWindow) postInvalidateDelayed(FRAME_DELAY_MS)
    }

    override fun onDetachedFromWindow() {
        active = false
        super.onDetachedFromWindow()
    }

    private companion object {
        const val FRAME_DELAY_MS = 33L
        val STATIC_BAR_PATTERN = floatArrayOf(0.22f, 0.38f, 0.58f, 0.78f, 0.92f, 0.78f, 0.58f, 0.38f, 0.22f)
    }
}
