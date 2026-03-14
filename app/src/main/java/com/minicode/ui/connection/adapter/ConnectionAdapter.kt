package com.minicode.ui.connection.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.minicode.databinding.ItemConnectionCardBinding
import com.minicode.model.ConnectionProfile

class ConnectionAdapter(
    private val onItemClick: (ConnectionProfile) -> Unit,
    private val onItemLongClick: (ConnectionProfile, View) -> Unit,
) : ListAdapter<ConnectionProfile, ConnectionAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemConnectionCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemConnectionCardBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(profile: ConnectionProfile) {
            binding.textLabel.text = profile.label
            binding.textHost.text = "${profile.username}@${profile.host}:${profile.port}"
            binding.textAuthType.text = when (profile.authType) {
                com.minicode.model.AuthType.PASSWORD -> "Password"
                com.minicode.model.AuthType.PRIVATE_KEY -> "Key"
            }
            binding.root.setOnClickListener { onItemClick(profile) }
            binding.root.setOnLongClickListener { v ->
                onItemLongClick(profile, v)
                true
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ConnectionProfile>() {
            override fun areItemsTheSame(a: ConnectionProfile, b: ConnectionProfile) = a.id == b.id
            override fun areContentsTheSame(a: ConnectionProfile, b: ConnectionProfile) = a == b
        }
    }
}
