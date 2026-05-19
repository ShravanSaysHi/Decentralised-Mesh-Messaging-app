package com.hop.mesh.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hop.mesh.bluetooth.BluetoothController
import com.hop.mesh.bluetooth.ConnectionManager
import com.hop.mesh.encryption.KeyStore
import com.hop.mesh.encryption.MessageCrypto
import com.hop.mesh.encryption.X25519Manager
import com.hop.mesh.messaging.MessagingLayer
import com.hop.mesh.messaging.WireProtocol
import com.hop.mesh.routing.MeshDatabase
import com.hop.mesh.routing.RoutingRepository
import com.hop.mesh.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Foreground service that owns the mesh networking engine.
 * Survives activity lifecycle — keeps Bluetooth connections alive.
 */
class MeshService : Service() {

    companion object {
        private const val TAG = "MeshService"
        private const val CHANNEL_ID = "hop_mesh_channel"
        private const val NOTIFICATION_ID = 1
        private const val ROUTING_BROADCAST_INTERVAL = 30_000L
        private const val STALE_PRUNE_INTERVAL = 60_000L
        private const val AUTO_RECONNECT_INTERVAL = 45_000L
        private const val CONNECTION_WATCHDOG_INTERVAL = 60_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Core components ─────────────────────────────────────────────────────

    lateinit var connectionManager: ConnectionManager private set
    lateinit var routingRepository: RoutingRepository private set
    lateinit var messagingLayer: MessagingLayer private set
    lateinit var bluetoothController: BluetoothController private set
    private lateinit var crypto: MessageCrypto

    // ── UI State ────────────────────────────────────────────────────────────

    private val _meshState = MutableStateFlow(MeshState())
    val meshState: StateFlow<MeshState> = _meshState.asStateFlow()

    data class MeshState(
        val connectedPeers: Set<String> = emptySet(),
        val routingEntries: List<com.hop.mesh.routing.RoutingEntry> = emptyList(),
        val inboxMessages: List<MessagingLayer.InboxMessage> = emptyList(),
        val isServerRunning: Boolean = false,
        val discoveredDevices: Set<BluetoothController.DiscoveredDevice> = emptySet()
    )

    // ── Service lifecycle ───────────────────────────────────────────────────

    inner class MeshBinder : Binder() {
        fun getService(): MeshService = this@MeshService
    }

    private val binder = MeshBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "MeshService starting")

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(0))

        // Initialize all mesh components
        bluetoothController = BluetoothController(this)
        connectionManager = ConnectionManager(this)
        routingRepository = RoutingRepository(this)
        val keyStore = KeyStore(this)
        crypto = MessageCrypto { keyStore.getOrCreateKey() }
        val db = MeshDatabase.getInstance(this)
        messagingLayer = MessagingLayer(
            localNodeId = routingRepository.localNodeId,
            routingRepository = routingRepository,
            connectionManager = connectionManager,
            crypto = crypto,
            sessionKeyDao = db.sessionKeyDao(),
            scope = scope
        )

        // Initialize E2EE Identity
        try {
            val privateRaw = keyStore.getOrCreateIdentityPrivateKey()
            val privParams = X25519Manager.privateKeyFromBytes(privateRaw)
            val pubParams = privParams.generatePublicKey()
            val pair = org.bouncycastle.crypto.AsymmetricCipherKeyPair(pubParams, privParams)
            messagingLayer.setIdentityKeyPair(pair)
            Log.d(TAG, "E2EE Identity initialized (X25519)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize E2EE Identity: ${e.message}")
        }

        // Ensure self is in the routing table (Phase 3 identity mapping)
        routingRepository.upsertSelf(bluetoothController.getLocalName())

        // Wire MessagingLayer callbacks
        messagingLayer.onIdentityReceived = { fromAddr, uuid, name ->
            Log.d(TAG, "Received identity from $fromAddr: $uuid ($name)")
            routingRepository.upsertNeighbor(fromAddr, uuid, name)
        }

        // Wire ConnectionManager callbacks
        connectionManager.onFrameReceived = { fromPeer, frame ->
            // Update heartbeat for this direct neighbor on any data
            scope.launch {
                val entries = routingRepository.getRoutingTableSnapshot()
                val neighbor = entries.find { it.nextHop == fromPeer && it.hopCount == 1 }
                neighbor?.let { routingRepository.heartbeat(it.nodeId) }
            }
            messagingLayer.receiveFrame(fromPeer, frame)
        }
        connectionManager.onPeerStateChanged = { address, connected ->
            if (connected) {
                // SEND LOCAL IDENTITY IMMEDIATELY
                scope.launch {
                    val frame = WireProtocol.encodeIdentity(routingRepository.localNodeId, bluetoothController.getLocalName())
                    connectionManager.sendToPeer(address, frame)
                }
                
                // TRIGGER IMMEDIATE ROUTING BROADCAST
                scope.launch {
                    delay(800) // Slightly longer to allow identity to be processed
                    val entries = routingRepository.getRoutingTableSnapshot()
                    if (entries.isNotEmpty()) {
                        messagingLayer.broadcastRoutingUpdate(entries)
                    }
                }
            } else {
                // Neighbor disconnected — immediately invalidate all routes via this peer
                routingRepository.invalidateRoutesVia(address)
            }
            updateNotification()
        }

        // Collect state flows
        collectFlows()
        startPeriodicTasks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "MeshService stopping")
        scope.cancel()
        connectionManager.shutdown()
        super.onDestroy()
    }

    // ── State collection ────────────────────────────────────────────────────

    private fun collectFlows() {
        scope.launch {
            connectionManager.connectedPeers.collect { peers ->
                _meshState.update { it.copy(connectedPeers = peers) }
            }
        }
        scope.launch {
            var lastTableSize = 0
            routingRepository.routingTable.collect { entries ->
                _meshState.update { it.copy(routingEntries = entries) }
                
                // If table grew (new node!), trigger immediate broadcast to help others learn
                if (entries.size > lastTableSize) {
                    lastTableSize = entries.size
                    scope.launch {
                        delay(500) // Small settle time
                        val snapshot = routingRepository.getRoutingTableSnapshot()
                        if (snapshot.isNotEmpty()) {
                            messagingLayer.broadcastRoutingUpdate(snapshot)
                        }
                    }
                }
            }
        }
        scope.launch {
            messagingLayer.inbox.collect { msg ->
                _meshState.update { it.copy(inboxMessages = it.inboxMessages + msg) }
            }
        }
        scope.launch {
            bluetoothController.discoveredDevices.collect { devices ->
                val bonded = bluetoothController.getBondedDevices()
                _meshState.update { it.copy(discoveredDevices = devices + bonded) }
            }
        }
    }

    // ── Periodic tasks ──────────────────────────────────────────────────────

    private fun startPeriodicTasks() {
        // Broadcast routing table
        scope.launch {
            while (isActive) {
                delay(ROUTING_BROADCAST_INTERVAL)
                try {
                    val entries = routingRepository.getRoutingTableSnapshot()
                    if (entries.isNotEmpty()) {
                        messagingLayer.broadcastRoutingUpdate(entries)
                    }
                } catch (_: Exception) {}
            }
        }
        // Prune stale entries
        scope.launch {
            while (isActive) {
                delay(STALE_PRUNE_INTERVAL)
                routingRepository.pruneStale()
            }
        }
        // Auto-reconnect to bonded devices
        scope.launch {
            while (isActive) {
                delay(AUTO_RECONNECT_INTERVAL)
                autoReconnect()
            }
        }
        // Connection Watchdog: refresh lastSeen for all actively connected neighbors
        scope.launch {
            while (isActive) {
                delay(CONNECTION_WATCHDOG_INTERVAL)
                try {
                    val connected = connectionManager.connectedAddresses()
                    val entries = routingRepository.getRoutingTableSnapshot()
                    for (entry in entries) {
                        // If it's a direct neighbor and we are physically connected, refresh it
                        if (entry.hopCount == 1 && entry.nextHop in connected) {
                            routingRepository.heartbeat(entry.nodeId)
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun autoReconnect() {
        try {
            val bonded = bluetoothController.getBondedDevices()
            val connected = connectionManager.connectedAddresses()
            for (device in bonded) {
                if (device.address !in connected && connectionManager.peerCount() < ConnectionManager.MAX_PEERS) {
                    connectionManager.connectToPeer(device.address)
                }
            }
        } catch (_: Exception) {}
    }

    // ── Public API for Activity/ViewModel ────────────────────────────────────

    val localNodeId: String get() = routingRepository.localNodeId

    fun startServer() {
        connectionManager.startServer()
        _meshState.update { it.copy(isServerRunning = true) }
    }

    fun connectToPeer(address: String) {
        connectionManager.connectToPeer(address)
    }

    fun disconnectPeer(address: String) {
        connectionManager.removePeer(address)
    }

    fun disconnectAll() {
        connectionManager.disconnectAll()
    }

    fun startDiscovery() {
        bluetoothController.startDiscovery()
    }

    fun sendMessage(destinationId: String, text: String): Boolean {
        val success = messagingLayer.sendMessage(destinationId, text)
        if (success) {
            val msg = MessagingLayer.InboxMessage(
                messageId = java.util.UUID.randomUUID().toString(),
                sourceId = localNodeId,
                destinationId = destinationId,
                body = text,
                receivedAt = System.currentTimeMillis()
            )
            _meshState.update { it.copy(inboxMessages = it.inboxMessages + msg) }
        }
        return success
    }

    fun broadcastMessage(text: String): Boolean {
        // Send a message with a special destination ID
        val success = messagingLayer.sendMessage("BROADCAST", text)
        if (success) {
            val msg = MessagingLayer.InboxMessage(
                messageId = java.util.UUID.randomUUID().toString(),
                sourceId = localNodeId,
                destinationId = "BROADCAST",
                body = text,
                receivedAt = System.currentTimeMillis()
            )
            _meshState.update { it.copy(inboxMessages = it.inboxMessages + msg) }
        }
        return success
    }

    fun removeDiscoveredDevice(address: String) {
        // Remove from our localized set so it disappears from the UI
        _meshState.update { state ->
            val updated = state.discoveredDevices.filter { it.address != address }.toSet()
            state.copy(discoveredDevices = updated)
        }
    }

    fun refreshBondedDevices() {
        val bonded = bluetoothController.getBondedDevices()
        _meshState.update { it.copy(discoveredDevices = it.discoveredDevices + bonded) }
        // Note: We don't upsertNeighbor here anymore because we wait for the UUID handshake
    }

    // ── Notification ────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Hop Mesh Network",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Mesh networking service"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(peerCount: Int): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Hop Mesh Active")
            .setContentText("$peerCount peer(s) connected")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification() {
        val count = connectionManager.peerCount()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(count))
    }
}
