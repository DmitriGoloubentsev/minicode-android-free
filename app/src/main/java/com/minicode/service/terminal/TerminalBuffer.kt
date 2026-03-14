package com.minicode.service.terminal

data class TerminalCell(
    var char: Char = ' ',
    var fg: Int = DEFAULT_FG,
    var bg: Int = DEFAULT_BG,
    var bold: Boolean = false,
    var italic: Boolean = false,
    var underline: Boolean = false,
    var inverse: Boolean = false,
    var dim: Boolean = false,
) {
    fun reset() {
        char = ' '
        fg = DEFAULT_FG
        bg = DEFAULT_BG
        bold = false
        italic = false
        underline = false
        inverse = false
        dim = false
    }

    fun copyFrom(other: TerminalCell) {
        char = other.char
        fg = other.fg
        bg = other.bg
        bold = other.bold
        italic = other.italic
        underline = other.underline
        inverse = other.inverse
        dim = other.dim
    }
}

const val DEFAULT_FG = 7  // Index into color palette (white)
const val DEFAULT_BG = 0  // Index into color palette (black)

class TerminalBuffer(
    var columns: Int,
    var rows: Int,
    private val maxScrollback: Int = 10000,
) {
    // Active screen lines (rows)
    private val lines = ArrayList<Array<TerminalCell>>()
    // Scrollback buffer (lines that scrolled off top)
    private val scrollback = ArrayList<Array<TerminalCell>>()

    // Alternate screen buffer (for vim, less, etc.)
    private var altLines: ArrayList<Array<TerminalCell>>? = null
    private var savedMainLines: ArrayList<Array<TerminalCell>>? = null
    private var savedMainScrollback: ArrayList<Array<TerminalCell>>? = null
    var isAltBuffer = false
        private set

    init {
        for (i in 0 until rows) {
            lines.add(newLine())
        }
    }

    fun newLine(): Array<TerminalCell> = Array(columns) { TerminalCell() }

    fun getCell(row: Int, col: Int): TerminalCell {
        if (row < 0 || row >= lines.size || col < 0 || col >= columns) {
            return TerminalCell()
        }
        val line = lines.getOrNull(row) ?: return TerminalCell()
        return if (col < line.size) line[col] else TerminalCell()
    }

    fun setCell(row: Int, col: Int, cell: TerminalCell) {
        if (row < 0 || row >= lines.size || col < 0 || col >= columns) return
        ensureLineWidth(row)
        lines[row][col].copyFrom(cell)
    }

    fun setChar(row: Int, col: Int, ch: Char, fg: Int, bg: Int, bold: Boolean, italic: Boolean, underline: Boolean, inverse: Boolean, dim: Boolean) {
        if (row < 0 || row >= lines.size || col < 0 || col >= columns) return
        ensureLineWidth(row)
        lines[row][col].apply {
            this.char = ch
            this.fg = fg
            this.bg = bg
            this.bold = bold
            this.italic = italic
            this.underline = underline
            this.inverse = inverse
            this.dim = dim
        }
    }

    private fun ensureLineWidth(row: Int) {
        if (lines[row].size < columns) {
            val old = lines[row]
            val newArr = Array(columns) { i -> if (i < old.size) old[i] else TerminalCell() }
            lines[row] = newArr
        }
    }

    fun scrollUp(scrollTop: Int, scrollBottom: Int) {
        if (scrollTop < 0 || scrollBottom >= lines.size || scrollTop >= scrollBottom) return
        val removed = lines.removeAt(scrollTop)
        if (scrollTop == 0 && !isAltBuffer) {
            scrollback.add(removed)
            if (scrollback.size > maxScrollback) {
                scrollback.removeAt(0)
            }
        }
        lines.add(scrollBottom, newLine())
    }

    fun scrollDown(scrollTop: Int, scrollBottom: Int) {
        if (scrollTop < 0 || scrollBottom >= lines.size || scrollTop >= scrollBottom) return
        lines.removeAt(scrollBottom)
        lines.add(scrollTop, newLine())
    }

    fun clearLine(row: Int, startCol: Int = 0, endCol: Int = columns) {
        if (row < 0 || row >= lines.size) return
        ensureLineWidth(row)
        for (col in startCol until minOf(endCol, columns)) {
            lines[row][col].reset()
        }
    }

    fun clearAll() {
        for (row in 0 until lines.size) {
            clearLine(row)
        }
    }

    fun getScrollbackSize(): Int = if (isAltBuffer) 0 else scrollback.size

    fun getScrollbackCell(row: Int, col: Int): TerminalCell {
        if (row < 0 || row >= scrollback.size || col < 0) return TerminalCell()
        val line = scrollback[row]
        return if (col < line.size) line[col] else TerminalCell()
    }

    fun enableAltBuffer() {
        if (isAltBuffer) return
        savedMainLines = ArrayList(lines.map { line -> Array(line.size) { i -> TerminalCell().also { it.copyFrom(line[i]) } } })
        savedMainScrollback = ArrayList(scrollback)
        lines.clear()
        for (i in 0 until rows) {
            lines.add(newLine())
        }
        isAltBuffer = true
    }

    fun disableAltBuffer() {
        if (!isAltBuffer) return
        savedMainLines?.let {
            lines.clear()
            lines.addAll(it)
        }
        savedMainScrollback?.let {
            scrollback.clear()
            scrollback.addAll(it)
        }
        savedMainLines = null
        savedMainScrollback = null
        isAltBuffer = false
    }

    /**
     * Resize the buffer. Returns the number of lines removed from the top
     * (pushed to scrollback), so the caller can adjust the cursor position.
     */
    fun resize(newCols: Int, newRows: Int, cursorRow: Int = -1): Int {
        var removedFromTop = 0
        // Growing: add empty lines at the bottom
        while (lines.size < newRows) {
            lines.add(newLine())
        }
        // Shrinking: remove lines to get to newRows
        if (lines.size > newRows) {
            // First, try to remove empty lines from the bottom (below cursor)
            val effectiveCursor = if (cursorRow >= 0) cursorRow else lines.size - 1
            while (lines.size > newRows) {
                val lastIdx = lines.size - 1
                if (lastIdx > effectiveCursor && isLineEmpty(lines[lastIdx])) {
                    lines.removeAt(lastIdx)
                } else {
                    break
                }
            }
            // If still need to remove, push top lines to scrollback
            while (lines.size > newRows) {
                val removedLine = lines.removeAt(0)
                removedFromTop++
                if (!isAltBuffer) {
                    scrollback.add(removedLine)
                    if (scrollback.size > maxScrollback) {
                        scrollback.removeAt(0)
                    }
                }
            }
        }
        columns = newCols
        rows = newRows
        return removedFromTop
    }

    private fun isLineEmpty(line: Array<TerminalCell>): Boolean {
        return line.all { it.char == ' ' || it.char == '\u0000' }
    }

    fun insertLines(row: Int, count: Int, scrollBottom: Int) {
        val bottom = minOf(scrollBottom, lines.size - 1)
        for (i in 0 until count) {
            if (row <= bottom) {
                lines.removeAt(bottom)
                lines.add(row, newLine())
            }
        }
    }

    fun deleteLines(row: Int, count: Int, scrollBottom: Int) {
        val bottom = minOf(scrollBottom, lines.size - 1)
        for (i in 0 until count) {
            if (row <= bottom) {
                lines.removeAt(row)
                lines.add(bottom, newLine())
            }
        }
    }

    fun insertChars(row: Int, col: Int, count: Int) {
        if (row < 0 || row >= lines.size) return
        ensureLineWidth(row)
        val line = lines[row]
        // Shift cells right
        for (i in columns - 1 downTo col + count) {
            if (i - count >= 0) line[i].copyFrom(line[i - count])
        }
        for (i in col until minOf(col + count, columns)) {
            line[i].reset()
        }
    }

    fun deleteChars(row: Int, col: Int, count: Int) {
        if (row < 0 || row >= lines.size) return
        ensureLineWidth(row)
        val line = lines[row]
        for (i in col until columns) {
            if (i + count < columns) {
                line[i].copyFrom(line[i + count])
            } else {
                line[i].reset()
            }
        }
    }
}
