package com.minicode.ui.filetree

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.minicode.R
import com.minicode.core.util.FileIcons
import com.minicode.model.FileNode

class FileNodeAdapter(
    private val onClick: (FileNode) -> Unit,
    private val onLongClick: (FileNode, View) -> Unit,
) : ListAdapter<FileNode, FileNodeAdapter.ViewHolder>(DIFF_CALLBACK) {

    var highlightedPath: String? = null
        set(value) {
            val old = field
            field = value
            // Rebind affected items
            if (old != null) notifyItemChanged(currentList.indexOfFirst { it.path == old })
            if (value != null) notifyItemChanged(currentList.indexOfFirst { it.path == value })
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file_node, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: TextView = itemView.findViewById(R.id.file_icon)
        private val name: TextView = itemView.findViewById(R.id.file_name)
        private val loading: ProgressBar = itemView.findViewById(R.id.file_loading)

        fun bind(node: FileNode) {
            val indent = (node.depth * 16 * itemView.resources.displayMetrics.density).toInt()
            itemView.setPadding(indent + (12 * itemView.resources.displayMetrics.density).toInt(),
                itemView.paddingTop, itemView.paddingRight, itemView.paddingBottom)

            name.text = node.name

            if (node.isDirectory) {
                val arrow = if (node.isExpanded) "\u25BE " else "\u25B8 "
                icon.text = arrow
                icon.setTextColor(FileIcons.FOLDER_COLOR)
                name.setTextColor(FileIcons.FOLDER_COLOR)
                name.typeface = Typeface.DEFAULT_BOLD
            } else {
                val info = FileIcons.getFileTypeInfo(node.name)
                icon.text = info.icon
                icon.setTextColor(info.color)
                name.setTextColor(0xFFD4D4D4.toInt())
                name.typeface = Typeface.DEFAULT
            }

            if (node.isSymlink) {
                name.setTextColor(FileIcons.SYMLINK_COLOR)
            }

            loading.visibility = if (node.isLoading) View.VISIBLE else View.GONE

            // Highlight active file
            if (node.path == highlightedPath) {
                itemView.setBackgroundColor(0xFF37373D.toInt())
            } else {
                itemView.setBackgroundColor(0x00000000)
            }

            itemView.setOnClickListener { onClick(node) }
            itemView.setOnLongClickListener { v -> onLongClick(node, v); true }
        }
    }

    companion object {
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<FileNode>() {
            override fun areItemsTheSame(oldItem: FileNode, newItem: FileNode) =
                oldItem.path == newItem.path

            override fun areContentsTheSame(oldItem: FileNode, newItem: FileNode) =
                oldItem == newItem
        }
    }
}
