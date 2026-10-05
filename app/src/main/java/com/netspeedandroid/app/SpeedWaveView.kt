package com.netspeedandroid.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.sin

class SpeedWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.wave)
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.wave_dot)
        style = Paint.Style.FILL
    }
    private val path = Path()
    private var phase = 0f
    private var running = false
    private val animator = ValueAnimator.ofFloat(0f, (2 * PI).toFloat()).apply {
        duration = 1_600L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    fun setRunning(value: Boolean) {
        running = value
        if (value && isAttachedToWindow && !animator.isStarted) animator.start()
        if (!value) animator.cancel()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (running && !animator.isStarted) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0) return

        val centerY = height / 2f
        val amplitude = height * if (running) 0.22f else 0.13f
        path.reset()
        var x = 0f
        while (x <= width) {
            val angle = x / width * (5 * PI).toFloat() + phase
            val y = centerY + sin(angle.toDouble()).toFloat() * amplitude
            if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
            x += 4f * density
        }
        canvas.drawPath(path, wavePaint)

        val dotX = width * if (running) ((phase / (2 * PI)).toFloat()) else 0.8f
        val dotAngle = dotX / width * (5 * PI).toFloat() + phase
        val dotY = centerY + sin(dotAngle.toDouble()).toFloat() * amplitude
        canvas.drawCircle(dotX, dotY, 6f * density, dotPaint)
    }
}
