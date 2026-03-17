package com.minicode.ui.workspace

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupMenu
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
    var onDelete: ((profileId: String, label: String) -> Unit)? = null

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

        // Gear icon — tap for Edit/Delete popup menu
        val gear = TextView(context).apply {
            text = "\u2699"  // ⚙
            textSize = 20f
            setTextColor(0xFF888888.toInt())
            gravity = Gravity.CENTER
            val touchPad = (8 * density).toInt()
            setPadding(touchPad, touchPad, touchPad, touchPad)
            setOnClickListener { anchor ->
                val popup = PopupMenu(context, anchor)
                popup.menu.apply {
                    add(0, 1, 0, "Edit")
                    add(0, 2, 1, "Delete")
                }
                popup.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> onEdit?.invoke(profile.id)
                        2 -> onDelete?.invoke(profile.id,
                            profile.label.ifBlank { "${profile.username}@${profile.host}" })
                    }
                    true
                }
                popup.show()
            }
        }
        card.addView(gear, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

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
