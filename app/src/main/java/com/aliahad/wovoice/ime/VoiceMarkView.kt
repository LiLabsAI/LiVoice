package com.aliahad.wovoice.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.min

/**
 * Renders the LiVoice monogram and radial voice wave emblem cleanly at keyboard bar scale.
 */
class VoiceMarkView(context: Context) : View(context) {
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mintGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lilacGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(36, 255, 255, 255)
        strokeWidth = 2f
    }
    private val mintStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#7DE4B6")
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 6.8f
    }
    private val mintWavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#7DE4B6")
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 5.2f
    }
    private val lilacWavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#AFA4FF")
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 5.2f
    }
    private val lilacDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#AFA4FF")
    }

    private val tileRect = RectF(0f, 0f, 108f, 108f)
    private val borderRect = RectF(1f, 1f, 107f, 107f)
    private val innerArcRect = RectF(58.5f - 11f, 41.5f - 11f, 58.5f + 11f, 41.5f + 11f)
    private val outerArcRect = RectF(58.5f - 17.5f, 41.5f - 17.5f, 58.5f + 17.5f, 41.5f + 17.5f)
    private val lPath = Path().apply {
        moveTo(33.5f, 39.5f)
        lineTo(33.5f, 65.5f)
        arcTo(RectF(33.5f, 58f, 48.5f, 73f), 180f, -90f, false)
        lineTo(46f, 73f)
    }

    init {
        bgPaint.shader = RadialGradient(
            40f,
            34f,
            92f,
            intArrayOf(
                Color.parseColor("#262832"),
                Color.parseColor("#1A1B22"),
                Color.parseColor("#121318"),
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        mintGlowPaint.shader = RadialGradient(
            74f,
            36f,
            46f,
            intArrayOf(Color.argb(56, 125, 228, 182), Color.TRANSPARENT),
            null,
            Shader.TileMode.CLAMP,
        )
        lilacGlowPaint.shader = RadialGradient(
            38f,
            72f,
            44f,
            intArrayOf(Color.argb(46, 175, 164, 255), Color.TRANSPARENT),
            null,
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        if (size <= 0f) return
        val scale = size / 108f
        canvas.save()
        canvas.translate((width - size) / 2f, (height - size) / 2f)
        canvas.scale(scale, scale)

        canvas.drawRoundRect(tileRect, 26f, 26f, bgPaint)
        canvas.drawRoundRect(tileRect, 26f, 26f, mintGlowPaint)
        canvas.drawRoundRect(tileRect, 26f, 26f, lilacGlowPaint)
        canvas.drawRoundRect(borderRect, 25f, 25f, borderPaint)

        canvas.drawPath(lPath, mintStrokePaint)
        canvas.drawCircle(58.5f, 41.5f, 4.6f, lilacDotPaint)
        canvas.drawLine(58.5f, 53.5f, 58.5f, 73f, mintStrokePaint)
        canvas.drawArc(innerArcRect, -50f, 92f, false, lilacWavePaint)
        canvas.drawArc(outerArcRect, -50f, 92f, false, mintWavePaint)

        canvas.restore()
    }
}
