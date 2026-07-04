package com.hop.mesh.routing

/**
 * Route lookup abstraction used by the messaging layer to decide the next hop
 * toward a destination. [RoutingRepository] is the production (Room-backed)
 * implementation; tests provide in-memory implementations.
 */
interface RouteResolver {
    /** Next-hop peer address toward [destinationId], or null if no route is known. */
    suspend fun getNextHop(destinationId: String): String?

    /** Merge a routing-table advertisement received from a neighbor. */
    fun applyUpdatesFromNeighborAsync(from: String, entries: List<RoutingEntry>)
}
