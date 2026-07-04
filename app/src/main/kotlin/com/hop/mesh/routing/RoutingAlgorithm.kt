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

        // 1. No existing route: always accept.
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

        // Does the advertised path actually improve on (or match) what we already have?
        val betterCost = costVia < current.cost
        val sameCostBetterHops = (costVia == current.cost && hopsVia < current.hopCount)
        val advertisedIsBetter = betterCost || sameCostBetterHops

        // 2. Newer sequence number (DVR loop prevention). Trust the newer info by
        //    refreshing lastSeen/seq, but only replace the path if it is actually
        //    as good or better — a distant node bumping its sequence must not be
        //    able to hijack an existing shorter route.
        if (advertisedSeq > current.sequenceNumber) {
            return if (advertisedIsBetter) {
                current.copy(
                    deviceName = deviceName,
                    nextHop = fromNeighbor,
                    cost = costVia,
                    lastSeen = now,
                    hopCount = hopsVia,
                    sequenceNumber = advertisedSeq
                )
            } else {
                // Keep the better path; just record that this route is still fresh.
                current.copy(
                    lastSeen = now,
                    sequenceNumber = advertisedSeq
                )
            }
        }

        // 3. Same sequence, strictly better cost or hop count.
        if (advertisedSeq == current.sequenceNumber && advertisedIsBetter) {
            return current.copy(
                deviceName = deviceName,
                nextHop = fromNeighbor,
                cost = costVia,
                lastSeen = now,
                hopCount = hopsVia,
                sequenceNumber = advertisedSeq
            )
        }

        // 4. Older or non-improving update: ignore.
        return null
    }

    fun isStale(entry: RoutingEntry, now: Long, ttlMs: Long = DEFAULT_TTL_MS): Boolean {
        return (now - entry.lastSeen) > ttlMs
    }
}
