package com.example.cursovaya.ui.search

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.cursovaya.databinding.ItemHistoryBinding

class HistoryAdapter(
    private val onClick: (String) -> Unit,
) : ListAdapter<String, HistoryAdapter.HistoryViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val binding = ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return HistoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class HistoryViewHolder(private val binding: ItemHistoryBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: String) {
            binding.textHistory.text = item
            binding.root.setOnClickListener { onClick(item) }
        }
    }

    private companion object DiffCallback : DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(oldItem: String, newItem: String) = oldItem.equals(newItem, ignoreCase = true)
        override fun areContentsTheSame(oldItem: String, newItem: String) = oldItem == newItem
    }
}

