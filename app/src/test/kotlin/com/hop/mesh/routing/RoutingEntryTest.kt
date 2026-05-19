package com.hop.mesh.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2: Unit tests for routing table entry.
 */
class RoutingEntryTest {

    @Test
    fun routingEntry_holdsAllFields() {
        val e = RoutingEntry(
            nodeId = "AA:BB:CC:DD:EE:FF",
            nextHop = "AA:BB:CC:DD:EE:FF",
            cost = 1,
            lastSeen = 1000L,
            hopCount = 1
        )
        assertEquals("AA:BB:CC:DD:EE:FF", e.nodeId)
        assertEquals("AA:BB:CC:DD:EE:FF", e.nextHop)
        assertEquals(1, e.cost)
        assertEquals(1000L, e.lastSeen)
        assertEquals(1, e.hopCount)
    }

    @Test
    fun routingEntry_equality() {
        val a = RoutingEntry("N1", "H1", 1, 100L, 1)
        val b = RoutingEntry("N1", "H1", 1, 100L, 1)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
