package com.tool.btremote.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tool.btremote.databinding.ItemSmsBinding
import com.tool.btremote.sms.SmsMessageItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmsListAdapter(
    private val onItemClick: ((SmsMessageItem) -> Unit)? = null
) : ListAdapter<SmsMessageItem, SmsListAdapter.SmsViewHolder>(DiffCallback()) {

    class SmsViewHolder(val binding: ItemSmsBinding) : RecyclerView.ViewHolder(binding.root)

    class DiffCallback : DiffUtil.ItemCallback<SmsMessageItem>() {
        override fun areItemsTheSame(oldItem: SmsMessageItem, newItem: SmsMessageItem) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: SmsMessageItem, newItem: SmsMessageItem) =
            oldItem == newItem
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SmsViewHolder {
        val binding = ItemSmsBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return SmsViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SmsViewHolder, position: Int) {
        val item = getItem(position)
        holder.binding.tvSender.text = if (item.isOutgoing) "我 → ${item.sender}" else item.sender
        holder.binding.tvBody.text = item.body
        holder.binding.tvTime.text = formatTime(item.time)

        holder.itemView.setOnClickListener {
            onItemClick?.invoke(item)
        }

        // 视觉上区分发送/接收
        holder.itemView.alpha = if (item.isOutgoing) 0.7f else 1f
    }

    private fun formatTime(time: Long): String {
        val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        return sdf.format(Date(time))
    }
}
