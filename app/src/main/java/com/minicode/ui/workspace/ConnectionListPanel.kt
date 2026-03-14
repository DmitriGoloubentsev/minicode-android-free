package com.minicode.ui.workspace

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.minicode.model.ConnectionProfile

class ConnectionListPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ScrollView(context, attrs, defStyleAttr) {

    var onConnect: ((profileId: String) -> Unit)? = null
    var onAddNew: (() -> Unit)? = null
    var onEdit: ((profileId: String) -> Unit)? = null

    private val density = context.resources.displayMetrics.density
    private val listContainer: LinearLayout

    init {
        setBackgroundColor(0xFF1A1A1A.toInt())

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(listContainer, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        addView(root, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
    }

    fun updateProfiles(profiles: List<ConnectionProfile>) {
        listContainer.removeAllViews()

        // Title
        val title = TextView(context).apply {
            text = "Connections"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
        }
        listContainer.addView(title, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = (16 * density).toInt()
        })

        if (profiles.isEmpty()) {
            val empty = TextView(context).apply {
                text = "No saved connections.\nTap + to add one."
                textSize = 14f
                setTextColor(0xFF888888.toInt())
                gravity = Gravity.CENTER
                val pad = (32 * density).toInt()
                setPadding(0, pad, 0, pad)
            }
            listContainer.addView(empty)
        } else {
            for (profile in profiles) {
                listContainer.addView(createProfileCard(profile))
            }
        }

        // Add New Connection button
        val addBtn = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val hPad = (16 * density).toInt()
            val vPad = (14 * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            background = GradientDrawable().apply {
                setStroke((1 * density).toInt(), 0xFF4CAF50.toInt())
                cornerRadius = 8 * density
                setColor(0x00000000)
            }
            setOnClickListener { onAddNew?.invoke() }
        }

        val plusIcon = TextView(context).apply {
            text = "+"
            textSize = 16f
            setTextColor(0xFF4CAF50.toInt())
        }
        addBtn.addView(plusIcon, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            marginEnd = (8 * density).toInt()
        })

        val addLabel = TextView(context).apply {
            text = "New Connection"
            textSize = 14f
            setTextColor(0xFF4CAF50.toInt())
        }
        addBtn.addView(addLabel)

        listContainer.addView(addBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = (12 * density).toInt()
        })
    }

    private fun createProfileCard(profile: ConnectionProfile): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hPad = (16 * density).toInt()
            val vPad = (14 * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            background = GradientDrawable().apply {
                setColor(0xFF2D2D2D.toInt())
                cornerRadius = 8 * density
            }
            setOnClickListener { onConnect?.invoke(profile.id) }
            setOnLongClickListener {
                onEdit?.invoke(profile.id)
                true
            }
        }

        // Info section
        val info = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val label = TextView(context).apply {
            text = profile.label.ifBlank { "${profile.username}@${profile.host}" }
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, Typeface.BOLD)
        }
        info.addView(label)

        val detail = TextView(context).apply {
            text = "${profile.username}@${profile.host}:${profile.port}"
            textSize = 12f
            setTextColor(0xFF888888.toInt())
        }
        info.addView(detail, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = (2 * density).toInt()
        })

        card.addView(info, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))

        // Connect arrow
        val arrow = TextView(context).apply {
            text = "▶"
            textSize = 14f
            setTextColor(0xFF4CAF50.toInt())
            gravity = Gravity.CENTER
        }
        card.addView(arrow)

        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        wrapper.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = (8 * density).toInt()
        })

        return wrapper
    }
}
