package com.hop.mesh.ui

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hop.mesh.R
import com.hop.mesh.databinding.ItemRoutingBinding
import com.hop.mesh.routing.RoutingEntry

class RoutingAdapter(
    private val onChatClick: (String) -> Unit
) : ListAdapter<RoutingEntry, RoutingAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRoutingBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding, onChatClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemRoutingBinding,
        private val onChatClick: (String) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(entry: RoutingEntry) {
            binding.routingNodeId.text = if (entry.deviceName.isNotBlank() && entry.deviceName != "Unknown") {
                entry.deviceName
            } else {
                "Node: ${entry.nodeId.takeLast(6)}"
            }
            binding.routingNextHop.text = "via ${entry.nextHop.takeLast(8)}"
            binding.routingHops.text = "${entry.hopCount} hop${if (entry.hopCount != 1) "s" else ""}"
            binding.routingCost.text = "cost ${entry.cost}"

            val ctx = binding.root.context
            val isStale = (System.currentTimeMillis() - entry.lastSeen) > 3 * 60 * 1000L
            val isDirectHop = entry.hopCount <= 1

            // Status dot
            val dotColor = when {
                isStale -> ContextCompat.getColor(ctx, R.color.status_offline)
                isDirectHop -> ContextCompat.getColor(ctx, R.color.status_connected)
                else -> ContextCompat.getColor(ctx, R.color.status_reachable)
            }
            val dot = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(dotColor)
            }
            binding.routingStatusDot.background = dot

            // Hop count badge
            val badgeBg = GradientDrawable().apply {
                cornerRadius = 16f
                setColor(when {
                    isStale -> ContextCompat.getColor(ctx, R.color.status_offline)
                    isDirectHop -> ContextCompat.getColor(ctx, R.color.status_connected)
                    else -> ContextCompat.getColor(ctx, R.color.status_reachable)
                })
            }
            binding.routingHops.background = badgeBg
            binding.routingHops.setTextColor(ContextCompat.getColor(ctx, R.color.on_primary))

            binding.btnChat.setOnClickListener {
                onChatClick(entry.nodeId)
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<RoutingEntry>() {
        override fun areItemsTheSame(old: RoutingEntry, new: RoutingEntry) = old.nodeId == new.nodeId
        override fun areContentsTheSame(old: RoutingEntry, new: RoutingEntry) = old == new
    }
}
