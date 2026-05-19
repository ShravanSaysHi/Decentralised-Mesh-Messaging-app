package com.hop.mesh.routing

import android.content.Context
import com.hop.mesh.identity.NodeIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase 2: Repository for node identity and routing table.
 * - Provides this node's UUID.
 * - Maintains routing table in Room; exposes as StateFlow.
 * - Updates table when neighbors are seen (Bluetooth address = node id for Phase 2).
 */
class RoutingRepository(context: Context) {

    private val appContext = context.applicationContext
    private val nodeIdentity = NodeIdentity(appContext)
    private val db = MeshDatabase.getInstance(appContext)
    private val dao = db.routingDao()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** This device's unique Node ID (UUID). */
    val localNodeId: String get() = nodeIdentity.nodeId

    /** Routing table entries, sorted by cost then hop count. */
    val routingTable = dao.getAllEntries()
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Stale threshold: entries not seen in 10 minutes are removed. */
    private val staleThresholdMs = 10 * 60 * 1000L

    init {
        scope.launch { pruneStaleEntries() }
    }

    private val _sequenceNumber = AtomicLong(0L)
    private fun nextSequence(): Long = _sequenceNumber.incrementAndGet()

    /**
     * Record or update a direct neighbor with its persistent UUID.
     */
    fun upsertNeighbor(bluetoothAddress: String, uuid: String, deviceName: String = "Unknown") {
        scope.launch {
            val now = System.currentTimeMillis()
            val seq = nextSequence()
            dao.insert(
                RoutingEntry(
                    nodeId = uuid, // Use UUID as primary key
                    deviceName = deviceName,
                    nextHop = bluetoothAddress,
                    cost = 1,
                    lastSeen = now,
                    hopCount = 1,
                    sequenceNumber = seq
                )
            )
        }
    }

    /**
     * Ensure this local node is in the routing table (cost 0) so others can learn about us.
     */
    fun upsertSelf(deviceName: String) {
        scope.launch {
            val now = System.currentTimeMillis()
            dao.insert(
                RoutingEntry(
                    nodeId = localNodeId,
                    deviceName = deviceName,
                    nextHop = "self",
                    cost = 0,
                    lastSeen = now,
                    hopCount = 0,
                    sequenceNumber = nextSequence()
                )
            )
        }
    }

    /** Phase 3: Apply DVR update from a neighbor (Bluetooth address). */
    suspend fun applyUpdatesFromNeighbor(fromNeighborAddress: String, entries: List<RoutingEntry>) {
        val now = System.currentTimeMillis()
        for (adv in entries) {
            if (adv.nodeId == localNodeId) continue
            val current = dao.getEntry(adv.nodeId)
            val merged = RoutingAlgorithm.mergeUpdate(
                current, adv.nodeId, adv.deviceName, fromNeighborAddress,
                adv.cost, adv.hopCount, adv.sequenceNumber, now
            )
            if (merged != null) dao.insert(merged)
        }
    }

    fun applyUpdatesFromNeighborAsync(from: String, entries: List<RoutingEntry>) {
        scope.launch { applyUpdatesFromNeighbor(from, entries) }
    }

    /**
     * Remove a neighbor (e.g. device no longer visible).
     */
    fun removeNeighbor(nodeId: String) {
        scope.launch {
            dao.deleteByNodeId(nodeId)
        }
    }

    /**
     * Prune entries older than [staleThresholdMs].
     */
    private suspend fun pruneStaleEntries() {
        val before = System.currentTimeMillis() - staleThresholdMs
        dao.deleteStale(before)
    }

    fun pruneStale() {
        scope.launch { pruneStaleEntries() }
    }

    suspend fun getNextHop(destinationId: String): String? = dao.getNextHop(destinationId)

    /** Refresh lastSeen for a node. */
    fun heartbeat(nodeId: String) {
        scope.launch {
            dao.updateLastSeen(nodeId, System.currentTimeMillis())
        }
    }

    /**
     * Invalidate all routes that go through this next hop (e.g. neighbor disconnected).
     */
    fun invalidateRoutesVia(bluetoothAddress: String) {
        scope.launch {
            dao.deleteByNextHop(bluetoothAddress)
        }
    }

    suspend fun getRoutingTableSnapshot(): List<RoutingEntry> = dao.getAllEntriesSnapshot()
}
