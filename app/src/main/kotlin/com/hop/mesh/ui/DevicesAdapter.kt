package com.hop.mesh.ui

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hop.mesh.R
import com.hop.mesh.bluetooth.BluetoothController
import com.hop.mesh.databinding.ItemDeviceBinding

class DevicesAdapter(
    private val onConnect: ((BluetoothController.DiscoveredDevice) -> Unit)? = null,
    private val onChat: ((BluetoothController.DiscoveredDevice) -> Unit)? = null,
    private val onDisconnect: ((BluetoothController.DiscoveredDevice) -> Unit)? = null,
    private val onRemove: ((BluetoothController.DiscoveredDevice) -> Unit)? = null
) : ListAdapter<BluetoothController.DiscoveredDevice, DevicesAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding, onConnect, onChat, onDisconnect, onRemove)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemDeviceBinding,
        private val onConnect: ((BluetoothController.DiscoveredDevice) -> Unit)?,
        private val onChat: ((BluetoothController.DiscoveredDevice) -> Unit)?,
        private val onDisconnect: ((BluetoothController.DiscoveredDevice) -> Unit)?,
        private val onRemove: ((BluetoothController.DiscoveredDevice) -> Unit)?
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(device: BluetoothController.DiscoveredDevice) {
            binding.deviceName.text = device.name
            binding.deviceAddress.text = device.address

            // Logic for which buttons to show
            val isConnected = onDisconnect != null
            
            binding.btnConnect.visibility = if (!isConnected && onConnect != null) android.view.View.VISIBLE else android.view.View.GONE
            binding.btnChat.visibility = if (isConnected && onChat != null) android.view.View.VISIBLE else android.view.View.GONE
            binding.btnDisconnect.visibility = if (isConnected && onDisconnect != null) android.view.View.VISIBLE else android.view.View.GONE
            binding.btnRemove.visibility = if (!isConnected && onRemove != null) android.view.View.VISIBLE else android.view.View.GONE

            // Status dot color
            val dotColor = if (isConnected || device.isBonded) {
                binding.root.context.getColor(R.color.status_connected)
            } else {
                binding.root.context.getColor(R.color.primary_light)
            }
            val dot = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(dotColor)
            }
            binding.statusDot.background = dot

            binding.btnConnect.setOnClickListener { onConnect?.invoke(device) }
            binding.btnChat.setOnClickListener { onChat?.invoke(device) }
            binding.btnDisconnect.setOnClickListener { onDisconnect?.invoke(device) }
            binding.btnRemove.setOnClickListener { onRemove?.invoke(device) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<BluetoothController.DiscoveredDevice>() {
        override fun areItemsTheSame(
            old: BluetoothController.DiscoveredDevice,
            new: BluetoothController.DiscoveredDevice
        ) = old.address == new.address

        override fun areContentsTheSame(
            old: BluetoothController.DiscoveredDevice,
            new: BluetoothController.DiscoveredDevice
        ) = old == new
    }
}
