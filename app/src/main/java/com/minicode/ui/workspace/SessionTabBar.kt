package com.minicode.ui.workspace

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.minicode.model.SessionHandle
import com.minicode.model.SshSessionState

/**
 * Horizontal tab bar showing active SSH sessions with a permanent Connections tab
 * and a fixed settings gear icon on the right.
 */
class SessionTabBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    companion object {
        const val CONNECTIONS_TAB_ID = "__connections__"
    }

    var onTabSelected: ((sessionId: String) -> Unit)? = null
    var onTabClosed: ((sessionId: String) -> Unit)? = null
    var onConnectionsTabSelected: (() -> Unit)? = null
    var onSettingsClicked: (() -> Unit)? = null

    private val tabContainer = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private val scrollView = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(tabContainer, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    private val density = context.resources.displayMetrics.density
    private var activeSessionId: String? = null
    private var connectionsTabActive = false

    // Track dot views and their animators for efficient activity updates
    private val dotViews = mutableMapOf<String, View>()
    private val dotAnimators = mutableMapOf<String, ObjectAnimator>()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(0xFF141414.toInt())

        // Scrollable tabs take remaining space
        addView(scrollView, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        // Fixed gear icon on the right
        val gear = TextView(context).apply {
            text = "\u2699"  // ⚙
            textSize = 16f
            setTextColor(0x99FFFFFF.toInt())
            gravity = Gravity.CENTER
            val hPad = (10 * density).toInt()
            setPadding(hPad, 0, hPad, 0)
            setOnClickListener { onSettingsClicked?.invoke() }
        }
        addView(gear, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    fun updateSessions(sessions: List<SessionHandle>, activeId: String?) {
        activeSessionId = activeId
        visibility = View.VISIBLE

        // Cancel all running animators before rebuilding
        dotAnimators.values.forEach { it.cancel() }
        dotAnimators.clear()
        dotViews.clear()

        tabContainer.removeAllViews()

        // Connections tab (always first)
        tabContainer.addView(createConnectionsTab())

        // Session tabs
        for (session in sessions) {
            val tabIsActive = !connectionsTabActive && session.id == activeId
            val tab = createTab(session, tabIsActive)
            tabContainer.addView(tab)
        }

        // Start pulse animations for sessions with active output
        for (session in sessions) {
            updateActivityIndicator(session.id, session.hasActiveOutput.value)
        }
    }

    /** Callback invoked when output activity stops (yellow→green transition). */
    var onActivityStopped: ((sessionId: String) -> Unit)? = null

    /** Update only the activity pulse for a specific session without rebuilding tabs. */
    fun updateActivity(sessionId: String, hasActiveOutput: Boolean) {
        updateActivityIndicator(sessionId, hasActiveOutput)
    }

    fun setConnectionsTabActive(active: Boolean) {
        connectionsTabActive = active
    }

    private fun updateActivityIndicator(sessionId: String, active: Boolean) {
        val dot = dotViews[sessionId] ?: return
        val dotBg = dot.background as? GradientDrawable
        val existing = dotAnimators[sessionId]
        if (active && existing == null) {
            // Switch to yellow pulsing
            dotBg?.setColor(0xFFFFAA00.toInt())
            val pulse = ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.3f).apply {
                duration = 600
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = LinearInterpolator()
            }
            pulse.start()
            dotAnimators[sessionId] = pulse
        } else if (!active && existing != null) {
            // Switch back to green solid
            existing.cancel()
            dot.alpha = 1f
            dotBg?.setColor(0xFF4CAF50.toInt())
            dotAnimators.remove(sessionId)
            // Notify that activity stopped (for bell notification)
            onActivityStopped?.invoke(sessionId)
        }
    }

    private fun createConnectionsTab(): View {
        val isActive = connectionsTabActive

        val tabLayout = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hPad = (12 * density).toInt()
            val vPad = (4 * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)

            background = GradientDrawable().apply {
                setColor(if (isActive) 0xFF2D2D2D.toInt() else 0x00000000)
            }

            setOnClickListener {
                onConnectionsTabSelected?.invoke()
            }
        }

        val icon = TextView(context).apply {
            text = "+"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (isActive) 0xFF4CAF50.toInt() else 0x99FFFFFF.toInt())
            gravity = Gravity.CENTER
        }
        tabLayout.addView(icon, LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
        ))

        if (isActive) {
            val wrapper = LinearLayout(context).apply {
                orientation = VERTICAL
            }
            wrapper.addView(tabLayout, LayoutParams(
                LayoutParams.WRAP_CONTENT, 0, 1f
            ))
            val accent = View(context).apply {
                setBackgroundColor(0xFF4CAF50.toInt())
            }
            wrapper.addView(accent, LayoutParams(
                LayoutParams.MATCH_PARENT, (2 * density).toInt()
            ))
            return wrapper
        }

        return tabLayout
    }

    private fun createTab(session: SessionHandle, tabIsActive: Boolean): View {
        val tabLayout = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hPad = (12 * density).toInt()
            val vPad = (4 * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)

            background = if (tabIsActive) {
                GradientDrawable().apply {
                    setColor(0xFF2D2D2D.toInt())
                }
            } else {
                GradientDrawable().apply {
                    setColor(0x00000000)
                }
            }

            setOnClickListener {
                onTabSelected?.invoke(session.id)
            }

            setOnLongClickListener {
                onTabClosed?.invoke(session.id)
                true
            }
        }

        // Status dot
        val dotSize = (6 * density).toInt()
        val dot = View(context).apply {
            val dotBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                val color = when (session.state.value) {
                    SshSessionState.CONNECTED -> 0xFF4CAF50.toInt()
                    SshSessionState.CONNECTING, SshSessionState.AUTHENTICATING -> 0xFFFFAA00.toInt()
                    SshSessionState.ERROR -> 0xFFFF3B30.toInt()
                    SshSessionState.DISCONNECTED -> 0xFF888888.toInt()
                }
                setColor(color)
            }
            background = dotBg
        }
        tabLayout.addView(dot, LayoutParams(dotSize, dotSize).apply {
            marginEnd = (6 * density).toInt()
        })
        dotViews[session.id] = dot

        // Label
        val label = TextView(context).apply {
            text = session.label.value
            textSize = 12f
            setTypeface(null, if (tabIsActive) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(if (tabIsActive) 0xFFFFFFFF.toInt() else 0x99FFFFFF.toInt())
            maxLines = 1
            maxWidth = (120 * density).toInt()
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        tabLayout.addView(label, LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
        ))

        // Close button (only for active tab)
        if (tabIsActive) {
            val closeSize = (16 * density).toInt()
            val closeBtn = TextView(context).apply {
                text = "\u00D7" // multiplication sign as X
                textSize = 14f
                setTextColor(0x99FFFFFF.toInt())
                gravity = Gravity.CENTER
                setOnClickListener {
                    onTabClosed?.invoke(session.id)
                }
            }
            tabLayout.addView(closeBtn, LayoutParams(closeSize, closeSize).apply {
                marginStart = (8 * density).toInt()
            })
        }

        // Bottom accent line for active tab
        if (tabIsActive) {
            val wrapper = LinearLayout(context).apply {
                orientation = VERTICAL
            }
            wrapper.addView(tabLayout, LayoutParams(
                LayoutParams.WRAP_CONTENT, 0, 1f
            ))
            val accent = View(context).apply {
                setBackgroundColor(0xFF4CAF50.toInt())
            }
            wrapper.addView(accent, LayoutParams(
                LayoutParams.MATCH_PARENT, (2 * density).toInt()
            ))
            return wrapper
        }

        return tabLayout
    }
}
