package com.minicode.service.terminal

object TerminalColors {
    // Standard 16 colors (0-15): matches xterm defaults
    val PALETTE_16 = intArrayOf(
        // Normal (0-7)
        0xFF000000.toInt(), // 0: Black
        0xFFCD3131.toInt(), // 1: Red
        0xFF0DBC79.toInt(), // 2: Green
        0xFFE5E510.toInt(), // 3: Yellow
        0xFF2472C8.toInt(), // 4: Blue
        0xFFBC3FBC.toInt(), // 5: Magenta
        0xFF11A8CD.toInt(), // 6: Cyan
        0xFFE5E5E5.toInt(), // 7: White
        // Bright (8-15)
        0xFF666666.toInt(), // 8: Bright Black
        0xFFF14C4C.toInt(), // 9: Bright Red
        0xFF23D18B.toInt(), // 10: Bright Green
        0xFFF5F543.toInt(), // 11: Bright Yellow
        0xFF3B8EEA.toInt(), // 12: Bright Blue
        0xFFD670D6.toInt(), // 13: Bright Magenta
        0xFF29B8DB.toInt(), // 14: Bright Cyan
        0xFFFFFFFF.toInt(), // 15: Bright White
    )

    // 256-color palette: 16 standard + 216 color cube + 24 grayscale
    val PALETTE_256: IntArray by lazy {
        val palette = IntArray(256)
        // Copy standard 16
        System.arraycopy(PALETTE_16, 0, palette, 0, 16)
        // 216 color cube (indices 16-231)
        for (r in 0..5) {
            for (g in 0..5) {
                for (b in 0..5) {
                    val index = 16 + 36 * r + 6 * g + b
                    val rv = if (r == 0) 0 else 55 + 40 * r
                    val gv = if (g == 0) 0 else 55 + 40 * g
                    val bv = if (b == 0) 0 else 55 + 40 * b
                    palette[index] = (0xFF shl 24) or (rv shl 16) or (gv shl 8) or bv
                }
            }
        }
        // 24 grayscale (indices 232-255)
        for (i in 0..23) {
            val v = 8 + 10 * i
            palette[232 + i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        palette
    }

    // Default terminal colors
    val DEFAULT_FG_COLOR = PALETTE_16[7]   // White
    val DEFAULT_BG_COLOR = 0xFF1E1E1E.toInt() // VS Code dark background
    val CURSOR_COLOR = 0xFFD4D4D4.toInt()

    fun indexToColor(index: Int, isBold: Boolean = false): Int {
        // Bold + color 0-7 → use bright variant (8-15)
        if (isBold && index in 0..7) {
            return PALETTE_16[index + 8]
        }
        return when {
            index < 16 -> PALETTE_16[index]
            index < 256 -> PALETTE_256[index]
            else -> PALETTE_16[7]
        }
    }

    fun trueColor(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    // Marker for true-color (24-bit): store as negative to distinguish from palette index
    const val TRUE_COLOR_FLAG = -0x1000000 // Flag to indicate direct RGB
}
