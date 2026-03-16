package com.minicode.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.InputType
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Magnifier
import android.widget.OverScroller
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.minicode.service.terminal.TerminalColors
import com.minicode.service.terminal.TerminalEmulator
import com.minicode.service.terminal.TerminalPathDetector

class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var emulator: TerminalEmulator? = null
        set(value) {
            field = value
            extViewportStart = 0
            lastScrollbackSize = value?.buffer?.getScrollbackSize() ?: 0
            value?.onUpdate = {
                val emu = emulator
                if (emu != null && scrollOffset > 0) {
                    val currentScrollback = emu.buffer.getScrollbackSize()
                    val delta = currentScrollback - lastScrollbackSize
                    if (delta > 0) {
                        scrollOffset += delta
                    }
                    lastScrollbackSize = currentScrollback
                } else if (emu != null) {
                    lastScrollbackSize = emu.buffer.getScrollbackSize()
                }
                postInvalidate()
            }
            requestLayout()
        }

    var onKeyInput: ((ByteArray) -> Unit)? = null
    var onPathTap: ((TerminalPathDetector.DetectedPath, String?) -> Unit)? = null

    // Modifier state from keyboard toolbar
    var ctrlDown = false
    var altDown = false

    // Viewport tracking for extended buffer mode
    private var extViewportStart = 0
    private var lastDisplayRows = 0

    var fontSize: Float = 14f
        set(value) {
            field = value
            updateFontMetrics()
            requestLayout()
        }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = fontSize * resources.displayMetrics.scaledDensity
    }

    private val bgPaint = Paint()
    private val cursorPaint = Paint().apply {
        color = TerminalColors.CURSOR_COLOR
    }
    private val underlinePaint = Paint().apply {
        strokeWidth = 1f * resources.displayMetrics.density
    }

    // Selection
    private val selectionPaint = Paint().apply {
        color = 0x6033B5E5.toInt() // semi-transparent blue
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF33B5E5.toInt()
    }
    private var selecting = false
    private var selStartRow = 0
    private var selStartCol = 0
    private var selEndRow = 0
    private var selEndCol = 0
    private var draggingHandle: Int = 0 // 0=none, 1=start, 2=end
    private val handleRadius get() = 12f * resources.displayMetrics.density
    private var magnifier: Magnifier? = null
    private val autoScrollHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var autoScrollDirection = 0 // -1=up, 0=none, 1=down
    private val autoScrollEdge get() = cellHeight * 2 // edge zone for auto-scroll

    private var cellWidth = 0f
    private var cellHeight = 0f
    private var fontAscent = 0f
    private var scrollOffset = 0 // Lines scrolled back (0 = bottom)
    private var lastScrollbackSize = 0 // Track scrollback growth to anchor view
    private var scrollAccumulator = 0f
    private val scroller = OverScroller(context)

    private val gestureDetector: GestureDetector

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        updateFontMetrics()

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (selecting) {
                    clearSelection()
                    return true
                }
                // Check if tapping on a file path
                if (onPathTap != null) {
                    val detected = detectPathAtTouch(e.x, e.y)
                    if (detected != null) {
                        val cwd = getCwdFromPrompt(e.y)
                        onPathTap?.invoke(detected, cwd)
                        return true
                    }
                }
                requestFocus()
                showKeyboard()
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (selecting) return false
                val emu = emulator ?: return false
                scroller.forceFinished(true)
                // Natural scrolling: swipe up = text moves up = see newer content
                scrollAccumulator -= dy
                val lineDelta = (scrollAccumulator / cellHeight).toInt()
                if (lineDelta != 0) {
                    scrollAccumulator -= lineDelta * cellHeight
                    val maxScroll = getMaxScrollOffset(emu)
                    val prev = scrollOffset
                    scrollOffset = (scrollOffset + lineDelta).coerceIn(0, maxScroll)
                    // Reset accumulator at bounds to prevent sticky feeling
                    if (scrollOffset == prev && (scrollOffset == 0 || scrollOffset == maxScroll)) {
                        scrollAccumulator = 0f
                    }
                    postInvalidateOnAnimation()
                }
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (selecting) return false
                val emu = emulator ?: return false
                val maxScroll = getMaxScrollOffset(emu)
                scroller.fling(
                    0, scrollOffset,
                    0, (velocityY / cellHeight).toInt(),
                    0, 0,
                    0, maxScroll,
                )
                postInvalidateOnAnimation()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (emulator == null) return
                startSelection(e.x, e.y)
            }
        })
        gestureDetector.setIsLongpressEnabled(true)
    }

    private fun updateFontMetrics() {
        textPaint.textSize = fontSize * resources.displayMetrics.scaledDensity
        val fm = textPaint.fontMetrics
        cellHeight = fm.descent - fm.ascent + fm.leading
        cellWidth = textPaint.measureText("M")
        fontAscent = -fm.ascent
    }

    fun calculateColumns(): Int = if (cellWidth > 0 && width > 0) (width / cellWidth).toInt().coerceAtLeast(1) else 0
    fun calculateRows(): Int = if (cellHeight > 0 && height > 0) (height / cellHeight).toInt().coerceAtLeast(1) else 0

    /** True when buffer has more rows than the screen (extended normal mode) */
    private fun isExtendedViewport(): Boolean {
        val emu = emulator ?: return false
        return !emu.buffer.isAltBuffer && emu.rows > calculateRows()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        scrollOffset = 0
        scrollAccumulator = 0f
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val emu = emulator ?: return
            val maxScroll = getMaxScrollOffset(emu)
            scrollOffset = scroller.currY.coerceIn(0, maxScroll)
            postInvalidateOnAnimation()
        }
    }

    private fun getMaxScrollOffset(emu: TerminalEmulator): Int {
        return if (isExtendedViewport()) {
            // Can scroll up from current viewport to row 0
            extViewportStart
        } else {
            emu.buffer.getScrollbackSize()
        }
    }

    // --- Selection logic ---

    private fun startSelection(x: Float, y: Float) {
        val rc = touchToCell(x, y)
        selecting = true
        selStartRow = rc.first
        selStartCol = rc.second
        selEndRow = rc.first
        selEndCol = rc.second
        // Select the word at the tap point
        selectWordAt(rc.first, rc.second)
        invalidate()
    }

    private fun selectWordAt(row: Int, col: Int) {
        val emu = emulator ?: return
        val cols = emu.columns
        // Get the text of this row
        fun getChar(c: Int): Char {
            val cell = getCellAt(row, c)
            return cell.char
        }
        // Expand left
        var left = col
        while (left > 0 && !getChar(left - 1).isWhitespace() && getChar(left - 1) != '\u0000') {
            left--
        }
        // Expand right
        var right = col
        while (right < cols - 1 && !getChar(right + 1).isWhitespace() && getChar(right + 1) != '\u0000') {
            right++
        }
        selStartRow = row
        selStartCol = left
        selEndRow = row
        selEndCol = right + 1 // exclusive end
    }

    private fun getCellAt(row: Int, col: Int): com.minicode.service.terminal.TerminalCell {
        val emu = emulator ?: return com.minicode.service.terminal.TerminalCell()
        val buf = emu.buffer
        if (isExtendedViewport()) {
            // Use extViewportStart which is kept in sync by onDraw
            val viewportStart = if (scrollOffset == 0) {
                extViewportStart
            } else {
                (extViewportStart - scrollOffset).coerceAtLeast(0)
            }
            return buf.getCell(viewportStart + row, col)
        }
        return if (scrollOffset > 0) {
            val scrollbackTotal = buf.getScrollbackSize()
            val actualScrollRow = scrollbackTotal - scrollOffset + row
            if (actualScrollRow < scrollbackTotal) {
                buf.getScrollbackCell(actualScrollRow, col)
            } else {
                buf.getCell(actualScrollRow - scrollbackTotal, col)
            }
        } else {
            buf.getCell(row, col)
        }
    }

    private fun touchToCell(x: Float, y: Float): Pair<Int, Int> {
        val emu = emulator ?: return Pair(0, 0)
        val col = (x / cellWidth).toInt().coerceIn(0, emu.columns - 1)
        val displayRows = if (isExtendedViewport()) calculateRows().coerceAtMost(emu.rows) else emu.rows
        val row = (y / cellHeight).toInt().coerceIn(0, displayRows - 1)
        return Pair(row, col)
    }

    private fun detectPathAtTouch(x: Float, y: Float): TerminalPathDetector.DetectedPath? {
        val emu = emulator ?: return null
        val (row, col) = touchToCell(x, y)
        val lineText = getRowText(row)
        val detected = TerminalPathDetector.detectAtPosition(lineText, col) ?: return null
        // If multiple paths on the line, return the one at tap position
        return detected
    }

    /**
     * Scan backwards from the given row to find the most recent shell prompt
     * and extract the working directory from it.
     * Matches patterns like: user@host:~/path$ or user@host:/abs/path$
     */
    fun getCwdFromPrompt(touchY: Float): String? {
        val emu = emulator ?: return null
        val (tapRow, _) = touchToCell(0f, touchY)
        // Scan from tap row backwards
        for (row in tapRow downTo 0) {
            val text = getRowText(row)
            val cwd = parsePromptCwd(text)
            if (cwd != null) return cwd
        }
        return null
    }

    companion object {
        // Matches user@host:path$ where path starts with ~ or /
        private val promptRegex = Regex("""[\w.+-]+@[\w.+-]+:(~[^\$\s]*|/[^\$\s]*)\$""")

        fun parsePromptCwd(line: String): String? {
            val match = promptRegex.find(line) ?: return null
            return match.groupValues[1]
        }
    }

    private fun getRowText(row: Int): String {
        val emu = emulator ?: return ""
        val cols = emu.columns
        val sb = StringBuilder()
        for (col in 0 until cols) {
            val cell = getCellAt(row, col)
            val ch = cell.char
            sb.append(if (ch == '\u0000') ' ' else ch)
        }
        // Trim trailing spaces
        return sb.trimEnd().toString()
    }

    @Suppress("DEPRECATION")
    private fun showMagnifier(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val mag = magnifier ?: run {
            val m = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Magnifier.Builder(this)
                    .setInitialZoom(1.5f)
                    .setCornerRadius(8f * resources.displayMetrics.density)
                    .setDefaultSourceToMagnifierOffset(0, -(64 * resources.displayMetrics.density).toInt())
                    .build()
            } else {
                Magnifier(this)
            }
            magnifier = m
            m
        }
        // Clamp source position to view bounds
        val sourceX = x.coerceIn(0f, width.toFloat())
        val sourceY = y.coerceIn(0f, height.toFloat())
        mag.show(sourceX, sourceY)
    }

    private fun dismissMagnifier() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        magnifier?.dismiss()
        magnifier = null
    }

    private val autoScrollRunnable = object : Runnable {
        override fun run() {
            if (draggingHandle == 0 || autoScrollDirection == 0) return
            val emu = emulator ?: return
            val maxScroll = getMaxScrollOffset(emu)
            val prevOffset = scrollOffset
            scrollOffset = (scrollOffset + autoScrollDirection).coerceIn(0, maxScroll)
            if (scrollOffset != prevOffset) {
                // Adjust both endpoints: the dragged one follows the finger,
                // the anchored one must compensate for viewport shift
                selStartRow -= autoScrollDirection
                selEndRow -= autoScrollDirection
                invalidate()
            }
            autoScrollHandler.postDelayed(this, 80) // ~12 lines/sec
        }
    }

    private fun startAutoScroll(y: Float) {
        val displayH = height.toFloat()
        val newDir = when {
            y < autoScrollEdge -> 1  // near top → scroll into history
            y > displayH - autoScrollEdge -> -1 // near bottom → scroll toward latest
            else -> 0
        }
        if (newDir != autoScrollDirection) {
            autoScrollHandler.removeCallbacks(autoScrollRunnable)
            autoScrollDirection = newDir
            if (newDir != 0) {
                autoScrollHandler.post(autoScrollRunnable)
            }
        }
    }

    private fun stopAutoScroll() {
        autoScrollDirection = 0
        autoScrollHandler.removeCallbacks(autoScrollRunnable)
    }

    fun clearSelection() {
        selecting = false
        draggingHandle = 0
        stopAutoScroll()
        dismissMagnifier()
        invalidate()
    }

    private fun getSelectedText(): String {
        val emu = emulator ?: return ""
        val sb = StringBuilder()
        val (startRow, startCol, endRow, endCol) = normalizeSelection()
        for (row in startRow..endRow) {
            val cStart = if (row == startRow) startCol else 0
            val cEnd = if (row == endRow) endCol else emu.columns
            for (col in cStart until cEnd) {
                val cell = getCellAt(row, col)
                val ch = cell.char
                if (ch != '\u0000') sb.append(ch)
            }
            // Trim trailing spaces on each line and add newline between rows
            if (row < endRow) {
                // Trim trailing spaces
                while (sb.isNotEmpty() && sb.last() == ' ') sb.deleteCharAt(sb.length - 1)
                sb.append('\n')
            }
        }
        // Trim trailing spaces from last line
        while (sb.isNotEmpty() && sb.last() == ' ') sb.deleteCharAt(sb.length - 1)
        return sb.toString()
    }

    /** Returns (startRow, startCol, endRow, endCol) normalized so start <= end */
    private fun normalizeSelection(): List<Int> {
        return if (selStartRow < selEndRow || (selStartRow == selEndRow && selStartCol <= selEndCol)) {
            listOf(selStartRow, selStartCol, selEndRow, selEndCol)
        } else {
            listOf(selEndRow, selEndCol, selStartRow, selStartCol)
        }
    }

    private fun copySelection() {
        val text = getSelectedText()
        if (text.isNotEmpty()) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
        }
        clearSelection()
    }

    // --- Handle hit testing ---

    private fun getHandleCenter(row: Int, col: Int, isStart: Boolean): Pair<Float, Float> {
        val x = col * cellWidth
        val y = if (isStart) row * cellHeight else (row + 1) * cellHeight
        return Pair(x, y)
    }

    private fun hitTestHandle(x: Float, y: Float): Int {
        val (norm) = normalizeSelection()
        val (sRow, sCol, eRow, eCol) = normalizeSelection()
        val (sx, sy) = getHandleCenter(sRow, sCol, true)
        val (ex, ey) = getHandleCenter(eRow, eCol, false)
        val r = handleRadius * 1.5f
        val dStart = (x - sx) * (x - sx) + (y - sy) * (y - sy)
        val dEnd = (x - ex) * (x - ex) + (y - ey) * (y - ey)
        if (dStart < r * r && dStart <= dEnd) return 1
        if (dEnd < r * r) return 2
        return 0
    }

    // --- Drawing ---

    override fun onDraw(canvas: Canvas) {
        val emu = emulator ?: return
        canvas.drawColor(TerminalColors.DEFAULT_BG_COLOR)
        // Try to acquire lock without blocking — if the IO thread is processing
        // heavy output, skip this frame to keep the UI thread responsive for
        // touch events (e.g. session tab switching). We'll draw next frame.
        if (!emu.lock.tryLock()) {
            postInvalidateOnAnimation()
            return
        }
        try {
        val cols = emu.columns
        val buf = emu.buffer
        val rect = RectF()
        val sel = if (selecting) normalizeSelection() else null

        val inExtViewport = isExtendedViewport()
        val displayRows = if (inExtViewport) calculateRows().coerceAtMost(emu.rows) else emu.rows
        val viewportStart: Int
        if (inExtViewport) {
            val maxStart = (emu.rows - displayRows).coerceAtLeast(0)
            val vs: Int
            if (scrollOffset == 0) {
                val cursorRow = emu.cursorRow
                if (displayRows != lastDisplayRows) {
                    // Viewport size changed (keyboard show/hide) — re-anchor cursor at bottom
                    vs = (cursorRow - displayRows + 1).coerceIn(0, maxStart)
                    lastDisplayRows = displayRows
                } else if (cursorRow in extViewportStart until extViewportStart + displayRows) {
                    // Stable viewport: cursor still visible — keep viewport stable
                    vs = extViewportStart.coerceIn(0, maxStart)
                } else if (cursorRow >= extViewportStart + displayRows) {
                    // Cursor moved below viewport — scroll down, cursor at bottom
                    vs = (cursorRow - displayRows + 1).coerceIn(0, maxStart)
                } else {
                    // Cursor moved above viewport — scroll up, cursor at bottom
                    vs = (cursorRow - displayRows + 1).coerceIn(0, maxStart)
                }
            } else {
                vs = (extViewportStart - scrollOffset).coerceAtLeast(0)
            }
            extViewportStart = if (scrollOffset == 0) vs else extViewportStart
            viewportStart = vs
        } else {
            viewportStart = 0
        }

        for (row in 0 until displayRows) {
            val y = row * cellHeight

            for (col in 0 until cols) {
                val cell = if (inExtViewport) {
                    buf.getCell(viewportStart + row, col)
                } else if (scrollOffset > 0) {
                    val scrollbackTotal = buf.getScrollbackSize()
                    val actualScrollRow = scrollbackTotal - scrollOffset + row
                    if (actualScrollRow < scrollbackTotal) {
                        buf.getScrollbackCell(actualScrollRow, col)
                    } else {
                        buf.getCell(actualScrollRow - scrollbackTotal, col)
                    }
                } else {
                    buf.getCell(row, col)
                }

                val x = col * cellWidth

                // Determine colors
                var fgColor: Int
                var bgColor: Int
                if (cell.inverse) {
                    fgColor = emu.getCellBgColor(cell)
                    bgColor = emu.getCellFgColor(cell)
                    // Ensure swapped colors are visible: dark-on-dark text becomes light
                    if (fgColor == TerminalColors.DEFAULT_BG_COLOR) fgColor = TerminalColors.DEFAULT_FG_COLOR
                } else {
                    fgColor = emu.getCellFgColor(cell)
                    bgColor = emu.getCellBgColor(cell)
                }

                if (cell.dim) {
                    fgColor = dimColor(fgColor)
                }

                // Draw background if not default
                if (bgColor != TerminalColors.DEFAULT_BG_COLOR) {
                    bgPaint.color = bgColor
                    rect.set(x, y, x + cellWidth, y + cellHeight)
                    canvas.drawRect(rect, bgPaint)
                }

                // Draw character
                if (cell.char != ' ' && cell.char != '\u0000') {
                    textPaint.color = fgColor
                    textPaint.isFakeBoldText = cell.bold
                    textPaint.textSkewX = if (cell.italic) -0.25f else 0f
                    canvas.drawText(cell.char.toString(), x, y + fontAscent, textPaint)
                }

                // Draw underline
                if (cell.underline) {
                    underlinePaint.color = fgColor
                    val underY = y + fontAscent + 2 * resources.displayMetrics.density
                    canvas.drawLine(x, underY, x + cellWidth, underY, underlinePaint)
                }

                // Draw selection highlight
                if (sel != null && isCellSelected(row, col, sel)) {
                    rect.set(x, y, x + cellWidth, y + cellHeight)
                    canvas.drawRect(rect, selectionPaint)
                }
            }
        }

        // Draw cursor
        if (emu.cursorVisible && !selecting) {
            var drawRow = -1
            var drawCol = emu.cursorCol
            if (inExtViewport) {
                val cursorScreenRow = emu.cursorRow - viewportStart
                if (cursorScreenRow in 0 until displayRows) {
                    drawRow = cursorScreenRow
                }
            } else if (scrollOffset == 0) {
                drawRow = emu.cursorRow
            }
            if (drawRow >= 0) {
                val cx = drawCol * cellWidth
                val cy = drawRow * cellHeight
                cursorPaint.color = TerminalColors.CURSOR_COLOR
                cursorPaint.alpha = 180
                rect.set(cx, cy, cx + cellWidth, cy + cellHeight)
                canvas.drawRect(rect, cursorPaint)
            }
        }

        // Draw selection handles
        if (selecting) {
            val (sRow, sCol, eRow, eCol) = normalizeSelection()
            // Start handle (top-left teardrop pointing up)
            val sx = sCol * cellWidth
            val sy = sRow * cellHeight
            canvas.drawCircle(sx, sy, handleRadius, handlePaint)
            canvas.drawRect(sx - handleRadius, sy - handleRadius, sx + 1f, sy + 1f, handlePaint)

            // End handle (bottom-right teardrop pointing down)
            val ex = eCol * cellWidth
            val ey = (eRow + 1) * cellHeight
            canvas.drawCircle(ex, ey, handleRadius, handlePaint)
            canvas.drawRect(ex - 1f, ey - 1f, ex + handleRadius, ey + handleRadius, handlePaint)

            // Draw "Copy" button above selection
            drawCopyButton(canvas, sRow, sCol, eCol)
        }

        // Draw scroll position indicator in extended viewport
        if (inExtViewport && scrollOffset > 0) {
            val maxScroll = (emu.rows - displayRows).coerceAtLeast(1)
            val viewHeight = displayRows * cellHeight
            val thumbH = (displayRows.toFloat() / emu.rows * viewHeight).coerceAtLeast(16f * resources.displayMetrics.density)
            val trackH = viewHeight - thumbH
            val thumbTop = trackH - (scrollOffset.toFloat() / maxScroll * trackH)
            val barW = 4f * resources.displayMetrics.density
            scrollBarPaint.color = 0x40FFFFFF.toInt()
            canvas.drawRoundRect(
                width - barW - 2f, thumbTop, width - 2f, thumbTop + thumbH,
                barW / 2, barW / 2, scrollBarPaint,
            )
        }
        } finally {
            emu.lock.unlock()
        }
    }

    private val scrollBarPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val copyBtnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF424242.toInt()
    }
    private val copyTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 14f * resources.displayMetrics.scaledDensity
        typeface = Typeface.DEFAULT_BOLD
    }
    private var copyBtnRect = RectF()
    private var pasteBtnRect = RectF()

    private fun drawCopyButton(canvas: Canvas, sRow: Int, sCol: Int, eCol: Int) {
        val copyText = "COPY"
        val pasteText = "PASTE"
        val padding = 16f * resources.displayMetrics.density
        val gap = 8f * resources.displayMetrics.density
        val copyW = copyTextPaint.measureText(copyText) + padding * 2
        val pasteW = copyTextPaint.measureText(pasteText) + padding * 2
        val totalW = copyW + gap + pasteW
        val btnH = 40f * resources.displayMetrics.density
        val fm = copyTextPaint.fontMetrics
        val cornerR = 6f * resources.displayMetrics.density

        // Position above the start of selection
        var startX = (sCol * cellWidth + eCol * cellWidth) / 2f - totalW / 2f
        var btnY = sRow * cellHeight - btnH - handleRadius - 4f * resources.displayMetrics.density
        if (btnY < 0) btnY = (sRow + 1) * cellHeight + handleRadius + 4f * resources.displayMetrics.density
        startX = startX.coerceIn(0f, width - totalW)

        // Draw COPY button
        copyBtnRect.set(startX, btnY, startX + copyW, btnY + btnH)
        canvas.drawRoundRect(copyBtnRect, cornerR, cornerR, copyBtnPaint)
        canvas.drawText(copyText, startX + padding, btnY + (btnH - fm.descent - fm.ascent) / 2f, copyTextPaint)

        // Draw PASTE button
        val pasteX = startX + copyW + gap
        pasteBtnRect.set(pasteX, btnY, pasteX + pasteW, btnY + btnH)
        canvas.drawRoundRect(pasteBtnRect, cornerR, cornerR, copyBtnPaint)
        canvas.drawText(pasteText, pasteX + padding, btnY + (btnH - fm.descent - fm.ascent) / 2f, copyTextPaint)
    }

    private fun isCellSelected(row: Int, col: Int, sel: List<Int>): Boolean {
        val (sRow, sCol, eRow, eCol) = sel
        if (row < sRow || row > eRow) return false
        if (sRow == eRow) return col in sCol until eCol
        if (row == sRow) return col >= sCol
        if (row == eRow) return col < eCol
        return true
    }

    private fun dimColor(color: Int): Int {
        val a = (color shr 24) and 0xFF
        val r = ((color shr 16) and 0xFF) / 2
        val g = ((color shr 8) and 0xFF) / 2
        val b = (color and 0xFF) / 2
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (selecting) {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Check if tapping COPY button
                    if (copyBtnRect.contains(event.x, event.y)) {
                        copySelection()
                        return true
                    }
                    // Check if tapping PASTE button
                    if (pasteBtnRect.contains(event.x, event.y)) {
                        clearSelection()
                        pasteFromClipboard()
                        return true
                    }
                    draggingHandle = hitTestHandle(event.x, event.y)
                    if (draggingHandle == 0) {
                        // Not on a handle — let gesture detector handle it (will clear on tap)
                        gestureDetector.onTouchEvent(event)
                        return true
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (draggingHandle != 0) {
                        val (row, col) = touchToCell(event.x, event.y)
                        val (sRow, sCol, eRow, eCol) = normalizeSelection()
                        if (draggingHandle == 1) {
                            // Moving start handle
                            if (row < eRow || (row == eRow && col < eCol)) {
                                selStartRow = row
                                selStartCol = col
                            }
                        } else {
                            // Moving end handle
                            val endCol = (col + 1).coerceAtMost(emulator?.columns ?: col + 1)
                            if (row > sRow || (row == sRow && endCol > sCol)) {
                                selEndRow = row
                                selEndCol = endCol
                            }
                        }
                        // Auto-scroll when dragging near top/bottom edge
                        startAutoScroll(event.y)
                        showMagnifier(event.x, event.y)
                        invalidate()
                        return true
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopAutoScroll()
                    dismissMagnifier()
                    draggingHandle = 0
                    return true
                }
            }
            return true
        }
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        return TerminalInputConnection(this, true)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (selecting) {
            clearSelection()
        }
        val bytes = keyEventToBytes(keyCode, event) ?: return super.onKeyDown(keyCode, event)
        onKeyInput?.invoke(bytes)
        if (scrollOffset > 0) {
            scrollOffset = 0
            invalidate()
        }
        return true
    }

    fun sendText(text: String) {
        if (selecting) clearSelection()
        if (scrollOffset > 0) {
            scrollOffset = 0
            invalidate()
        }
        if (text.length == 1) {
            val c = text[0]
            if (ctrlDown) {
                val ctrlByte = when {
                    c in 'a'..'z' -> (c.code - 0x60).toByte()
                    c in 'A'..'Z' -> (c.code - 0x40).toByte()
                    c in '@'..'_' -> (c.code - 0x40).toByte() // Ctrl+@=0x00, Ctrl+[=0x1B, Ctrl+]=0x1D
                    c == ' ' || c == '2' -> 0x00.toByte()
                    c == '/' -> 0x1F.toByte()
                    else -> null
                }
                if (ctrlByte != null) {
                    onKeyInput?.invoke(byteArrayOf(ctrlByte))
                    return
                }
            }
            if (altDown) {
                onKeyInput?.invoke(byteArrayOf(0x1b) + text.toByteArray(Charsets.UTF_8))
                return
            }
        }
        onKeyInput?.invoke(text.toByteArray(Charsets.UTF_8))
    }

    fun sendSpecialKey(key: SpecialKey) {
        if (selecting) clearSelection()
        val emu = emulator
        val appCursor = emu?.isApplicationCursorKeys() == true
        val bytes = when (key) {
            SpecialKey.TAB -> byteArrayOf(0x09)
            SpecialKey.ESCAPE -> byteArrayOf(0x1b)
            SpecialKey.ENTER -> byteArrayOf(0x0d)
            SpecialKey.UP -> if (appCursor) "\u001bOA".toByteArray() else "\u001b[A".toByteArray()
            SpecialKey.DOWN -> if (appCursor) "\u001bOB".toByteArray() else "\u001b[B".toByteArray()
            SpecialKey.RIGHT -> if (appCursor) "\u001bOC".toByteArray() else "\u001b[C".toByteArray()
            SpecialKey.LEFT -> if (appCursor) "\u001bOD".toByteArray() else "\u001b[D".toByteArray()
            SpecialKey.HOME -> "\u001b[H".toByteArray()
            SpecialKey.END -> "\u001b[F".toByteArray()
            SpecialKey.PAGE_UP -> "\u001b[5~".toByteArray()
            SpecialKey.PAGE_DOWN -> "\u001b[6~".toByteArray()
            SpecialKey.DELETE -> "\u001b[3~".toByteArray()
            SpecialKey.INSERT -> "\u001b[2~".toByteArray()
            SpecialKey.BACKSPACE -> byteArrayOf(0x7f)
        }
        if (scrollOffset > 0) {
            scrollOffset = 0
            invalidate()
        }
        onKeyInput?.invoke(bytes)
    }

    private fun keyEventToBytes(keyCode: Int, event: KeyEvent): ByteArray? {
        val emu = emulator
        val appCursor = emu?.isApplicationCursorKeys() == true

        when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> return byteArrayOf(0x0d)
            KeyEvent.KEYCODE_DEL -> return byteArrayOf(0x7f)
            KeyEvent.KEYCODE_FORWARD_DEL -> return "\u001b[3~".toByteArray()
            KeyEvent.KEYCODE_TAB -> return byteArrayOf(0x09)
            KeyEvent.KEYCODE_ESCAPE -> return byteArrayOf(0x1b)
            KeyEvent.KEYCODE_DPAD_UP -> return if (appCursor) "\u001bOA".toByteArray() else "\u001b[A".toByteArray()
            KeyEvent.KEYCODE_DPAD_DOWN -> return if (appCursor) "\u001bOB".toByteArray() else "\u001b[B".toByteArray()
            KeyEvent.KEYCODE_DPAD_RIGHT -> return if (appCursor) "\u001bOC".toByteArray() else "\u001b[C".toByteArray()
            KeyEvent.KEYCODE_DPAD_LEFT -> return if (appCursor) "\u001bOD".toByteArray() else "\u001b[D".toByteArray()
            KeyEvent.KEYCODE_MOVE_HOME -> return "\u001b[H".toByteArray()
            KeyEvent.KEYCODE_MOVE_END -> return "\u001b[F".toByteArray()
            KeyEvent.KEYCODE_PAGE_UP -> return "\u001b[5~".toByteArray()
            KeyEvent.KEYCODE_PAGE_DOWN -> return "\u001b[6~".toByteArray()
        }

        // Handle Ctrl+key from external keyboards (unicodeChar may be 0 when isCtrlPressed)
        if (ctrlDown || event.isCtrlPressed) {
            val baseChar = event.getUnicodeChar(0) // get char without modifiers
            if (baseChar != 0) {
                val ctrlByte = when {
                    baseChar in 0x61..0x7a -> (baseChar - 0x60).toByte() // a-z
                    baseChar in 0x41..0x5a -> (baseChar - 0x40).toByte() // A-Z
                    baseChar in 0x40..0x5f -> (baseChar - 0x40).toByte() // @[\]^_ (Ctrl+]=0x1D)
                    baseChar == 0x20 -> 0x00.toByte() // space
                    baseChar == 0x32 -> 0x00.toByte() // 2
                    baseChar == 0x33 -> 0x1b.toByte() // 3
                    baseChar == 0x34 -> 0x1c.toByte() // 4
                    baseChar == 0x35 -> 0x1d.toByte() // 5
                    baseChar == 0x36 -> 0x1e.toByte() // 6
                    baseChar == 0x37 -> 0x1f.toByte() // 7
                    baseChar == 0x38 -> 0x7f.toByte() // 8
                    baseChar == 0x2f -> 0x1f.toByte() // /
                    else -> null
                }
                if (ctrlByte != null) {
                    return if (altDown || event.isAltPressed) {
                        byteArrayOf(0x1b, ctrlByte)
                    } else {
                        byteArrayOf(ctrlByte)
                    }
                }
            }
        }

        val unicodeChar = event.unicodeChar
        if (unicodeChar != 0) {
            var ch = unicodeChar
            if (altDown || event.isAltPressed) {
                return byteArrayOf(0x1b) + ch.toChar().toString().toByteArray(Charsets.UTF_8)
            }
            return ch.toChar().toString().toByteArray(Charsets.UTF_8)
        }
        return null
    }

    fun pasteFromClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(context)?.toString() ?: return
        if (text.isEmpty()) return
        // Use bracketed paste mode if the terminal supports it
        val emu = emulator
        if (emu != null && emu.isBracketedPasteMode()) {
            onKeyInput?.invoke("\u001b[200~".toByteArray(Charsets.UTF_8))
            onKeyInput?.invoke(text.toByteArray(Charsets.UTF_8))
            onKeyInput?.invoke("\u001b[201~".toByteArray(Charsets.UTF_8))
        } else {
            onKeyInput?.invoke(text.toByteArray(Charsets.UTF_8))
        }
        if (scrollOffset > 0) {
            scrollOffset = 0
            invalidate()
        }
    }

    fun showKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    enum class SpecialKey {
        TAB, ESCAPE, ENTER, UP, DOWN, LEFT, RIGHT,
        HOME, END, PAGE_UP, PAGE_DOWN, DELETE, INSERT, BACKSPACE,
    }
}

private class TerminalInputConnection(
    private val terminalView: TerminalView,
    fullEditor: Boolean,
) : BaseInputConnection(terminalView, fullEditor) {

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (text != null) {
            terminalView.sendText(text.toString())
        }
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        for (i in 0 until beforeLength) {
            terminalView.onKeyInput?.invoke(byteArrayOf(0x7f))
        }
        return true
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            return terminalView.onKeyDown(event.keyCode, event)
        }
        return super.sendKeyEvent(event)
    }
}
