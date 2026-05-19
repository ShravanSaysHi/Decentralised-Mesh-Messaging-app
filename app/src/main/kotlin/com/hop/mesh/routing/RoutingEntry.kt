package com.hop.mesh.routing

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Phase 2: Single row in the routing table.
 * NodeID | NextHop | Cost | LastSeen | HopCount
 *
 * For Phase 2, remote node identity is keyed by Bluetooth address until
 * we exchange UUIDs in the routing protocol (Phase 3).
 */
@Entity(
    tableName = "routing_table"
)
data class RoutingEntry(
    @PrimaryKey
    val nodeId: String = "",
    val deviceName: String = "Unknown",
    val nextHop: String = "",
    val cost: Int = 0,
    val lastSeen: Long = 0L,
    val hopCount: Int = 0,
    /** Phase 3: Sequence number for DVR loop prevention. */
    val sequenceNumber: Long = 0L
)
