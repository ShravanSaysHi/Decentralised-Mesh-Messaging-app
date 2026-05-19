package com.hop.mesh.routing

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for RoutingAlgorithm – covers the edge cases fixed in code review:
 *  - MAX_HOPS boundary (Bug 2)
 *  - Sequence-number-only update should not overwrite a better route (Bug 3)
 */
class RoutingAlgorithmTest {

    private val now = System.currentTimeMillis()

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun entry(
        nodeId: String = "node-A",
        nextHop: String = "hop-X",
        cost: Int = 2,
        hopCount: Int = 2,
        seq: Long = 1L,
        lastSeen: Long = now
    ) = RoutingEntry(nodeId, nextHop, cost, lastSeen, hopCount, seq)

    // ── Bug 2: MAX_HOPS boundary ──────────────────────────────────────────────

    @Test
    fun mergeUpdate_acceptsRouteAtExactlyMaxHops() {
        // advertisedHops = MAX_HOPS - 1 → hopsVia = MAX_HOPS (should be accepted)
        val hops = RoutingAlgorithm.MAX_HOPS - 1
        val result = RoutingAlgorithm.mergeUpdate(
            current = null,
            nodeId = "N",
            fromNeighbor = "H",
            advertisedCost = 1,
            advertisedHops = hops,
            advertisedSeq = 1L,
            now = now
        )
        assertNotNull("Route at exactly MAX_HOPS hops should be accepted", result)
        assertEquals(RoutingAlgorithm.MAX_HOPS, result!!.hopCount)
    }

    @Test
    fun mergeUpdate_rejectsRouteExceedingMaxHops() {
        // advertisedHops = MAX_HOPS → hopsVia = MAX_HOPS + 1 (should be rejected)
        val result = RoutingAlgorithm.mergeUpdate(
            current = null,
            nodeId = "N",
            fromNeighbor = "H",
            advertisedCost = 1,
            advertisedHops = RoutingAlgorithm.MAX_HOPS,
            advertisedSeq = 1L,
            now = now
        )
        assertNull("Route exceeding MAX_HOPS should be rejected", result)
    }

    // ── Bug 3: seq-only update must not overwrite a better route ─────────────

    @Test
    fun mergeUpdate_seqOnlyUpdatePreservesExistingBetterRoute() {
        val existing = entry(nextHop = "direct-hop", cost = 1, hopCount = 1, seq = 5L)
        // Incoming: same node, higher seq but worse cost/hops
        val result = RoutingAlgorithm.mergeUpdate(
            current = existing,
            nodeId = existing.nodeId,
            fromNeighbor = "far-hop",
            advertisedCost = 10,       // costVia = 11, much worse
            advertisedHops = 8,        // hopsVia = 9, worse
            advertisedSeq = 99L,       // newer sequence
            now = now + 1000
        )
        // Should refresh lastSeen/seq but keep the better nextHop/cost/hopCount
        assertNotNull(result)
        assertEquals("direct-hop", result!!.nextHop)
        assertEquals(1, result.cost)
        assertEquals(1, result.hopCount)
        assertEquals(99L, result.sequenceNumber)
        assertEquals(now + 1000, result.lastSeen)
    }

    // ── General correctness ──────────────────────────────────────────────────

    @Test
    fun mergeUpdate_newRouteCreated_whenNoCurrent() {
        val result = RoutingAlgorithm.mergeUpdate(null, "N", "H", 1, 1, 1L, now)
        assertNotNull(result)
        assertEquals("N", result!!.nodeId)
        assertEquals("H", result.nextHop)
        assertEquals(2, result.cost)
        assertEquals(2, result.hopCount)
    }

    @Test
    fun mergeUpdate_betterCostWins() {
        val existing = entry(nextHop = "old-hop", cost = 5, hopCount = 3)
        val result = RoutingAlgorithm.mergeUpdate(existing, existing.nodeId, "new-hop", 1, 1, existing.sequenceNumber + 1, now)
        assertNotNull(result)
        assertEquals("new-hop", result!!.nextHop)
        assertEquals(2, result.cost)
    }

    @Test
    fun mergeUpdate_infiniteCostRejected() {
        val result = RoutingAlgorithm.mergeUpdate(
            null, "N", "H",
            advertisedCost = RoutingAlgorithm.INFINITE_COST,
            advertisedHops = 1,
            advertisedSeq = 1L,
            now = now
        )
        assertNull(result)
    }
}
