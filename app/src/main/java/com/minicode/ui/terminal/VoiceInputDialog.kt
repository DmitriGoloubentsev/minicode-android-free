package com.minicode.ui.terminal

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.button.MaterialButton

class VoiceInputDialog(
    context: Context,
    private val offline: Boolean,
    private val anchorBottomY: Int,
    private val anchorLeftX: Int = 0,
    private val onConfirm: (String) -> Unit,
    private val onCancel: () -> Unit,
    private val onRecordStart: () -> Unit,
    private val onRecordStop: () -> Unit,
) : Dialog(context) {

    private lateinit var editText: EditText
    private lateinit var statusText: TextView
    private lateinit var timerText: TextView
    private lateinit var micButton: View
    private lateinit var processingOverlay: FrameLayout
    private var isRecording = true
    private var recordingStartTime = 0L

    // For insert-at-cursor on re-record
    private var insertStart = -1
    private var insertEnd = -1
    private var preInsertText: String? = null

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!isRecording) return
            val elapsed = SystemClock.elapsedRealtime() - recordingStartTime
            val secs = (elapsed / 1000).toInt()
            timerText.text = "%d:%02d".format(secs / 60, secs % 60)
            timerText.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setCancelable(false)

        val density = context.resources.displayMetrics.density
        recordingStartTime = SystemClock.elapsedRealtime()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (16 * density).toInt(), (20 * density).toInt(), (16 * density).toInt())
            background = GradientDrawable().apply {
                setColor(0xFF1E1E1E.toInt())
                cornerRadius = 16 * density
                setStroke((1 * density).toInt(), 0xFF333333.toInt())
            }
        }

        // Header row: status + timer
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (8 * density).toInt())
        }

        statusText = TextView(context).apply {
            text = if (offline) "\u25CF Recording (offline)" else "\u25CF Recording (online)"
            setTextColor(0xFFFF3B30.toInt())
            textSize = 14f
        }
        headerRow.addView(statusText, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
        ))

        timerText = TextView(context).apply {
            text = "0:00"
            setTextColor(0xFFAAAAAA.toInt())
            textSize = 14f
        }
        headerRow.addView(timerText)
        root.addView(headerRow)

        // EditText + processing overlay in a FrameLayout
        val editFrame = FrameLayout(context)

        editText = EditText(context).apply {
            setTextColor(Color.WHITE)
            setHintTextColor(0x80FFFFFF.toInt())
            hint = "Speak now..."
            textSize = 16f
            minLines = 5
            maxLines = 12
            background = GradientDrawable().apply {
                setColor(0xFF2D2D2D.toInt())
                cornerRadius = 8 * density
            }
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
        }
        editFrame.addView(editText, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        // Processing overlay — shown during finalizing
        processingOverlay = FrameLayout(context).apply {
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(0xDD1E1E1E.toInt())
                cornerRadius = 8 * density
            }
        }
        val spinnerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val spinner = ProgressBar(context).apply {
            isIndeterminate = true
        }
        spinnerLayout.addView(spinner, LinearLayout.LayoutParams(
            (48 * density).toInt(), (48 * density).toInt(),
        ).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val processingLabel = TextView(context).apply {
            text = "Processing speech..."
            setTextColor(0xFFFFAA00.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, (10 * density).toInt(), 0, 0)
        }
        spinnerLayout.addView(processingLabel)
        processingOverlay.addView(spinnerLayout, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))
        editFrame.addView(processingOverlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        root.addView(editFrame, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = (12 * density).toInt()
        })

        // Button row: Cancel | [mic] | OK
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val cancelButton = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Cancel"
            setTextColor(0xFFAAAAAA.toInt())
            setOnClickListener {
                onCancel()
                dismiss()
            }
        }
        buttonRow.addView(cancelButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        // Spacer
        buttonRow.addView(View(context), LinearLayout.LayoutParams(
            0, 0, 1f,
        ))

        // Mic hold-to-record button
        val micSize = (48 * density).toInt()
        micButton = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFCC4444.toInt())
            }
            visibility = View.GONE // Hidden during first recording, shown after
            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        if (!isRecording) {
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            beginReRecord()
                            onRecordStart()
                            (v.background as? GradientDrawable)?.setColor(0xFFFF3B30.toInt())
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (isRecording) {
                            onRecordStop()
                            (v.background as? GradientDrawable)?.setColor(0xFFCC4444.toInt())
                        }
                        true
                    }
                    else -> true
                }
            }
        }
        buttonRow.addView(micButton, LinearLayout.LayoutParams(micSize, micSize).apply {
            marginEnd = (12 * density).toInt()
        })

        val okButton = MaterialButton(context).apply {
            text = "OK"
            setOnClickListener {
                val text = editText.text.toString().trim()
                if (text.isNotEmpty()) {
                    onConfirm(text)
                }
                dismiss()
            }
        }
        buttonRow.addView(okButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        root.addView(buttonRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        setContentView(root)

        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels
        window?.apply {
            if (anchorLeftX > 0) {
                // Split mode: fill from anchorLeftX to right edge with small margins
                val availableWidth = screenWidth - anchorLeftX
                val dialogWidth = (availableWidth - (16 * density).toInt())
                setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
                setGravity(Gravity.BOTTOM or Gravity.RIGHT)
                val marginFromBottom = screenHeight - anchorBottomY
                val attrs = attributes
                attrs.y = marginFromBottom + (8 * density).toInt()
                attrs.x = (8 * density).toInt()
                attributes = attrs
            } else {
                // Phone/portrait mode: centered, 92% width
                setLayout((screenWidth * 0.92).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
                setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                val marginFromBottom = screenHeight - anchorBottomY
                val attrs = attributes
                attrs.y = marginFromBottom + (8 * density).toInt()
                attributes = attrs
            }
            setBackgroundDrawableResource(android.R.color.transparent)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        // Start timer
        timerText.post(timerRunnable)
    }

    private fun beginReRecord() {
        isRecording = true
        recordingStartTime = SystemClock.elapsedRealtime()

        // Capture cursor/selection position before re-recording
        preInsertText = editText.text.toString()
        insertStart = editText.selectionStart
        insertEnd = editText.selectionEnd

        statusText.text = if (offline) "\u25CF Recording (offline)" else "\u25CF Recording (online)"
        statusText.setTextColor(0xFFFF3B30.toInt())
        timerText.text = "0:00"
        timerText.post(timerRunnable)
    }

    fun updatePartialText(text: String) {
        if (!isRecording) return
        if (insertStart >= 0) {
            val base = preInsertText ?: ""
            val before = base.substring(0, insertStart.coerceAtMost(base.length))
            val after = base.substring(insertEnd.coerceAtMost(base.length))

            val needSpaceBefore = before.isNotEmpty() && !before.endsWith(' ') && !text.startsWith(' ')
            val needSpaceAfter = after.isNotEmpty() && !after.startsWith(' ') && !text.endsWith(' ')
            val spaceBefore = if (needSpaceBefore) " " else ""
            val spaceAfter = if (needSpaceAfter) " " else ""

            val combined = before + spaceBefore + text + spaceAfter + after
            editText.setText(combined)
            val cursorPos = (before + spaceBefore + text).length
            editText.setSelection(cursorPos.coerceAtMost(combined.length))
        } else {
            editText.setText(text)
            editText.setSelection(text.length)
        }
    }

    fun onRecordingStopped() {
        isRecording = false
        timerText.removeCallbacks(timerRunnable)
        statusText.text = "\u25CF Processing..."
        statusText.setTextColor(0xFFFFAA00.toInt())
        processingOverlay.visibility = View.VISIBLE
    }

    fun onFinalResult(text: String?) {
        if (text != null) {
            if (insertStart >= 0) {
                val base = preInsertText ?: ""
                val before = base.substring(0, insertStart.coerceAtMost(base.length))
                val after = base.substring(insertEnd.coerceAtMost(base.length))

                val needSpaceBefore = before.isNotEmpty() && !before.endsWith(' ') && !text.startsWith(' ')
                val needSpaceAfter = after.isNotEmpty() && !after.startsWith(' ') && !text.endsWith(' ')
                val spaceBefore = if (needSpaceBefore) " " else ""
                val spaceAfter = if (needSpaceAfter) " " else ""

                val combined = before + spaceBefore + text + spaceAfter + after
                editText.setText(combined)
                val cursorPos = (before + spaceBefore + text).length
                editText.setSelection(cursorPos.coerceAtMost(combined.length))
            } else {
                editText.setText(text)
                editText.setSelection(text.length)
            }
        }
        insertStart = -1
        insertEnd = -1
        preInsertText = null

        processingOverlay.visibility = View.GONE
        statusText.text = "\u2713 Edit and confirm"
        statusText.setTextColor(0xFF4CAF50.toInt())
        micButton.visibility = View.VISIBLE
        editText.requestFocus()
    }

    fun isCurrentlyRecording(): Boolean = isRecording

    override fun dismiss() {
        timerText.removeCallbacks(timerRunnable)
        super.dismiss()
    }
}
