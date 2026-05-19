package com.hop.mesh.ui

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hop.mesh.R
import com.hop.mesh.databinding.ItemInboxBinding
import com.hop.mesh.messaging.MessagingLayer
import java.text.SimpleDateFormat
import java.util.*

class InboxAdapter : ListAdapter<MessagingLayer.InboxMessage, InboxAdapter.ViewHolder>(DiffCallback) {

    private var localNodeId: String = ""
    private var nodeNames: Map<String, String> = emptyMap()

    fun updateIdentity(nodeId: String, names: Map<String, String>) {
        localNodeId = nodeId
        nodeNames = names
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemInboxBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), localNodeId, nodeNames)
    }

    class ViewHolder(
        private val binding: ItemInboxBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        fun bind(msg: MessagingLayer.InboxMessage, localNodeId: String, nodeNames: Map<String, String>) {
            val isMine = msg.sourceId == localNodeId
            val isBroadcast = msg.destinationId == "BROADCAST"

            val displayName = if (isMine) "Me" else (nodeNames[msg.sourceId] ?: msg.sourceId.take(8))
            binding.inboxFrom.text = displayName
            binding.inboxBody.text = msg.body
            binding.inboxTime.text = timeFormat.format(Date(msg.receivedAt))

            // Layout gravity (Left for received, Right for mine)
            val layoutParams = binding.bubbleContainer.layoutParams as FrameLayout.LayoutParams
            layoutParams.gravity = if (isMine) Gravity.END else Gravity.START
            binding.bubbleContainer.layoutParams = layoutParams

            // Style the bubble
            val ctx = binding.root.context

            val bgColor = when {
                isBroadcast -> ContextCompat.getColor(ctx, R.color.bubble_broadcast)
                isMine -> ContextCompat.getColor(ctx, R.color.primary)
                else -> ContextCompat.getColor(ctx, R.color.bubble_received)
            }
            val textColor = when {
                isBroadcast -> ContextCompat.getColor(ctx, R.color.bubble_broadcast_text)
                isMine -> ContextCompat.getColor(ctx, R.color.on_primary)
                else -> ContextCompat.getColor(ctx, R.color.bubble_received_text)
            }

            val bubble = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = 24f
            }
            binding.bubbleContainer.background = bubble
            binding.inboxBody.setTextColor(textColor)
            binding.inboxTime.setTextColor(if (isMine) ContextCompat.getColor(ctx, R.color.on_primary) else ContextCompat.getColor(ctx, R.color.on_surface_variant))

            if (isBroadcast) {
                binding.inboxFrom.text = "📡 Broadcast"
                binding.inboxFrom.setTextColor(ContextCompat.getColor(ctx, R.color.on_tertiary))
                binding.inboxFrom.visibility = android.view.View.VISIBLE
            } else if (isMine) {
                binding.inboxFrom.visibility = android.view.View.GONE
            } else {
                binding.inboxFrom.setTextColor(ContextCompat.getColor(ctx, R.color.primary))
                binding.inboxFrom.visibility = android.view.View.VISIBLE
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<MessagingLayer.InboxMessage>() {
        override fun areItemsTheSame(a: MessagingLayer.InboxMessage, b: MessagingLayer.InboxMessage) = a.messageId == b.messageId
        override fun areContentsTheSame(a: MessagingLayer.InboxMessage, b: MessagingLayer.InboxMessage) = a == b
    }
}
