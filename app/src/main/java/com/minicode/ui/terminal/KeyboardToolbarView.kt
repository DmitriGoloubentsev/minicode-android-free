package com.minicode.ui.terminal

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.minicode.R

class KeyboardToolbarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    var onSpecialKey: ((TerminalView.SpecialKey) -> Unit)? = null
    var onCharKey: ((String) -> Unit)? = null
    var onCtrlToggle: ((Boolean) -> Unit)? = null
    var onAltToggle: ((Boolean) -> Unit)? = null
    var onPaste: (() -> Unit)? = null
    var onImagePaste: (() -> Unit)? = null
    var onFileUpload: (() -> Unit)? = null

    private var ctrlActive = false
    private var altActive = false
    private lateinit var ctrlButton: TextView
    private lateinit var altButton: TextView

    private val toolbarBg = 0xFF2D2D2D.toInt()
    private val keyBg = 0xFF3E3E3E.toInt()
    private val keyActiveBg = 0xFF007ACC.toInt()
    private val keyTextColor = 0xFFD4D4D4.toInt()
    private val toolbarHeight = (44 * resources.displayMetrics.density).toInt()
    private val keyMinWidth = (40 * resources.displayMetrics.density).toInt()
    private val keyMargin = (3 * resources.displayMetrics.density).toInt()
    private val keyPadding = (8 * resources.displayMetrics.density).toInt()
    private val keyRadius = 4 * resources.displayMetrics.density

    init {
        setBackgroundColor(toolbarBg)
        isHorizontalScrollBarEnabled = false
        isFillViewport = false

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(keyMargin, keyMargin, keyMargin, keyMargin)
        }

        // Modifiers
        ctrlButton = addToggleKey(container, "Ctrl") { active ->
            ctrlActive = active
            onCtrlToggle?.invoke(active)
        }
        altButton = addToggleKey(container, "Alt") { active ->
            altActive = active
            onAltToggle?.invoke(active)
        }

        // Image paste button
        addImageKey(container)
        // File upload button
        addFileUploadKey(container)

        addSeparator(container)

        // Navigation keys
        addSpecialKey(container, "Tab", TerminalView.SpecialKey.TAB)
        addSpecialKey(container, "Esc", TerminalView.SpecialKey.ESCAPE)
        addSpecialKey(container, "\u2191", TerminalView.SpecialKey.UP) // ↑
        addSpecialKey(container, "\u2193", TerminalView.SpecialKey.DOWN) // ↓
        addSpecialKey(container, "\u21B5", TerminalView.SpecialKey.ENTER) // ↵
        addSpecialKey(container, "\u2190", TerminalView.SpecialKey.LEFT) // ←
        addSpecialKey(container, "\u2192", TerminalView.SpecialKey.RIGHT) // →

        addSeparator(container)

        addSpecialKey(container, "Home", TerminalView.SpecialKey.HOME)
        addSpecialKey(container, "End", TerminalView.SpecialKey.END)
        addSpecialKey(container, "PgUp", TerminalView.SpecialKey.PAGE_UP)
        addSpecialKey(container, "PgDn", TerminalView.SpecialKey.PAGE_DOWN)

        addSeparator(container)

        // Special characters
        val specialChars = arrayOf("|", "/", "\\", "~", "`", "{", "}", "[", "]", "<", ">", "_", "-", "=", "+", "*", "&", "!", "@", "#", "$", "%", "^")
        for (ch in specialChars) {
            addCharKey(container, ch)
        }

        addView(container)
    }

    private fun makeKeyBackground(active: Boolean): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(if (active) keyActiveBg else keyBg)
            cornerRadius = keyRadius
        }
    }

    private fun addToggleKey(container: LinearLayout, label: String, onToggle: (Boolean) -> Unit): TextView {
        val tv = TextView(context).apply {
            text = label
            setTextColor(keyTextColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minWidth = keyMinWidth
            setPadding(keyPadding, 0, keyPadding, 0)
            background = makeKeyBackground(false)
            isClickable = true
            isFocusable = false

            setOnClickListener {
                val nowActive = !((tag as? Boolean) ?: false)
                tag = nowActive
                background = makeKeyBackground(nowActive)
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onToggle(nowActive)
            }
            tag = false
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            toolbarHeight - 2 * keyMargin,
        ).apply {
            setMargins(keyMargin, 0, keyMargin, 0)
        }
        container.addView(tv, lp)
        return tv
    }

    private fun addActionKey(container: LinearLayout, label: String, onClick: () -> Unit) {
        val tv = TextView(context).apply {
            text = label
            setTextColor(keyTextColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minWidth = keyMinWidth
            setPadding(keyPadding, 0, keyPadding, 0)
            background = makeKeyBackground(false)
            isClickable = true
            isFocusable = false

            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            toolbarHeight - 2 * keyMargin,
        ).apply {
            setMargins(keyMargin, 0, keyMargin, 0)
        }
        container.addView(tv, lp)
    }

    private fun addSpecialKey(container: LinearLayout, label: String, key: TerminalView.SpecialKey) {
        val tv = TextView(context).apply {
            text = label
            setTextColor(keyTextColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            minWidth = keyMinWidth
            setPadding(keyPadding, 0, keyPadding, 0)
            background = makeKeyBackground(false)
            isClickable = true
            isFocusable = false

            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onSpecialKey?.invoke(key)
                clearModifiers()
            }
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            toolbarHeight - 2 * keyMargin,
        ).apply {
            setMargins(keyMargin, 0, keyMargin, 0)
        }
        container.addView(tv, lp)
    }

    private fun addCharKey(container: LinearLayout, ch: String) {
        val tv = TextView(context).apply {
            text = ch
            setTextColor(keyTextColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            minWidth = keyMinWidth
            setPadding(keyPadding, 0, keyPadding, 0)
            background = makeKeyBackground(false)
            isClickable = true
            isFocusable = false

            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onCharKey?.invoke(ch)
                clearModifiers()
            }
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            toolbarHeight - 2 * keyMargin,
        ).apply {
            setMargins(keyMargin, 0, keyMargin, 0)
        }
        container.addView(tv, lp)
    }

    private fun addImageKey(container: LinearLayout) {
        val iv = ImageView(context).apply {
            setImageResource(R.drawable.ic_image)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = makeKeyBackground(false)
            isClickable = true
            isFocusable = false
            contentDescription = "Paste image"
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onImagePaste?.invoke()
            }
        }
        val size = toolbarHeight - 2 * keyMargin
        val lp = LinearLayout.LayoutParams(size, size).apply {
            setMargins(keyMargin, 0, keyMargin, 0)
        }
        container.addView(iv, lp)
    }

    private fun addFileUploadKey(container: LinearLayout) {
        val iv = ImageView(context).apply {
            setImageResource(R.drawable.ic_upload)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = makeKeyBackground(false)
            isClickable = true
            isFocusable = false
            contentDescription = "Upload file"
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onFileUpload?.invoke()
            }
        }
        val size = toolbarHeight - 2 * keyMargin
        val lp = LinearLayout.LayoutParams(size, size).apply {
            setMargins(keyMargin, 0, keyMargin, 0)
        }
        container.addView(iv, lp)
    }

    private fun addSeparator(container: LinearLayout) {
        val sep = android.view.View(context).apply {
            setBackgroundColor(0xFF555555.toInt())
        }
        val lp = LinearLayout.LayoutParams(
            (1 * resources.displayMetrics.density).toInt(),
            toolbarHeight - 4 * keyMargin,
        ).apply {
            setMargins(keyMargin * 2, 0, keyMargin * 2, 0)
        }
        container.addView(sep, lp)
    }

    private fun clearModifiers() {
        if (ctrlActive) {
            ctrlActive = false
            ctrlButton.tag = false
            ctrlButton.background = makeKeyBackground(false)
            onCtrlToggle?.invoke(false)
        }
        if (altActive) {
            altActive = false
            altButton.tag = false
            altButton.background = makeKeyBackground(false)
            onAltToggle?.invoke(false)
        }
    }
}
