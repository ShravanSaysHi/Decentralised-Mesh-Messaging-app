package com.hop.mesh.messaging

import com.hop.mesh.bluetooth.MessageTransport
import com.hop.mesh.encryption.MessageCrypto
import com.hop.mesh.encryption.SessionKeyDao
import com.hop.mesh.encryption.SessionKeyEntry
import com.hop.mesh.encryption.X25519Manager
import com.hop.mesh.routing.RouteResolver
import com.hop.mesh.routing.RoutingEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * In-process multi-node simulation of the mesh.
 *
 * Emulators cannot emulate peer-to-peer Bluetooth, so instead of faking radios we
 * wire real [MessagingLayer] instances together through in-memory transports and
 * assert that a message actually hops through intermediate nodes to its
 * destination — exercising real routing, forwarding, TTL, dedup, the X25519
 * handshake, and AES-256-GCM decryption end to end.
 *
 * Topologies are lines where non-adjacent nodes cannot hear each other, so
 * delivery is only possible if forwarding works.
 */
class MeshMultiHopTest {

    // ── Simulated network ────────────────────────────────────────────────────

    private class SimNetwork {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val psk = ByteArray(32) { (it * 7 + 3).toByte() }
        val nodes = ConcurrentHashMap<String, SimNode>()

        fun addNode(addr: String, nodeId: String): SimNode =
            SimNode(addr, nodeId, this).also { nodes[addr] = it }

        /** Create a bidirectional radio link (both can hear each other). */
        fun link(a: String, b: String) {
            nodes.getValue(a).neighbors.add(b)
            nodes.getValue(b).neighbors.add(a)
        }

        /** Deliver already-length-prefixed bytes from one node to another, asynchronously. */
        fun deliver(fromAddr: String, toAddr: String, wrapped: ByteArray) {
            val target = nodes[toAddr] ?: return
            scope.launch { target.feed(fromAddr, wrapped) }
        }

        fun shutdown() = scope.cancel()
    }

    private class SimNode(val addr: String, val nodeId: String, val net: SimNetwork) {
        val neighbors: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        /** destinationNodeId -> next-hop peer address. */
        val routes = ConcurrentHashMap<String, String>()
        val inbox = Collections.synchronizedList(mutableListOf<MessagingLayer.InboxMessage>())

        private val buffers = ConcurrentHashMap<String, ReceiveBuffer>()

        private val transport = object : MessageTransport {
            override fun sendToPeer(address: String, data: ByteArray): Boolean {
                if (address !in neighbors) return false
                net.deliver(addr, address, data)
                return true
            }
            override fun broadcastToAll(data: ByteArray): Int {
                val peers = neighbors.toList()
                peers.forEach { net.deliver(addr, it, data) }
                return peers.size
            }
            override fun broadcastExcept(excludeAddress: String, data: ByteArray): Int {
                val peers = neighbors.toList().filter { it != excludeAddress }
                peers.forEach { net.deliver(addr, it, data) }
                return peers.size
            }
            override fun isConnectedTo(address: String) = address in neighbors
            override fun peerCount() = neighbors.size
        }

        private val resolver = object : RouteResolver {
            override suspend fun getNextHop(destinationId: String): String? = routes[destinationId]
            override fun applyUpdatesFromNeighborAsync(from: String, entries: List<RoutingEntry>) {}
        }

        private val sessionDao = object : SessionKeyDao {
            val map = ConcurrentHashMap<String, ByteArray>()
            override suspend fun getKey(nodeId: String) = map[nodeId]?.let { SessionKeyEntry(nodeId, it) }
            override suspend fun getAllKeys() = map.map { SessionKeyEntry(it.key, it.value) }
            override suspend fun insert(entry: SessionKeyEntry) { map[entry.nodeId] = entry.sessionKey }
            override suspend fun delete(nodeId: String) { map.remove(nodeId) }
        }

        val messaging = MessagingLayer(
            localNodeId = nodeId,
            routingRepository = resolver,
            connectionManager = transport,
            crypto = MessageCrypto { net.psk },
            sessionKeyDao = sessionDao,
            scope = net.scope
        )

        init {
            messaging.setIdentityKeyPair(X25519Manager.generateKeyPair())
            net.scope.launch { messaging.inbox.collect { inbox.add(it) } }
        }

        /** Simulate bytes arriving on the RFCOMM socket from [fromAddr]. */
        fun feed(fromAddr: String, wrapped: ByteArray) {
            buffers.getOrPut(fromAddr) {
                ReceiveBuffer { frame -> messaging.receiveFrame(fromAddr, frame) }
            }.append(wrapped, 0, wrapped.size)
        }
    }

    /** Poll a node's inbox until a message from [fromNode] arrives, or time out. */
    private suspend fun awaitMessage(node: SimNode, fromNode: String, timeoutMs: Long = 8000L) =
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                node.inbox.firstOrNull { it.sourceId == fromNode }?.let { return@withTimeoutOrNull it }
                delay(50)
            }
            @Suppress("UNREACHABLE_CODE") null
        }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun message_hopsThroughIntermediateNode_AtoBtoC() = runBlocking {
        val net = SimNetwork()
        val a = net.addNode("A", "node-A")
        val b = net.addNode("B", "node-B")
        val c = net.addNode("C", "node-C")

        // Line topology: A can hear B, B can hear C, but A cannot hear C.
        net.link("A", "B")
        net.link("B", "C")

        // Pre-seeded routing tables (DVR convergence is covered by RoutingAlgorithmTest).
        a.routes["node-C"] = "B"; a.routes["node-B"] = "B"
        b.routes["node-A"] = "A"; b.routes["node-C"] = "C"
        c.routes["node-A"] = "B"; c.routes["node-B"] = "B"

        delay(200) // let inbox collectors subscribe

        val body = "hello across two hops"
        a.messaging.sendMessage("node-C", body)

        val delivered = awaitMessage(c, "node-A")
        assertNotNull("C should receive the message forwarded by B", delivered)
        assertEquals(body, delivered!!.body)
        assertEquals("node-A", delivered.sourceId)
        assertEquals("node-C", delivered.destinationId)

        // B only relays — it must never deliver the message to its own inbox.
        assertTrue("Intermediate node B must not deliver the message", b.inbox.isEmpty())
        // A must not receive its own message back (dedup / loop prevention).
        assertTrue("Source A must not receive its own message", a.inbox.isEmpty())

        net.shutdown()
    }

    @Test
    fun message_hopsThreeTimes_AtoBtoCtoD() = runBlocking {
        val net = SimNetwork()
        val a = net.addNode("A", "node-A")
        val b = net.addNode("B", "node-B")
        val c = net.addNode("C", "node-C")
        val d = net.addNode("D", "node-D")

        net.link("A", "B"); net.link("B", "C"); net.link("C", "D")

        a.routes["node-D"] = "B"
        b.routes["node-D"] = "C"; b.routes["node-A"] = "A"
        c.routes["node-D"] = "D"; c.routes["node-A"] = "B"
        d.routes["node-A"] = "C"

        delay(200)

        val body = "three hops away"
        a.messaging.sendMessage("node-D", body)

        val delivered = awaitMessage(d, "node-A")
        assertNotNull("D should receive the message after 3 hops", delivered)
        assertEquals(body, delivered!!.body)
        assertTrue(b.inbox.isEmpty())
        assertTrue(c.inbox.isEmpty())

        net.shutdown()
    }

    @Test
    fun message_stillDelivered_whenNoRouteKnown_viaFlooding() = runBlocking {
        val net = SimNetwork()
        val a = net.addNode("A", "node-A")
        net.addNode("B", "node-B")
        val c = net.addNode("C", "node-C")

        net.link("A", "B"); net.link("B", "C")

        // No routing entries at all: nodes must fall back to controlled flooding.
        delay(200)

        val body = "flooded to destination"
        a.messaging.sendMessage("node-C", body)

        val delivered = awaitMessage(c, "node-A")
        assertNotNull("C should still be reached by flooding when no route is known", delivered)
        assertEquals(body, delivered!!.body)

        net.shutdown()
    }
}
