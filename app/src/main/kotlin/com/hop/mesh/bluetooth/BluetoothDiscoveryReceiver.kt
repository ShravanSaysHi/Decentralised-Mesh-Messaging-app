package com.hop.mesh.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * BroadcastReceiver for Bluetooth device discovery events.
 * Notifies [BluetoothController] when devices are found or discovery finishes.
 */
class BluetoothDiscoveryReceiver(
    private val controller: BluetoothController
) : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            BluetoothDevice.ACTION_FOUND -> {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                device?.let { controller.addDiscoveredDevice(toDiscovered(it)) }
            }
            BluetoothAdapter.ACTION_DISCOVERY_STARTED -> { /* optional: update UI */ }
            BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> { /* optional: update UI */ }
        }
    }

    @SuppressLint("MissingPermission")
    private fun toDiscovered(device: BluetoothDevice): BluetoothController.DiscoveredDevice {
        val name = device.name ?: "Unknown"
        return BluetoothController.DiscoveredDevice(
            address = device.address,
            name = name,
            isBonded = false
        )
    }

    fun register(context: Context) {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ContextCompat.registerReceiver(
            context,
            this,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun unregister(context: Context) {
        try {
            context.unregisterReceiver(this)
        } catch (_: Exception) { }
    }
}
