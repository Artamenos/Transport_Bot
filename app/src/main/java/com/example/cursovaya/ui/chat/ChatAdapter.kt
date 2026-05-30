package com.example.cursovaya.ui.chat

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.cursovaya.databinding.ItemChatMessageBinding

class ChatAdapter : ListAdapter<ChatMessageUi, ChatAdapter.ChatViewHolder>(DiffCallback()) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val binding = ItemChatMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ChatViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ChatViewHolder(private val binding: ItemChatMessageBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ChatMessageUi) {
            binding.textMessage.text = item.text

            val rowParams = binding.messageRow.layoutParams as FrameLayout.LayoutParams
            rowParams.gravity = if (item.isUser) Gravity.END else Gravity.START
            binding.messageRow.layoutParams = rowParams

            binding.imageAvatar.visibility = if (item.isUser) View.GONE else View.VISIBLE
            binding.textMessage.isSelected = item.isUser

            if (item.time.isNullOrBlank()) {
                binding.textTime.visibility = View.GONE
            } else {
                binding.textTime.text = item.time
                binding.textTime.visibility = View.VISIBLE
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<ChatMessageUi>() {
        override fun areItemsTheSame(oldItem: ChatMessageUi, newItem: ChatMessageUi): Boolean = oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: ChatMessageUi, newItem: ChatMessageUi): Boolean = oldItem == newItem
    }
}

data class ChatMessageUi(
    val id: Long,
    val text: String,
    val isUser: Boolean,
    val time: String? = null,
    val showMainTopics: Boolean = false,
    val showRouteActions: Boolean = false,
)
