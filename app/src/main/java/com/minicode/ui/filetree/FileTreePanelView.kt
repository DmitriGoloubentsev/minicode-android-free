package com.minicode.ui.filetree

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.minicode.R
import com.minicode.databinding.ViewFileTreePanelBinding
import com.minicode.model.FileNode
import com.minicode.viewmodel.FileTreeViewModel

class FileTreePanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val binding: ViewFileTreePanelBinding
    private val adapter: FileNodeAdapter
    var viewModel: FileTreeViewModel? = null
        set(value) {
            field = value
            setupButtons()
        }
    var onFileOpen: ((String) -> Unit)? = null

    init {
        binding = ViewFileTreePanelBinding.inflate(LayoutInflater.from(context), this, true)
        orientation = VERTICAL

        adapter = FileNodeAdapter(
            onClick = { node ->
                if (node.isDirectory) {
                    viewModel?.toggleExpand(node)
                } else {
                    onFileOpen?.invoke(node.path)
                }
            },
            onLongClick = { node, anchor ->
                showContextMenu(node, anchor)
            },
        )

        binding.recyclerFileTree.layoutManager = LinearLayoutManager(context)
        binding.recyclerFileTree.adapter = adapter
        binding.recyclerFileTree.itemAnimator = null
    }

    private fun setupButtons() {
        binding.btnNavigateUp.setOnClickListener { viewModel?.navigateUp() }
        binding.btnRefresh.setOnClickListener { viewModel?.refresh() }
        binding.btnNewFile.setOnClickListener { showNewFileDialog() }
        binding.btnNewFolder.setOnClickListener { showNewFolderDialog() }
    }

    fun updateNodes(nodes: List<FileNode>) {
        adapter.submitList(nodes)
    }

    fun updatePath(path: String) {
        binding.textCurrentPath.text = path
    }

    fun highlightAndScrollTo(filePath: String?) {
        adapter.highlightedPath = filePath
        if (filePath == null) return
        val position = adapter.currentList.indexOfFirst { it.path == filePath }
        if (position >= 0) {
            (binding.recyclerFileTree.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(position, binding.recyclerFileTree.height / 3)
        }
    }

    fun updateLoading(loading: Boolean) {
        binding.progressLoading.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun showContextMenu(node: FileNode, anchor: View) {
        val popup = PopupMenu(context, anchor)
        popup.menu.apply {
            if (node.isDirectory) {
                add(0, 1, 0, "New File Here")
                add(0, 2, 1, "New Folder Here")
            }
            add(0, 3, 2, "Rename")
            add(0, 4, 3, "Delete")
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showNewFileDialog(node.path)
                2 -> showNewFolderDialog(node.path)
                3 -> showRenameDialog(node)
                4 -> showDeleteConfirmation(node)
            }
            true
        }
        popup.show()
    }

    private fun showNewFileDialog(parentPath: String? = null) {
        val path = parentPath ?: viewModel?.currentPath?.value ?: return
        val input = EditText(context).apply {
            hint = "filename.ext"
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(48, 32, 48, 16)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle("New File")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    viewModel?.createFile(path, name)
                    // Open the new file in the editor
                    onFileOpen?.invoke("$path/$name")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showNewFolderDialog(parentPath: String? = null) {
        val path = parentPath ?: viewModel?.currentPath?.value ?: return
        val input = EditText(context).apply {
            hint = "folder name"
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(48, 32, 48, 16)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle("New Folder")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    viewModel?.createFolder(path, name)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRenameDialog(node: FileNode) {
        val input = EditText(context).apply {
            setText(node.name)
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(48, 32, 48, 16)
            selectAll()
        }
        MaterialAlertDialogBuilder(context)
            .setTitle("Rename")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != node.name) {
                    viewModel?.renameNode(node, newName)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteConfirmation(node: FileNode) {
        val type = if (node.isDirectory) "folder" else "file"
        MaterialAlertDialogBuilder(context)
            .setTitle("Delete $type")
            .setMessage("Delete \"${node.name}\"?${if (node.isDirectory) "\n\nThis will delete all contents." else ""}")
            .setPositiveButton("Delete") { _, _ ->
                viewModel?.deleteNode(node)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
