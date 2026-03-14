package com.minicode.ui.workspace

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.minicode.model.ConnectionProfile
import com.minicode.viewmodel.ConnectionListViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ConnectionPickerSheet : BottomSheetDialogFragment() {

    private val viewModel: ConnectionListViewModel by activityViewModels()
    var onProfileSelected: ((profileId: String) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val density = resources.displayMetrics.density

        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (16 * density).toInt(), (16 * density).toInt(),
                (16 * density).toInt(), (16 * density).toInt()
            )
            setBackgroundColor(0xFF1E1E1E.toInt())
        }

        val title = TextView(requireContext()).apply {
            text = "New Session"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, (12 * density).toInt())
        }
        root.addView(title)

        val listContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            id = View.generateViewId()
        }
        root.addView(listContainer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.profiles.collect { profiles ->
                    listContainer.removeAllViews()
                    if (profiles.isEmpty()) {
                        val empty = TextView(requireContext()).apply {
                            text = "No saved connections"
                            textSize = 14f
                            setTextColor(0xFF888888.toInt())
                            gravity = Gravity.CENTER
                            setPadding(0, (24 * density).toInt(), 0, (24 * density).toInt())
                        }
                        listContainer.addView(empty)
                    } else {
                        for (profile in profiles) {
                            listContainer.addView(createProfileItem(profile, density))
                        }
                    }
                }
            }
        }

        return root
    }

    private fun createProfileItem(profile: ConnectionProfile, density: Float): View {
        val item = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val hPad = (12 * density).toInt()
            val vPad = (10 * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            background = GradientDrawable().apply {
                setColor(0xFF2D2D2D.toInt())
                cornerRadius = 8 * density
            }
            setOnClickListener {
                onProfileSelected?.invoke(profile.id)
                dismiss()
            }
        }

        val label = TextView(requireContext()).apply {
            text = profile.label.ifBlank { "${profile.username}@${profile.host}" }
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
        }
        item.addView(label)

        val detail = TextView(requireContext()).apply {
            text = "${profile.username}@${profile.host}:${profile.port}"
            textSize = 12f
            setTextColor(0xFF888888.toInt())
        }
        item.addView(detail)

        val wrapper = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        wrapper.addView(item, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = (6 * density).toInt()
        })

        return wrapper
    }
}
