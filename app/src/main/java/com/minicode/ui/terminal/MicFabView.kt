package com.minicode.ui.terminal

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View

class MicFabView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    var recording = false
        set(value) {
            if (field != value) {
                field = value
                if (value) {
                    recordingStartTime = SystemClock.elapsedRealtime()
                    rmsHistory.fill(0f)
                }
                invalidate()
            }
        }

    var onTimerUpdate: ((String) -> Unit)? = null
    private var recordingStartTime = 0L

    // Ring buffer of RMS values for spectrum bars
    private val barCount = 12
    private val rmsHistory = FloatArray(barCount)
    private var rmsIndex = 0

    private val idleBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCCC4444.toInt()
    }
    private val recordBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xDD1E1E1E.toInt()
    }
    private val redDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF3B30.toInt()
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF3B30.toInt()
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val barBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFFFFF
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val micPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    fun updateRms(rmsdB: Float) {
        val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        rmsHistory[rmsIndex % barCount] = normalized
        rmsIndex++
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(cx, cy)

        if (!recording) {
            canvas.drawCircle(cx, cy, radius, idleBgPaint)
            micPaint.textSize = radius * 1.1f
            canvas.drawText("\uD83C\uDF99", cx, cy + micPaint.textSize * 0.35f, micPaint)
            return
        }

        val elapsed = SystemClock.elapsedRealtime() - recordingStartTime

        // Background
        canvas.drawCircle(cx, cy, radius, recordBgPaint)

        // Spectrum bars around the circle
        val barRadius = radius * 0.65f
        val minBarH = 3f * density
        val maxBarH = radius * 0.30f
        for (i in 0 until barCount) {
            val angle = (i * 360f / barCount) - 90f
            val rad = Math.toRadians(angle.toDouble())
            val cos = Math.cos(rad).toFloat()
            val sin = Math.sin(rad).toFloat()

            val rmsVal = rmsHistory[(rmsIndex - barCount + i + barCount * 2) % barCount]
            val barH = minBarH + rmsVal * (maxBarH - minBarH)

            val x1 = cx + cos * barRadius
            val y1 = cy + sin * barRadius
            val x2 = cx + cos * (barRadius + barH)
            val y2 = cy + sin * (barRadius + barH)

            canvas.drawLine(x1, y1, cx + cos * (barRadius + maxBarH), cy + sin * (barRadius + maxBarH), barBgPaint)
            canvas.drawLine(x1, y1, x2, y2, barPaint)
        }

        // Pulsing red dot in center
        val pulse = 1f + 0.3f * Math.sin(elapsed / 500.0 * Math.PI).toFloat()
        val dotRadius = 5f * density * pulse
        canvas.drawCircle(cx, cy, dotRadius, redDotPaint)

        // Update timer via callback
        val secs = (elapsed / 1000).toInt()
        onTimerUpdate?.invoke("%d:%02d".format(secs / 60, secs % 60))

        postInvalidateOnAnimation()
    }
}
