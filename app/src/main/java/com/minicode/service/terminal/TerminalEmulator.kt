package com.minicode.service.terminal

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.locks.ReentrantLock

class TerminalEmulator(
    columns: Int,
    rows: Int,
    maxScrollback: Int = 10000,
) {
    // Lock for thread safety between IO reader thread (processBytes) and main thread (resize/draw)
    val lock = ReentrantLock()

    val buffer = TerminalBuffer(columns, rows, maxScrollback)
    var cursorRow = 0
        private set
    var cursorCol = 0
        private set
    var cursorVisible = true
        private set

    // Current text attributes
    private var currentFg = DEFAULT_FG
    private var currentBg = DEFAULT_BG
    private var currentBold = false
    private var currentItalic = false
    private var currentUnderline = false
    private var currentInverse = false
    private var currentDim = false

    // True-color storage: -1 = use palette index, otherwise direct ARGB
    private var currentFgTrue = -1
    private var currentBgTrue = -1

    // Scroll region
    private var scrollTop = 0
    private var scrollBottom = rows - 1

    // Saved cursor state
    private var savedCursorRow = 0
    private var savedCursorCol = 0
    private var savedFg = DEFAULT_FG
    private var savedBg = DEFAULT_BG
    private var savedBold = false
    private var savedItalic = false
    private var savedUnderline = false

    // Saved state for alt buffer switch (scroll regions, modes)
    private var savedAltScrollTop = 0
    private var savedAltScrollBottom = 0
    private var savedAltOriginMode = false
    private var savedAltAutoWrapMode = true

    // Parser state
    private var state = ParseState.GROUND
    private val escParams = StringBuilder()
    private val oscString = StringBuilder()

    // Modes
    private var originMode = false
    private var autoWrapMode = true
    private var bracketedPasteMode = false
    private var applicationCursorKeys = false
    private var synchronizedOutput = false
    var title: String = ""
        private set

    // Pending wrap: next char triggers wrap + print
    private var pendingWrap = false

    // Listeners
    var onUpdate: (() -> Unit)? = null
    var onAltBufferChanged: ((Boolean) -> Unit)? = null
    var onCwdChanged: ((String) -> Unit)? = null
    var onTitleChanged: ((String) -> Unit)? = null
    var onBell: (() -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var updatePending = false

    val columns get() = buffer.columns
    val rows get() = buffer.rows

    private enum class ParseState {
        GROUND,
        ESCAPE,
        CSI,
        OSC,
        OSC_ESC,
        DCS,
        CHARSET,
    }

    // UTF-8 decoder that carries incomplete multi-byte sequences across read() calls
    private val utf8Decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private val decodeOutBuf = CharBuffer.allocate(8192)

    fun processBytes(data: ByteArray, offset: Int, length: Int) {
        lock.lock()
        try {
            val inBuf = ByteBuffer.wrap(data, offset, length)
            decodeOutBuf.clear()
            utf8Decoder.decode(inBuf, decodeOutBuf, false)
            decodeOutBuf.flip()
            for (i in 0 until decodeOutBuf.remaining()) {
                processChar(decodeOutBuf.get(i))
            }
        } finally {
            lock.unlock()
        }
        scheduleUpdate()
    }

    fun processString(text: String) {
        lock.lock()
        try {
            for (ch in text) {
                processChar(ch)
            }
        } finally {
            lock.unlock()
        }
        scheduleUpdate()
    }

    private fun scheduleUpdate() {
        if (synchronizedOutput) return // Defer updates during synchronized output
        if (!updatePending) {
            updatePending = true
            mainHandler.post {
                updatePending = false
                onUpdate?.invoke()
            }
        }
    }

    private fun processChar(ch: Char) {
        when (state) {
            ParseState.GROUND -> processGround(ch)
            ParseState.ESCAPE -> processEscape(ch)
            ParseState.CSI -> processCsi(ch)
            ParseState.OSC -> processOsc(ch)
            ParseState.OSC_ESC -> {
                if (ch == '\\') {
                    handleOsc()
                    state = ParseState.GROUND
                } else {
                    state = ParseState.GROUND
                }
            }
            ParseState.DCS -> {
                if (ch == '\u001b') state = ParseState.ESCAPE
                else if (ch == '\u0007') state = ParseState.GROUND
                // Ignore DCS content
            }
            ParseState.CHARSET -> {
                // Consume the charset designation character and return to ground
                state = ParseState.GROUND
            }
        }
    }

    private fun processGround(ch: Char) {
        when {
            ch == '\u001b' -> state = ParseState.ESCAPE
            ch == '\n' || ch == '\u000B' || ch == '\u000C' -> lineFeed()
            ch == '\r' -> { cursorCol = 0; pendingWrap = false }
            ch == '\b' -> { if (cursorCol > 0) cursorCol--; pendingWrap = false }
            ch == '\t' -> {
                val nextTab = ((cursorCol / 8) + 1) * 8
                cursorCol = minOf(nextTab, buffer.columns - 1)
                pendingWrap = false
            }
            ch == '\u0007' -> { mainHandler.post { onBell?.invoke() } }
            ch == '\u000E' || ch == '\u000F' -> { /* Shift in/out - ignore */ }
            ch.code == 0 -> { /* NUL - ignore */ }
            ch.code < 32 -> { /* Other control chars - ignore */ }
            else -> printChar(ch)
        }
    }

    private fun processEscape(ch: Char) {
        when (ch) {
            '[' -> { state = ParseState.CSI; escParams.clear() }
            ']' -> { state = ParseState.OSC; oscString.clear() }
            'P' -> { state = ParseState.DCS }
            '(' , ')' , '*' , '+' -> { state = ParseState.CHARSET }
            'M' -> { reverseIndex(); state = ParseState.GROUND }
            'D' -> { lineFeed(); state = ParseState.GROUND }
            'E' -> { cursorCol = 0; lineFeed(); state = ParseState.GROUND }
            '7' -> { saveCursor(); state = ParseState.GROUND }
            '8' -> { restoreCursor(); state = ParseState.GROUND }
            'c' -> { fullReset(); state = ParseState.GROUND }
            '=' , '>' -> { state = ParseState.GROUND } // Keypad mode
            '#' -> { state = ParseState.GROUND } // DEC test
            '\\' -> { state = ParseState.GROUND } // ST
            else -> state = ParseState.GROUND
        }
    }

    private fun processCsi(ch: Char) {
        when {
            ch in '0'..'9' || ch == ';' || ch == '?' || ch == '>' || ch == '!' || ch == ' ' || ch == '"' || ch == '\'' -> {
                escParams.append(ch)
            }
            else -> {
                handleCsi(ch)
                state = ParseState.GROUND
            }
        }
    }

    private fun processOsc(ch: Char) {
        when {
            ch == '\u0007' -> { handleOsc(); state = ParseState.GROUND }
            ch == '\u001b' -> state = ParseState.OSC_ESC
            else -> oscString.append(ch)
        }
    }

    private fun printChar(ch: Char) {
        if (pendingWrap) {
            pendingWrap = false
            cursorCol = 0
            lineFeed()
        }
        val fg = if (currentFgTrue != -1) currentFgTrue else currentFg
        val bg = if (currentBgTrue != -1) currentBgTrue else currentBg
        buffer.setChar(cursorRow, cursorCol, ch, fg, bg, currentBold, currentItalic, currentUnderline, currentInverse, currentDim)
        if (cursorCol < buffer.columns - 1) {
            cursorCol++
        } else if (autoWrapMode) {
            pendingWrap = true
        }
    }

    private fun lineFeed() {
        if (cursorRow == scrollBottom) {
            buffer.scrollUp(scrollTop, scrollBottom)
        } else if (cursorRow < buffer.rows - 1) {
            cursorRow++
        }
    }

    private fun reverseIndex() {
        if (cursorRow == scrollTop) {
            buffer.scrollDown(scrollTop, scrollBottom)
        } else if (cursorRow > 0) {
            cursorRow--
        }
    }

    private fun parseParams(): IntArray {
        val raw = escParams.toString().removePrefix("?").removePrefix(">").removePrefix("!")
        if (raw.isEmpty()) return intArrayOf()
        return raw.split(';').map { it.toIntOrNull() ?: 0 }.toIntArray()
    }

    private fun param(params: IntArray, index: Int, default: Int = 0): Int {
        return if (index < params.size && params[index] != 0) params[index] else default
    }

    private fun handleCsi(final: Char) {
        val raw = escParams.toString()
        val isPrivate = raw.startsWith("?")
        val params = parseParams()

        when (final) {
            'A' -> { cursorRow = maxOf(cursorRow - param(params, 0, 1), if (cursorRow >= scrollTop) scrollTop else 0); pendingWrap = false } // CUU
            'B' -> { cursorRow = minOf(cursorRow + param(params, 0, 1), buffer.rows - 1); pendingWrap = false } // CUD
            'C' -> { cursorCol = minOf(cursorCol + param(params, 0, 1), buffer.columns - 1); pendingWrap = false } // CUF
            'D' -> { cursorCol = maxOf(cursorCol - param(params, 0, 1), 0); pendingWrap = false } // CUB
            'E' -> { cursorCol = 0; cursorRow = minOf(cursorRow + param(params, 0, 1), buffer.rows - 1); pendingWrap = false } // CNL
            'F' -> { cursorCol = 0; cursorRow = maxOf(cursorRow - param(params, 0, 1), 0); pendingWrap = false } // CPL
            'G' -> { cursorCol = maxOf(0, minOf(param(params, 0, 1) - 1, buffer.columns - 1)); pendingWrap = false } // CHA
            'H', 'f' -> { // CUP
                cursorRow = maxOf(0, minOf(param(params, 0, 1) - 1, buffer.rows - 1))
                cursorCol = maxOf(0, minOf(param(params, 1, 1) - 1, buffer.columns - 1))
                pendingWrap = false
            }
            'J' -> handleEraseDisplay(param(params, 0, 0)) // ED
            'K' -> handleEraseLine(param(params, 0, 0)) // EL
            'L' -> buffer.insertLines(cursorRow, param(params, 0, 1), scrollBottom) // IL
            'M' -> buffer.deleteLines(cursorRow, param(params, 0, 1), scrollBottom) // DL
            'P' -> buffer.deleteChars(cursorRow, cursorCol, param(params, 0, 1)) // DCH
            '@' -> buffer.insertChars(cursorRow, cursorCol, param(params, 0, 1)) // ICH
            'X' -> { // ECH
                val n = param(params, 0, 1)
                for (i in cursorCol until minOf(cursorCol + n, buffer.columns)) {
                    buffer.setChar(cursorRow, i, ' ', currentFg, currentBg, false, false, false, false, false)
                }
            }
            'S' -> { // SU (scroll up)
                val n = param(params, 0, 1)
                for (i in 0 until n) buffer.scrollUp(scrollTop, scrollBottom)
            }
            'T' -> { // SD (scroll down)
                val n = param(params, 0, 1)
                for (i in 0 until n) buffer.scrollDown(scrollTop, scrollBottom)
            }
            'd' -> { cursorRow = maxOf(0, minOf(param(params, 0, 1) - 1, buffer.rows - 1)); pendingWrap = false } // VPA
            'm' -> handleSgr(params) // SGR
            'r' -> { // DECSTBM
                scrollTop = maxOf(0, param(params, 0, 1) - 1)
                scrollBottom = minOf(param(params, 1, buffer.rows) - 1, buffer.rows - 1)
                cursorRow = if (originMode) scrollTop else 0
                cursorCol = 0
                pendingWrap = false
            }
            'h' -> handleMode(params, true, isPrivate) // SM
            'l' -> handleMode(params, false, isPrivate) // RM
            'n' -> handleDeviceStatus(params) // DSR
            'c' -> { /* DA - ignore */ }
            's' -> saveCursor()
            'u' -> restoreCursor()
            't' -> { /* Window manipulation - ignore */ }
            else -> { /* Unknown CSI */ }
        }
    }

    private fun handleEraseDisplay(mode: Int) {
        when (mode) {
            0 -> { // Erase below
                buffer.clearLine(cursorRow, cursorCol)
                for (row in cursorRow + 1 until buffer.rows) buffer.clearLine(row)
            }
            1 -> { // Erase above
                for (row in 0 until cursorRow) buffer.clearLine(row)
                buffer.clearLine(cursorRow, 0, cursorCol + 1)
            }
            2, 3 -> buffer.clearAll() // Erase all
        }
    }

    private fun handleEraseLine(mode: Int) {
        when (mode) {
            0 -> buffer.clearLine(cursorRow, cursorCol) // Erase to right
            1 -> buffer.clearLine(cursorRow, 0, cursorCol + 1) // Erase to left
            2 -> buffer.clearLine(cursorRow) // Erase entire line
        }
    }

    private fun handleSgr(params: IntArray) {
        if (params.isEmpty()) {
            resetAttributes()
            return
        }
        var i = 0
        while (i < params.size) {
            when (params[i]) {
                0 -> resetAttributes()
                1 -> currentBold = true
                2 -> currentDim = true
                3 -> currentItalic = true
                4 -> currentUnderline = true
                7 -> currentInverse = true
                22 -> { currentBold = false; currentDim = false }
                23 -> currentItalic = false
                24 -> currentUnderline = false
                27 -> currentInverse = false
                in 30..37 -> { currentFg = params[i] - 30; currentFgTrue = -1 }
                38 -> {
                    i = handleExtendedColor(params, i, true)
                }
                39 -> { currentFg = DEFAULT_FG; currentFgTrue = -1 }
                in 40..47 -> { currentBg = params[i] - 40; currentBgTrue = -1 }
                48 -> {
                    i = handleExtendedColor(params, i, false)
                }
                49 -> { currentBg = DEFAULT_BG; currentBgTrue = -1 }
                in 90..97 -> { currentFg = params[i] - 90 + 8; currentFgTrue = -1 }
                in 100..107 -> { currentBg = params[i] - 100 + 8; currentBgTrue = -1 }
            }
            i++
        }
    }

    private fun handleExtendedColor(params: IntArray, startIndex: Int, isFg: Boolean): Int {
        if (startIndex + 1 >= params.size) return startIndex
        return when (params[startIndex + 1]) {
            5 -> { // 256-color
                if (startIndex + 2 < params.size) {
                    val colorIndex = params[startIndex + 2]
                    if (isFg) { currentFg = colorIndex; currentFgTrue = -1 }
                    else { currentBg = colorIndex; currentBgTrue = -1 }
                    startIndex + 2
                } else startIndex + 1
            }
            2 -> { // True color (24-bit)
                if (startIndex + 4 < params.size) {
                    val r = params[startIndex + 2]
                    val g = params[startIndex + 3]
                    val b = params[startIndex + 4]
                    val color = TerminalColors.trueColor(r, g, b)
                    if (isFg) { currentFgTrue = color; currentFg = DEFAULT_FG }
                    else { currentBgTrue = color; currentBg = DEFAULT_BG }
                    startIndex + 4
                } else startIndex + 1
            }
            else -> startIndex + 1
        }
    }

    private fun resetAttributes() {
        currentFg = DEFAULT_FG
        currentBg = DEFAULT_BG
        currentFgTrue = -1
        currentBgTrue = -1
        currentBold = false
        currentItalic = false
        currentUnderline = false
        currentInverse = false
        currentDim = false
    }

    private fun handleMode(params: IntArray, enable: Boolean, isPrivate: Boolean) {
        for (p in params) {
            if (isPrivate) {
                when (p) {
                    1 -> applicationCursorKeys = enable // DECCKM
                    6 -> originMode = enable // DECOM
                    7 -> autoWrapMode = enable // DECAWM
                    12 -> { /* Blinking cursor - ignore */ }
                    25 -> cursorVisible = enable // DECTCEM
                    47, 1047 -> {
                        val wasAlt = buffer.isAltBuffer
                        if (enable) {
                            savedAltScrollTop = scrollTop
                            savedAltScrollBottom = scrollBottom
                            savedAltOriginMode = originMode
                            savedAltAutoWrapMode = autoWrapMode
                            buffer.enableAltBuffer()
                            scrollTop = 0
                            scrollBottom = buffer.rows - 1
                        } else {
                            buffer.disableAltBuffer()
                            scrollTop = savedAltScrollTop
                            scrollBottom = savedAltScrollBottom
                            originMode = savedAltOriginMode
                            autoWrapMode = savedAltAutoWrapMode
                        }
                        if (buffer.isAltBuffer != wasAlt) onAltBufferChanged?.invoke(enable)
                    }
                    1049 -> { // Alt screen + save/restore cursor
                        val wasAlt = buffer.isAltBuffer
                        if (enable) {
                            saveCursor()
                            savedAltScrollTop = scrollTop
                            savedAltScrollBottom = scrollBottom
                            savedAltOriginMode = originMode
                            savedAltAutoWrapMode = autoWrapMode
                            buffer.enableAltBuffer()
                            scrollTop = 0
                            scrollBottom = buffer.rows - 1
                        } else {
                            buffer.disableAltBuffer()
                            restoreCursor()
                            scrollTop = savedAltScrollTop
                            scrollBottom = savedAltScrollBottom
                            originMode = savedAltOriginMode
                            autoWrapMode = savedAltAutoWrapMode
                        }
                        if (buffer.isAltBuffer != wasAlt) onAltBufferChanged?.invoke(enable)
                    }
                    2004 -> bracketedPasteMode = enable
                    2026 -> {
                        synchronizedOutput = enable
                        if (!enable) scheduleUpdate() // Flush display when sync ends
                    }
                }
            }
        }
    }

    private fun handleDeviceStatus(params: IntArray) {
        // DSR responses would need to be written back to SSH
        // Handled via onDeviceStatusReport callback
    }

    private fun handleOsc() {
        val str = oscString.toString()
        val semicolonIndex = str.indexOf(';')
        if (semicolonIndex > 0) {
            val cmd = str.substring(0, semicolonIndex).toIntOrNull() ?: return
            val arg = str.substring(semicolonIndex + 1)
            when (cmd) {
                0, 2 -> {
                    title = arg
                    Log.d("TerminalEmulator", "OSC title set: '$arg', callback=${onTitleChanged != null}")
                    onTitleChanged?.invoke(arg)
                }
                7 -> {
                    // OSC 7: Current working directory
                    // Format: file://hostname/path or just /path
                    val path = if (arg.startsWith("file://")) {
                        val afterScheme = arg.removePrefix("file://")
                        val slashIdx = afterScheme.indexOf('/')
                        if (slashIdx >= 0) afterScheme.substring(slashIdx) else afterScheme
                    } else arg
                    if (path.startsWith("/")) {
                        onCwdChanged?.invoke(path)
                    }
                }
            }
        }
    }

    private fun saveCursor() {
        savedCursorRow = cursorRow
        savedCursorCol = cursorCol
        savedFg = currentFg
        savedBg = currentBg
        savedBold = currentBold
        savedItalic = currentItalic
        savedUnderline = currentUnderline
    }

    private fun restoreCursor() {
        cursorRow = savedCursorRow
        cursorCol = savedCursorCol
        currentFg = savedFg
        currentBg = savedBg
        currentBold = savedBold
        currentItalic = savedItalic
        currentUnderline = savedUnderline
        pendingWrap = false
    }

    private fun fullReset() {
        resetAttributes()
        cursorRow = 0
        cursorCol = 0
        cursorVisible = true
        scrollTop = 0
        scrollBottom = buffer.rows - 1
        originMode = false
        autoWrapMode = true
        bracketedPasteMode = false
        applicationCursorKeys = false
        pendingWrap = false
        buffer.clearAll()
    }

    fun resize(newCols: Int, newRows: Int) {
        lock.lock()
        try {
            val removedFromTop = buffer.resize(newCols, newRows, cursorRow)
            cursorRow = (cursorRow - removedFromTop).coerceIn(0, newRows - 1)
            scrollTop = 0
            scrollBottom = newRows - 1
            cursorCol = minOf(cursorCol, newCols - 1)
        } finally {
            lock.unlock()
        }
    }

    fun isApplicationCursorKeys(): Boolean = applicationCursorKeys
    fun isBracketedPasteMode(): Boolean = bracketedPasteMode

    fun resolveColor(index: Int, trueColor: Int, isBold: Boolean, isDefaultFg: Boolean = false): Int {
        if (trueColor != -1) return trueColor
        if (isDefaultFg && index == DEFAULT_FG) return TerminalColors.DEFAULT_FG_COLOR
        if (index == DEFAULT_BG) return TerminalColors.DEFAULT_BG_COLOR
        return TerminalColors.indexToColor(index, isBold)
    }

    fun getCellFgColor(cell: TerminalCell): Int {
        val trueColor = if (cell.fg < 0) cell.fg else -1
        val index = if (cell.fg >= 0) cell.fg else DEFAULT_FG
        return resolveColor(index, trueColor, cell.bold, isDefaultFg = true)
    }

    fun getCellBgColor(cell: TerminalCell): Int {
        val trueColor = if (cell.bg < 0) cell.bg else -1
        val index = if (cell.bg >= 0) cell.bg else DEFAULT_BG
        return resolveColor(index, trueColor, false)
    }
}
