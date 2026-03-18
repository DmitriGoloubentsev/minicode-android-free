package com.minicode.ui.connection

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.minicode.R
import com.minicode.databinding.FragmentConnectionListBinding
import com.minicode.model.ConnectionProfile
import com.minicode.ui.connection.adapter.ConnectionAdapter
import com.minicode.ui.settings.SettingsActivity
import com.minicode.ui.workspace.WorkspaceActivity
import com.minicode.viewmodel.ConnectionListViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ConnectionListFragment : Fragment() {

    private var _binding: FragmentConnectionListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ConnectionListViewModel by activityViewModels()
    private lateinit var adapter: ConnectionAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentConnectionListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Show app name + version in toolbar
        val pInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
        binding.toolbar.title = "${getString(R.string.app_name)} v${pInfo.versionName} (${pInfo.longVersionCode})"

        adapter = ConnectionAdapter(
            onItemClick = { profile ->
                val intent = Intent(requireContext(), WorkspaceActivity::class.java).apply {
                    putExtra("profile_id", profile.id)
                }
                startActivity(intent)
            },
            onItemLongClick = { profile, anchor ->
                showConnectionMenu(profile, anchor)
            },
        )

        binding.recyclerConnections.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerConnections.adapter = adapter

        // Swipe to delete
        val swipeHandler = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {
                val profile = adapter.currentList[vh.adapterPosition]
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Delete Connection")
                    .setMessage("Delete \"${profile.label}\"?")
                    .setPositiveButton("Delete") { _, _ -> viewModel.deleteProfile(profile.id) }
                    .setNegativeButton("Cancel") { _, _ -> adapter.notifyItemChanged(vh.adapterPosition) }
                    .setOnCancelListener { adapter.notifyItemChanged(vh.adapterPosition) }
                    .show()
            }
        }
        ItemTouchHelper(swipeHandler).attachToRecyclerView(binding.recyclerConnections)

        binding.toolbar.inflateMenu(R.menu.menu_connection_list)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> {
                    startActivity(Intent(requireContext(), SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }

        binding.fabAdd.setOnClickListener {
            findNavController().navigate(R.id.action_list_to_form)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.profiles.collect { profiles ->
                    adapter.submitList(profiles)
                    binding.textEmpty.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
                    binding.recyclerConnections.visibility = if (profiles.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }
    }

    private fun showConnectionMenu(profile: ConnectionProfile, anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.apply {
            add(0, 1, 0, "Edit")
            add(0, 2, 1, "Delete")
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    val bundle = Bundle().apply { putString("profile_id", profile.id) }
                    findNavController().navigate(R.id.action_list_to_form, bundle)
                }
                2 -> {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Delete Connection")
                        .setMessage("Delete \"${profile.label}\"?")
                        .setPositiveButton("Delete") { _, _ -> viewModel.deleteProfile(profile.id) }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            true
        }
        popup.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
