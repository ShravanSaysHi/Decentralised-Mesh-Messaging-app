package com.hop.mesh.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.hop.mesh.messaging.ReceiveBuffer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Multi-peer Bluetooth connection pool.
 * Manages up to [MAX_PEERS] simultaneous RFCOMM connections.
 * Server accept loop runs continuously to accept inbound peers.
 */
class ConnectionManager(context: Context) {

    companion object {
        private const val TAG = "ConnectionManager"
        val MESH_SERVICE_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val MAX_PEERS = 7
    }

    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Currently connected peers keyed by Bluetooth address. */
    private val peers = ConcurrentHashMap<String, PeerConnection>()

    /** Callback invoked when a complete frame is received from a peer. */
    var onFrameReceived: ((fromAddress: String, frame: ByteArray) -> Unit)? = null

    /** Callback invoked when a peer connects or disconnects. */
    var onPeerStateChanged: ((address: String, connected: Boolean) -> Unit)? = null

    private val _connectedPeers = MutableStateFlow<Set<String>>(emptySet())
    val connectedPeers: StateFlow<Set<String>> = _connectedPeers.asStateFlow()

    private var serverSocket: BluetoothServerSocket? = null
    private var acceptJob: Job? = null

    val isBluetoothAvailable: Boolean get() = adapter != null
    val isEnabled: Boolean get() = adapter?.isEnabled == true

    // ── Server (accept inbound connections) ─────────────────────────────────

    @SuppressLint("MissingPermission")
    fun startServer() {
        if (acceptJob?.isActive == true) return
        acceptJob = scope.launch {
            try {
                serverSocket?.close()
                serverSocket = adapter?.listenUsingRfcommWithServiceRecord("HopMesh", MESH_SERVICE_UUID)
                Log.d(TAG, "Server listening for connections")
                while (isActive) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        val address = socket.remoteDevice.address
                        Log.d(TAG, "Accepted connection from $address")
                        if (peers.size >= MAX_PEERS) {
                            Log.w(TAG, "Max peers reached, rejecting $address")
                            try { socket.close() } catch (_: Exception) {}
                            continue
                        }
                        addPeer(address, socket)
                    } catch (e: IOException) {
                        if (!isActive) break
                        Log.e(TAG, "Accept error: ${e.message}")
                        delay(1000)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server failed: ${e.message}")
            }
        }
    }

    fun stopServer() {
        acceptJob?.cancel()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    // ── Client (outbound connections) ────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun connectToPeer(address: String) {
        if (peers.containsKey(address)) return
        if (peers.size >= MAX_PEERS) {
            Log.w(TAG, "Max peers reached, can't connect to $address")
            return
        }
        scope.launch {
            try {
                adapter?.cancelDiscovery()
                val device = adapter?.getRemoteDevice(address) ?: return@launch
                val socket = device.createRfcommSocketToServiceRecord(MESH_SERVICE_UUID)
                socket.connect()
                Log.d(TAG, "Connected to $address")
                addPeer(address, socket)
            } catch (e: Exception) {
                Log.e(TAG, "Connect to $address failed: ${e.message}")
            }
        }
    }

    // ── Peer management ─────────────────────────────────────────────────────

    private fun addPeer(address: String, socket: BluetoothSocket) {
        if (peers.containsKey(address)) {
            try { socket.close() } catch (_: Exception) {}
            return
        }
        val receiveBuffer = ReceiveBuffer { frame ->
            onFrameReceived?.invoke(address, frame)
        }
        val peer = PeerConnection(address, socket, receiveBuffer)
        peers[address] = peer
        updatePeerSet()
        // Start read loop for this peer
        peer.receiveJob = scope.launch {
            // Notify state change once loop is ready
            withContext(Dispatchers.Main) {
                onPeerStateChanged?.invoke(address, true)
            }
            
            val stream = peer.inputStream
            val buffer = ByteArray(4096)
            try {
                while (isActive && peer.isConnected) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    peer.receiveBuffer.append(buffer, 0, read)
                }
            } catch (_: Exception) {
            } finally {
                removePeer(address)
            }
        }
    }

    fun removePeer(address: String) {
        val peer = peers.remove(address) ?: return
        peer.close()
        updatePeerSet()
        onPeerStateChanged?.invoke(address, false)
        Log.d(TAG, "Peer $address disconnected, ${peers.size} remaining")
    }

    private fun updatePeerSet() {
        _connectedPeers.update { peers.keys.toSet() }
    }

    // ── Send ─────────────────────────────────────────────────────────────────

    /** Send raw bytes to a specific peer. Returns false if peer not found or send failed. */
    fun sendToPeer(address: String, data: ByteArray): Boolean {
        val peer = peers[address] ?: return false
        val ok = try {
            peer.send(data)
        } catch (e: Exception) {
            Log.e(TAG, "Send to $address failed explicitly: ${e.message}")
            false
        }
        if (!ok) removePeer(address)
        return ok
    }

    /** Broadcast raw bytes to ALL connected peers. Returns number of successful sends. */
    fun broadcastToAll(data: ByteArray): Int {
        var count = 0
        for ((address, peer) in peers) {
            if (peer.send(data)) count++
            else removePeer(address)
        }
        return count
    }

    /** Broadcast raw bytes to all peers EXCEPT the specified one (for forwarding). */
    fun broadcastExcept(excludeAddress: String, data: ByteArray): Int {
        var count = 0
        for ((address, peer) in peers) {
            if (address == excludeAddress) continue
            if (peer.send(data)) count++
            else removePeer(address)
        }
        return count
    }

    // ── Queries ──────────────────────────────────────────────────────────────

    fun isConnectedTo(address: String): Boolean = peers.containsKey(address)
    fun connectedAddresses(): Set<String> = peers.keys.toSet()
    fun peerCount(): Int = peers.size

    // ── Lifecycle ────────────────────────────────────────────────────────────

    fun disconnectAll() {
        for ((_, peer) in peers) peer.close()
        peers.clear()
        updatePeerSet()
    }

    fun shutdown() {
        stopServer()
        disconnectAll()
        scope.cancel()
    }
}
