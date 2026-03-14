package com.minicode.ui.workspace

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Floating circular handle for adjusting split panel dividers.
 * Positions itself at the T-intersection when both dividers are visible,
 * or centered on a single divider otherwise.
 */
class SplitHandleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    enum class Mode {
        BOTH,           // T-intersection: drag adjusts both dividers
        VERTICAL_ONLY,  // Only vertical divider (file tree | terminal)
        HORIZONTAL_ONLY,// Only horizontal divider (editor | terminal)
        NONE,           // Both panels hidden: tap/drag to restore
    }

    var mode = Mode.BOTH
        set(value) {
            field = value
            invalidate()
        }

    var onDragVertical: ((Float) -> Unit)? = null
    var onDragHorizontal: ((Float) -> Unit)? = null
    var onFlingLeft: (() -> Unit)? = null
    var onFlingUp: (() -> Unit)? = null
    var onFlingRight: (() -> Unit)? = null
    var onFlingDown: (() -> Unit)? = null
    var onTap: (() -> Unit)? = null
    var onDoubleTap: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private var dragging = false
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private val flingMinDistance = 20f * density
    private val flingMaxDuration = 500L // ms
    // Track extremes to find the "recoil" turnaround point
    private var maxX = 0f
    private var minX = 0f
    private var maxY = 0f
    private var minY = 0f
    private val tapSlopSq = (8f * density) * (8f * density)
    private var lastTapTime = 0L
    private val doubleTapTimeout = 300L

    private val handleRadius = 14f * density
    private val innerRadius = 10f * density

    private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF505050.toInt()
    }
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF707070.toInt()
    }
    private val outerHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF606060.toInt()
    }
    private val innerHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFA0A0A0.toInt()
    }

    // Arrow indicators
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB0B0B0.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val arrowSize = 4f * density

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val oPaint = if (dragging) outerHighlightPaint else outerPaint
        val iPaint = if (dragging) innerHighlightPaint else innerPaint

        // Outer circle
        canvas.drawCircle(cx, cy, handleRadius, oPaint)
        // Inner circle
        canvas.drawCircle(cx, cy, innerRadius, iPaint)

        // Draw directional arrows based on mode
        val aPaint = arrowPaint
        when (mode) {
            Mode.BOTH -> {
                // T-junction: up + down + right (file tree is to the left,
                // so left arrow is not meaningful — we control vertical divider
                // by dragging right, and horizontal divider by dragging up/down)
                drawArrow(canvas, cx, cy - arrowSize * 1.2f, 0f, -1f, aPaint) // up
                drawArrow(canvas, cx, cy + arrowSize * 1.2f, 0f, 1f, aPaint)  // down
                drawArrow(canvas, cx + arrowSize * 1.2f, cy, 1f, 0f, aPaint)  // right
            }
            Mode.VERTICAL_ONLY -> {
                // Left-right arrows
                drawArrow(canvas, cx - arrowSize * 1.2f, cy, -1f, 0f, aPaint)
                drawArrow(canvas, cx + arrowSize * 1.2f, cy, 1f, 0f, aPaint)
            }
            Mode.HORIZONTAL_ONLY -> {
                // T-junction facing right: up + down + right (no left panel)
                drawArrow(canvas, cx, cy - arrowSize * 1.2f, 0f, -1f, aPaint) // up
                drawArrow(canvas, cx, cy + arrowSize * 1.2f, 0f, 1f, aPaint)  // down
                drawArrow(canvas, cx + arrowSize * 1.2f, cy, 1f, 0f, aPaint)  // right
            }
            Mode.NONE -> {
                // Right and down arrows (expand)
                drawArrow(canvas, cx + arrowSize * 1.2f, cy, 1f, 0f, aPaint)  // right
                drawArrow(canvas, cx, cy + arrowSize * 1.2f, 0f, 1f, aPaint)  // down
            }
        }
    }

    private fun drawArrow(canvas: Canvas, cx: Float, cy: Float, dx: Float, dy: Float, paint: Paint) {
        val len = arrowSize
        // Arrow tip at (cx, cy), pointing in (dx, dy) direction
        val tipX = cx + dx * len * 0.5f
        val tipY = cy + dy * len * 0.5f
        val baseX = cx - dx * len * 0.5f
        val baseY = cy - dy * len * 0.5f
        canvas.drawLine(baseX, baseY, tipX, tipY, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                lastX = event.rawX
                lastY = event.rawY
                downX = event.rawX
                downY = event.rawY
                maxX = event.rawX
                minX = event.rawX
                maxY = event.rawY
                minY = event.rawY
                downTime = System.currentTimeMillis()
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    val dx = event.rawX - lastX
                    val dy = event.rawY - lastY
                    lastX = event.rawX
                    lastY = event.rawY
                    if (event.rawX > maxX) maxX = event.rawX
                    if (event.rawX < minX) minX = event.rawX
                    if (event.rawY > maxY) maxY = event.rawY
                    if (event.rawY < minY) minY = event.rawY
                    when (mode) {
                        Mode.BOTH -> {
                            if (dx != 0f) onDragVertical?.invoke(dx)
                            if (dy != 0f) onDragHorizontal?.invoke(dy)
                        }
                        Mode.VERTICAL_ONLY -> {
                            if (dx != 0f) onDragVertical?.invoke(dx)
                        }
                        Mode.HORIZONTAL_ONLY -> {
                            if (dy != 0f) onDragHorizontal?.invoke(dy)
                        }
                        Mode.NONE -> { /* no drag resize when both hidden */ }
                    }
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                var flung = false
                if (dragging) {
                    // Fling detection: measure from the turnaround point to end.
                    // For a leftward fling, the turnaround is the rightmost point reached;
                    // this handles "recoil" gestures where the finger starts in the
                    // opposite direction before flinging.
                    val endX = event.rawX
                    val endY = event.rawY
                    // Pick the extreme that gives the largest displacement toward end
                    val fromX = if (endX <= (minX + maxX) / 2f) maxX else minX
                    val fromY = if (endY <= (minY + maxY) / 2f) maxY else minY
                    val dx = endX - fromX
                    val dy = endY - fromY
                    val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                    val duration = System.currentTimeMillis() - downTime
                    if (dist > flingMinDistance && duration < flingMaxDuration) {
                        val absDx = kotlin.math.abs(dx)
                        val absDy = kotlin.math.abs(dy)
                        // Dominant axis wins; 2:1 ratio ≈ 63° cone from axis
                        if (absDy > absDx * 0.5f && absDy >= absDx) {
                            if (dy < 0) { onFlingUp?.invoke(); flung = true }
                            else { onFlingDown?.invoke(); flung = true }
                        } else if (absDx > absDy * 0.5f && absDx >= absDy) {
                            if (dx < 0) { onFlingLeft?.invoke(); flung = true }
                            else { onFlingRight?.invoke(); flung = true }
                        }
                    }
                    // Detect tap: no significant movement from start
                    val tapDx = endX - downX
                    val tapDy = endY - downY
                    if (!flung && tapDx * tapDx + tapDy * tapDy < tapSlopSq) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime < doubleTapTimeout) {
                            onDoubleTap?.invoke()
                            lastTapTime = 0L
                        } else {
                            lastTapTime = now
                            postDelayed({
                                if (lastTapTime == now) onTap?.invoke()
                            }, doubleTapTimeout)
                        }
                    }
                }
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
