package com.hop.mesh.bluetooth

/**
 * Transport abstraction used by the messaging layer to move raw frames between
 * peers. [ConnectionManager] is the production (Bluetooth RFCOMM) implementation;
 * tests provide in-memory implementations to exercise multi-hop routing without
 * real radios.
 */
interface MessageTransport {
    /** Send raw bytes to a specific peer. Returns false if the peer is unreachable. */
    fun sendToPeer(address: String, data: ByteArray): Boolean

    /** Broadcast raw bytes to all connected peers. Returns the number of successful sends. */
    fun broadcastToAll(data: ByteArray): Int

    /** Broadcast to all peers except one (used when forwarding, to avoid echoing back). */
    fun broadcastExcept(excludeAddress: String, data: ByteArray): Int

    /** Whether we currently have a direct connection to [address]. */
    fun isConnectedTo(address: String): Boolean

    /** Number of currently connected peers. */
    fun peerCount(): Int
}
