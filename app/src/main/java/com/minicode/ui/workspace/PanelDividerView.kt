package com.minicode.ui.workspace

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup

class PanelDividerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var onDrag: ((Float) -> Unit)? = null
    private var lastPos = 0f
    private var dragging = false
    private val isHorizontal get() = layoutParams?.width == ViewGroup.LayoutParams.MATCH_PARENT
    private val extraTouchPx = (16 * resources.displayMetrics.density).toInt()
    private val density = resources.displayMetrics.density

    // Thin divider line (center 8dp of the 24dp view)
    private val dividerLinePaint = Paint().apply {
        color = 0xFF2D2D2D.toInt() // dark_border color
    }
    private val dividerLineThickness = 8f * density

    // Pill handle: 48dp long, 18dp thick
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF707070.toInt()
    }
    private val handleHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFA0A0A0.toInt()
    }
    private val handleLength = 48f * density
    private val handleThickness = 18f * density
    private val handleRadius = handleThickness / 2f

    init {
        isClickable = true
        isFocusable = true
        post {
            val parent = parent as? ViewGroup ?: return@post
            parent.clipChildren = false
            // Expand touch area
            val rect = Rect()
            getHitRect(rect)
            if (isHorizontal) {
                rect.top -= extraTouchPx
                rect.bottom += extraTouchPx
            } else {
                rect.left -= extraTouchPx
                rect.right += extraTouchPx
            }
            parent.touchDelegate = TouchDelegate(rect, this)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f

        // Draw thin divider line in center
        if (isHorizontal) {
            val top = cy - dividerLineThickness / 2f
            canvas.drawRect(0f, top, width.toFloat(), top + dividerLineThickness, dividerLinePaint)
        } else {
            val left = cx - dividerLineThickness / 2f
            canvas.drawRect(left, 0f, left + dividerLineThickness, height.toFloat(), dividerLinePaint)
        }

        // Draw thick pill handle on top
        val paint = if (dragging) handleHighlightPaint else handlePaint
        if (isHorizontal) {
            val rect = RectF(
                cx - handleLength / 2f,
                cy - handleThickness / 2f,
                cx + handleLength / 2f,
                cy + handleThickness / 2f
            )
            canvas.drawRoundRect(rect, handleRadius, handleRadius, paint)
        } else {
            val rect = RectF(
                cx - handleThickness / 2f,
                cy - handleLength / 2f,
                cx + handleThickness / 2f,
                cy + handleLength / 2f
            )
            canvas.drawRoundRect(rect, handleRadius, handleRadius, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val horizontal = isHorizontal
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                lastPos = if (horizontal) event.rawY else event.rawX
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    val current = if (horizontal) event.rawY else event.rawX
                    val delta = current - lastPos
                    if (delta != 0f) {
                        onDrag?.invoke(delta)
                        lastPos = current
                    }
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
