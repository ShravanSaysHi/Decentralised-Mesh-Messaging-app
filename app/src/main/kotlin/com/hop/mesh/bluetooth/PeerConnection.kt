package com.hop.mesh.bluetooth

import android.bluetooth.BluetoothSocket
import com.hop.mesh.messaging.ReceiveBuffer
import kotlinx.coroutines.Job

/**
 * Holds per-peer connection state: socket, I/O streams, receive buffer, and receive coroutine.
 */
data class PeerConnection(
    val address: String,
    val socket: BluetoothSocket,
    val receiveBuffer: ReceiveBuffer,
    var receiveJob: Job? = null
) {
    val inputStream get() = socket.inputStream
    val outputStream get() = socket.outputStream
    val isConnected get() = socket.isConnected

    fun send(data: ByteArray): Boolean {
        return try {
            synchronized(outputStream) {
                outputStream.write(data)
                outputStream.flush()
            }
            true
        } catch (e: Exception) {
            android.util.Log.e("PeerConnection", "Failed to send to $address: ${e.message}")
            false
        }
    }

    fun close() {
        try { receiveJob?.cancel() } catch (_: Exception) {}
        try { inputStream.close() } catch (_: Exception) {}
        try { outputStream.close() } catch (_: Exception) {}
        try { socket.close() } catch (_: Exception) {}
    }
}
