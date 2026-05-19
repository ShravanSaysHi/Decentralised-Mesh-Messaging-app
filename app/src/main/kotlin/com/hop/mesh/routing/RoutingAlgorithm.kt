package com.hop.mesh.routing

/**
 * Phase 3: Distance-vector style routing logic.
 * - Prefer minimum cost, then minimum hops.
 * - Use sequence numbers to prefer newer route info (loop prevention).
 * - Routes expire via lastSeen (TTL).
 */
object RoutingAlgorithm {

    const val DEFAULT_TTL_MS = 5 * 60 * 1000L
    const val MAX_HOPS = 15
    const val INFINITE_COST = 999999

    /**
     * Merge an update from a neighbor (DVR).
     * For each entry from neighbor: cost_via_neighbor = entry.cost + 1, hopCount = entry.hopCount + 1.
     * Accept if: no existing route, or better cost/hops, or same route with newer sequence.
     */
    /**
     * @param nodeId the remote node id this route is for (when creating new entry).
     */
    fun mergeUpdate(
        current: RoutingEntry?,
        nodeId: String,
        deviceName: String,
        fromNeighbor: String,
        advertisedCost: Int,
        advertisedHops: Int,
        advertisedSeq: Long,
        now: Long
    ): RoutingEntry? {
        val costVia = advertisedCost + 1
        val hopsVia = advertisedHops + 1
        if (costVia >= INFINITE_COST || hopsVia > MAX_HOPS) return null

        // 1. New sequence number logic (DVR standard: newer sequence is always trusted)
        if (current != null && advertisedSeq > current.sequenceNumber) {
            return current.copy(
                deviceName = deviceName,
                nextHop = fromNeighbor,
                cost = costVia,
                lastSeen = now,
                hopCount = hopsVia,
                sequenceNumber = advertisedSeq
            )
        }

        // 2. No existing route
        if (current == null) {
            return RoutingEntry(
                nodeId = nodeId,
                deviceName = deviceName,
                nextHop = fromNeighbor,
                cost = costVia,
                lastSeen = now,
                hopCount = hopsVia,
                sequenceNumber = advertisedSeq
            )
        }

        // 3. Same sequence, better cost or better hop count
        if (advertisedSeq == current.sequenceNumber) {
            val betterCost = costVia < current.cost
            val sameCostBetterHops = (costVia == current.cost && hopsVia < current.hopCount)
            
            if (betterCost || sameCostBetterHops) {
                return current.copy(
                    deviceName = deviceName,
                    nextHop = fromNeighbor,
                    cost = costVia,
                    lastSeen = now,
                    hopCount = hopsVia,
                    sequenceNumber = advertisedSeq
                )
            }
        }

        return null
    }

    fun isStale(entry: RoutingEntry, now: Long, ttlMs: Long = DEFAULT_TTL_MS): Boolean {
        return (now - entry.lastSeen) > ttlMs
    }
}
