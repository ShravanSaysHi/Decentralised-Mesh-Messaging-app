package com.hop.mesh.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hop.mesh.bluetooth.BluetoothController
import com.hop.mesh.messaging.MessagingLayer
import com.hop.mesh.routing.RoutingEntry
import com.hop.mesh.service.MeshService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel that binds to [MeshService] and exposes mesh state to the UI.
 * All mesh operations are delegated to the service.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private var meshService: MeshService? = null
    private var bound = false

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    val localNodeId: String get() = meshService?.localNodeId ?: "Starting…"

    /** BluetoothController access for discovery receiver. */
    val bluetoothController: BluetoothController?
        get() = meshService?.bluetoothController

    val isBluetoothAvailable: Boolean get() = meshService?.connectionManager?.isBluetoothAvailable ?: false
    val isBluetoothEnabled: Boolean get() = meshService?.connectionManager?.isEnabled ?: false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as MeshService.MeshBinder).getService()
            meshService = service
            bound = true
            _uiState.update { it.copy(serviceConnected = true) }

            // Collect service state
            viewModelScope.launch {
                service.meshState.collect { state ->
                    _uiState.update { current ->
                        val destId = current.selectedDestinationId
                        val rawMessages = state.inboxMessages
                        val filteredMessages = rawMessages.filter { msg ->
                            if (destId == "BROADCAST") msg.destinationId == "BROADCAST"
                            else (msg.sourceId == destId && msg.destinationId == localNodeId) ||
                                 (msg.sourceId == localNodeId && msg.destinationId == destId)
                        }

                        current.copy(
                            connectedPeers = state.connectedPeers,
                            routingEntries = state.routingEntries,
                            reachablePeers = buildReachablePeers(state.routingEntries),
                            inboxMessages = filteredMessages,
                            isServerRunning = state.isServerRunning,
                            discoveredDevices = state.discoveredDevices,
                            connectionStatus = buildStatus(state),
                            knownDestinations = buildDestinations(state.discoveredDevices, state.routingEntries),
                            nodeNames = state.routingEntries.associate { it.nodeId to it.deviceName }
                        )
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            meshService = null
            bound = false
            _uiState.update { it.copy(serviceConnected = false) }
        }
    }

    init {
        startAndBindService()
    }

    private fun startAndBindService() {
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, MeshService::class.java)
        ctx.startForegroundService(intent)
        ctx.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onCleared() {
        if (bound) {
            getApplication<Application>().unbindService(serviceConnection)
            bound = false
        }
        super.onCleared()
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    fun selectDestination(nodeId: String?) {
        val dest = nodeId ?: "BROADCAST"
        _uiState.update { current ->
            val msgs = meshService?.meshState?.value?.inboxMessages ?: emptyList()
            current.copy(
                selectedDestinationId = dest,
                inboxMessages = msgs.filter { msg ->
                    if (dest == "BROADCAST") msg.destinationId == "BROADCAST"
                    else (msg.sourceId == dest && msg.destinationId == localNodeId) ||
                         (msg.sourceId == localNodeId && msg.destinationId == dest)
                }
            )
        }
    }

    fun startServer() = meshService?.startServer()

    fun startDiscovery() = meshService?.startDiscovery()

    fun connectTo(address: String) = meshService?.connectToPeer(address)

    fun disconnectPeer(id: String) {
        viewModelScope.launch {
            // If 'id' is a UUID, find its direct bluetooth address
            val bluetoothAddress = meshService?.routingRepository?.getNextHop(id) ?: id
            meshService?.disconnectPeer(bluetoothAddress)
        }
    }

    fun disconnectAll() = meshService?.disconnectAll()

    fun sendMessageToNode(destinationId: String, text: String): Boolean {
        return meshService?.sendMessage(destinationId, text) ?: false
    }

    /** Broadcast a message to all devices in the mesh. */
    fun broadcastMessage(text: String): Boolean {
        return meshService?.broadcastMessage(text) ?: false
    }

    /** Remove a device from the discovered list. */
    fun removeDevice(address: String) {
        meshService?.removeDiscoveredDevice(address)
    }

    fun refreshBondedDevices() = meshService?.refreshBondedDevices()

    fun clearKnownDestinations() {
        _uiState.update { it.copy(knownDestinations = emptyList()) }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun buildStatus(state: MeshService.MeshState): String {
        val peers = state.connectedPeers.size
        return when {
            peers > 0 -> "Mesh active — $peers peer(s) connected"
            state.isServerRunning -> "Hosting — waiting for peers…"
            else -> "Disconnected"
        }
    }

    private fun buildDestinations(
        devices: Set<BluetoothController.DiscoveredDevice>,
        routingEntries: List<RoutingEntry>
    ): List<DestinationItem> {
        val map = mutableMapOf<String, String>()
        
        // Add BROADCAST explicitly
        map["BROADCAST"] = "Group Chat (Global)"

        val now = System.currentTimeMillis()
        val staleThreshold = 10 * 60 * 1000L
        val activePeers = meshService?.meshState?.value?.connectedPeers ?: emptySet()
        
        for (e in routingEntries) {
            if (e.nodeId == localNodeId) continue
            
            // If it's a direct neighbor and active, OR if it's within the threshold
            val isDirectActive = (e.hopCount == 1 && e.nextHop in activePeers)
            if (!isDirectActive && (now - e.lastSeen > staleThreshold)) continue
            
            val label = if (e.deviceName.isNotBlank() && e.deviceName != "Unknown") {
                e.deviceName
            } else {
                "Node: ${e.nodeId.takeLast(6)}"
            }
            map.putIfAbsent(e.nodeId, label)
        }
        return map.map { (id, label) -> DestinationItem(id, label) }
    }

    private fun buildReachablePeers(
        routingEntries: List<RoutingEntry>
    ): List<BluetoothController.DiscoveredDevice> {
        val now = System.currentTimeMillis()
        val staleThreshold = 10 * 60 * 1000L
        val activePeers = meshService?.meshState?.value?.connectedPeers ?: emptySet()

        return routingEntries
            .filter { 
                if (it.nodeId == localNodeId) return@filter false
                val isDirectActive = (it.hopCount == 1 && it.nextHop in activePeers)
                isDirectActive || (now - it.lastSeen < staleThreshold)
            }
            .map { entry ->
                BluetoothController.DiscoveredDevice(
                    address = entry.nodeId,
                    name = if (entry.deviceName != "Unknown") entry.deviceName else "Node ${entry.nodeId.takeLast(6)}",
                    isBonded = entry.hopCount <= 1
                )
            }
    }

    // ── Data ─────────────────────────────────────────────────────────────────

    data class DestinationItem(val nodeId: String, val displayName: String) {
        override fun toString() = displayName
    }

    data class MainUiState(
        val serviceConnected: Boolean = false,
        val connectedPeers: Set<String> = emptySet(),
        val discoveredDevices: Set<BluetoothController.DiscoveredDevice> = emptySet(),
        val connectionStatus: String = "Starting…",
        val routingEntries: List<RoutingEntry> = emptyList(),
        val reachablePeers: List<BluetoothController.DiscoveredDevice> = emptyList(),
        val inboxMessages: List<MessagingLayer.InboxMessage> = emptyList(),
        val isServerRunning: Boolean = false,
        val selectedDestinationId: String = "BROADCAST",
        val knownDestinations: List<DestinationItem> = emptyList(),
        val nodeNames: Map<String, String> = emptyMap()
    )
}
