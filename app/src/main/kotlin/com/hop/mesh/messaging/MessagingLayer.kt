package com.hop.mesh.messaging

import com.hop.mesh.bluetooth.ConnectionManager
import com.hop.mesh.encryption.MessageCrypto
import com.hop.mesh.encryption.SessionKeyDao
import com.hop.mesh.encryption.SessionKeyEntry
import com.hop.mesh.encryption.X25519Manager
import com.hop.mesh.routing.RoutingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Multi-hop messaging layer with route-based forwarding.
 *
 * - Receives frames from any peer via [receiveFrame].
 * - Sends messages by looking up the routing table for the next-hop peer.
 * - Falls back to broadcasting if no route is known.
 * - Forwards messages not addressed to us through the correct next-hop.
 * - Deduplicates forwarded messages to prevent routing loops.
 */
class MessagingLayer(
    private val localNodeId: String,
    private val routingRepository: RoutingRepository,
    private val connectionManager: ConnectionManager,
    private val crypto: MessageCrypto,
    private val sessionKeyDao: SessionKeyDao,
    private val scope: CoroutineScope
) {

    private val _inbox = MutableSharedFlow<InboxMessage>(replay = 0, extraBufferCapacity = 128)
    val inbox: SharedFlow<InboxMessage> = _inbox.asSharedFlow()

    /** Callback for when a peer's persistent Identity (UUID + Name) is received. */
    var onIdentityReceived: ((fromPeerAddr: String, nodeId: String, name: String) -> Unit)? = null

    private val reassembler = Fragmentation.Reassembler()
    private val pendingHeaders = ConcurrentHashMap<String, WireProtocol.MessagePacketHeaderData>()
    private val sequenceNumber = AtomicLong(0L)

    /** Track seen message IDs to prevent forwarding loops. Max 500 entries. */
    private val seenMessages = LinkedHashSet<String>()
    private val maxSeenMessages = 500

    /** Pairwise E2EE session keys: RemoteNodeId -> SymmetricKey (derived secret). */
    private val sessionKeys = ConcurrentHashMap<String, ByteArray>()
    
    /** Local identity key pair for X25519 handshake. */
    private var identityKeyPair: AsymmetricCipherKeyPair? = null

    fun setIdentityKeyPair(pair: AsymmetricCipherKeyPair) {
        this.identityKeyPair = pair
    }

    init {
        // Load persistent session keys
        scope.launch(Dispatchers.IO) {
            try {
                val stored = sessionKeyDao.getAllKeys()
                for (entry in stored) {
                    sessionKeys[entry.nodeId] = entry.sessionKey
                }
                println("MessagingLayer: Loaded ${stored.size} persistent session keys")
            } catch (e: Exception) {
                println("MessagingLayer: Failed to load session keys: ${e.message}")
            }
        }
    }

    // ── Frame I/O ────────────────────────────────────────────────────────────

    /** Length-prefixed frame: 4 bytes (big-endian length) + N bytes payload. */
    private fun wrapFrame(frame: ByteArray): ByteArray {
        val len = frame.size
        val out = ByteArray(4 + len)
        out[0] = (len shr 24).toByte()
        out[1] = (len shr 16).toByte()
        out[2] = (len shr 8).toByte()
        out[3] = len.toByte()
        System.arraycopy(frame, 0, out, 4, len)
        return out
    }

    /** Send a frame to a specific peer (length-prefixed). */
    private fun sendFrameToPeer(address: String, frame: ByteArray): Boolean {
        return connectionManager.sendToPeer(address, wrapFrame(frame))
    }

    /** Broadcast a frame to all peers (length-prefixed). */
    private fun broadcastFrame(frame: ByteArray): Int {
        return connectionManager.broadcastToAll(wrapFrame(frame))
    }

    /** Broadcast a frame to all peers except one (for forwarding). */
    private fun broadcastFrameExcept(excludeAddress: String, frame: ByteArray): Int {
        return connectionManager.broadcastExcept(excludeAddress, wrapFrame(frame))
    }

    // ── Receive ──────────────────────────────────────────────────────────────

    /** Called by ConnectionManager when a complete frame arrives from a peer. */
    fun receiveFrame(fromPeer: String, frame: ByteArray) {
        handleFrame(fromPeer, frame)
    }

    private fun handleFrame(fromPeer: String, frame: ByteArray) {
        when (WireProtocol.frameType(frame)) {
            WireProtocol.TYPE_ROUTING -> {
                val entries = WireProtocol.decodeRouting(frame) ?: return
                routingRepository.applyUpdatesFromNeighborAsync(fromPeer, entries)
            }
            WireProtocol.TYPE_MESSAGE_FULL -> {
                val packet = WireProtocol.decodeMessageFull(frame) ?: return
                handlePacket(fromPeer, packet)
            }
            WireProtocol.TYPE_MESSAGE_HEADER -> {
                val h = WireProtocol.decodeMessageHeader(frame) ?: return
                pendingHeaders[h.messageId] = h
            }
            WireProtocol.TYPE_BLOCK -> {
                val block = WireProtocol.decodeBlock(frame) ?: return
                // Send ACK back to the sender
                sendFrameToPeer(fromPeer, WireProtocol.encodeAck(block.messageId, block.blockId))
                val assembled = reassembler.addBlock(block)
                if (assembled != null) {
                    val header = pendingHeaders.remove(block.messageId) ?: return
                    val packet = MessagePacket(
                        header.messageId, header.sourceId, header.destinationId,
                        header.ttl, header.sequenceNumber, assembled, header.checksum
                    )
                    handlePacket(fromPeer, packet)
                }
            }
            WireProtocol.TYPE_ACK -> {
                val ack = WireProtocol.decodeAck(frame) ?: return
                onAckReceived(ack.first, ack.second)
            }
            WireProtocol.TYPE_IDENTITY -> {
                val id = WireProtocol.decodeIdentity(frame) ?: return
                onIdentityReceived?.invoke(fromPeer, id.first, id.second)
            }
            WireProtocol.TYPE_KEY_EXCHANGE -> {
                val triple = WireProtocol.decodeKeyExchange(frame) ?: return
                handleKeyExchange(fromPeer, triple.first, triple.second, triple.third)
            }
        }
    }

    private fun handleKeyExchange(fromPeer: String, remoteId: String, destId: String, remotePubKeyBytes: ByteArray) {
        if (destId != localNodeId) {
            // Forward if not for us
            scope.launch {
                val nextHop = routingRepository.getNextHop(destId)
                if (nextHop != null && connectionManager.isConnectedTo(nextHop)) {
                    sendFrameToPeer(nextHop, WireProtocol.encodeKeyExchange(remoteId, destId, remotePubKeyBytes))
                } else {
                    broadcastFrameExcept(fromPeer, WireProtocol.encodeKeyExchange(remoteId, destId, remotePubKeyBytes))
                }
            }
            return
        }

        val myPair = identityKeyPair ?: return
        val myRawPubKey = X25519Manager.getRawPublicKey(myPair)
        
        // 1. If we haven't established a key yet (or just received one), reply with our own public key
        // We reply to the sourceId (remoteId)
        if (!sessionKeys.containsKey(remoteId)) {
            triggerHandshake(remoteId)
        }

        // 2. Derive shared secret
        try {
            val remotePubKey = X25519Manager.publicKeyFromBytes(remotePubKeyBytes)
            val myPrivate = X25519Manager.privateKeyFromBytes(X25519Manager.getRawPrivateKey(myPair))
            val sharedSecret = X25519Manager.calculateSharedSecret(myPrivate, remotePubKey)
            sessionKeys[remoteId] = sharedSecret
            println("MessagingLayer: Established session key with $remoteId")
            
            // Persist the key
            scope.launch(Dispatchers.IO) {
                sessionKeyDao.insert(SessionKeyEntry(remoteId, sharedSecret))
            }
        } catch (e: Exception) {
            println("MessagingLayer: Key derivation failed for $remoteId: ${e.message}")
        }
    }

    private fun handlePacket(fromPeer: String, packet: MessagePacket) {
        // Deduplicate
        synchronized(seenMessages) {
            if (packet.messageId in seenMessages) return
            seenMessages.add(packet.messageId)
            if (seenMessages.size > maxSeenMessages) {
                seenMessages.remove(seenMessages.first())
            }
        }

        if (packet.destinationId == localNodeId || packet.destinationId == "BROADCAST") {
            deliver(packet)
        }
        // Forward if not for us (or broadcast) and TTL allows
        if (packet.destinationId != localNodeId && packet.ttl > 1) {
            forwardMessage(fromPeer, packet)
        }
    }

    // ── Delivery ────────────────────────────────────────────────────────────

    private fun deliver(packet: MessagePacket) {
        try {
            // FIX: For broadcast messages, ALWAYS use the global PSK (sessionKey = null)
            val sessionKey = if (packet.destinationId == "BROADCAST") null else sessionKeys[packet.sourceId]
            
            val plain = crypto.decrypt(packet.payloadCiphertext, sessionKey)
            if (crc32(plain) != packet.checksum) {
                println("MessagingLayer: checksum failed for message ${packet.messageId} (Wrong key?)")
                return
            }
            val text = String(plain, Charsets.UTF_8)
            _inbox.tryEmit(InboxMessage(packet.messageId, packet.sourceId, packet.destinationId, text, System.currentTimeMillis()))
        } catch (e: Exception) {
            println("MessagingLayer: deliver failed: ${e.message}")
        }
    }

    // ── Forward to next hop ─────────────────────────────────────────────────

    private fun forwardMessage(fromPeer: String, packet: MessagePacket) {
        val fwd = packet.copy(ttl = packet.ttl - 1)
        val frame = WireProtocol.encodeMessageFull(fwd)

        scope.launch {
            val nextHop = routingRepository.getNextHop(packet.destinationId)
            if (nextHop != null && connectionManager.isConnectedTo(nextHop)) {
                // Route through known next-hop
                sendFrameToPeer(nextHop, frame)
            } else {
                // No route — flood to all peers except the one that sent it
                broadcastFrameExcept(fromPeer, frame)
            }
        }
    }

    // ── ACK tracking ────────────────────────────────────────────────────────

    private val pendingAcks = ConcurrentHashMap<Pair<String, Int>, () -> Unit>()
    private fun onAckReceived(messageId: String, blockId: Int) {
        pendingAcks.remove(messageId to blockId)?.invoke()
    }

    // ── Send message ────────────────────────────────────────────────────────

    /** Send plaintext to a destination node. Encrypts, routes, optionally fragments. */
    fun sendMessage(destinationId: String, plaintext: String): Boolean {
        // For BROADCAST, we use the global PSK (null custom key in crypto)
        val sessionKey = if (destinationId == "BROADCAST") null else sessionKeys[destinationId]
        
        // If we need a pairwise key but don't have it, trigger handshake and retry once
        if (destinationId != "BROADCAST" && sessionKey == null) {
            triggerHandshake(destinationId)
            // Simple approach: launch a delayed retry
            scope.launch {
                delay(1000) // Wait for handshake
                sendMessage(destinationId, plaintext)
            }
            return true
        }

        val messageId = java.util.UUID.randomUUID().toString()
        val plain = plaintext.toByteArray(Charsets.UTF_8)
        val encrypted = crypto.encrypt(plain, sessionKey)
        val checksum = crc32(plain)
        val seq = sequenceNumber.incrementAndGet()
        val ttl = MessagePacket.DEFAULT_TTL
        val packet = MessagePacket(messageId, localNodeId, destinationId, ttl, seq, encrypted, checksum)

        // Mark as seen to prevent bounce-back
        synchronized(seenMessages) {
            seenMessages.add(messageId)
            if (seenMessages.size > maxSeenMessages) seenMessages.remove(seenMessages.first())
        }

        val frame = if (encrypted.size <= Fragmentation.BLOCK_PAYLOAD_SIZE * 4) {
            WireProtocol.encodeMessageFull(packet)
        } else {
            return sendFragmented(packet, sessionKey)
        }

        // Try route-based send, fall back to broadcast
        scope.launch {
            val nextHop = routingRepository.getNextHop(destinationId)
            if (nextHop != null && connectionManager.isConnectedTo(nextHop)) {
                sendFrameToPeer(nextHop, frame)
            } else {
                // No route or broadcast — flood to all peers
                broadcastFrame(frame)
            }
        }
        // For non-blocking send, always return true if we have peers
        return connectionManager.peerCount() > 0
    }

    private fun triggerHandshake(destinationId: String) {
        val myPair = identityKeyPair ?: return
        val myPubKey = X25519Manager.getRawPublicKey(myPair)
        val frame = WireProtocol.encodeKeyExchange(localNodeId, destinationId, myPubKey)
        
        scope.launch {
            val nextHop = routingRepository.getNextHop(destinationId)
            if (nextHop != null && connectionManager.isConnectedTo(nextHop)) {
                sendFrameToPeer(nextHop, frame)
            } else {
                broadcastFrame(frame)
            }
        }
    }

    private fun sendFragmented(packet: MessagePacket, customKey: ByteArray?): Boolean {
        val headerFrame = WireProtocol.encodeMessageHeader(
            packet.messageId, packet.sourceId, packet.destinationId,
            packet.ttl, packet.sequenceNumber, packet.checksum
        )
        val blocks = Fragmentation.split(packet.messageId, packet.payloadCiphertext)

        scope.launch {
            val nextHop = routingRepository.getNextHop(packet.destinationId)
            if (nextHop != null && connectionManager.isConnectedTo(nextHop)) {
                sendFrameToPeer(nextHop, headerFrame)
                for (block in blocks) sendFrameToPeer(nextHop, WireProtocol.encodeBlock(block))
            } else {
                broadcastFrame(headerFrame)
                for (block in blocks) broadcastFrame(WireProtocol.encodeBlock(block))
            }
        }
        return connectionManager.peerCount() > 0
    }

    // ── Routing updates ─────────────────────────────────────────────────────

    /** Broadcast routing table to all connected peers. */
    fun broadcastRoutingUpdate(entries: List<com.hop.mesh.routing.RoutingEntry>): Boolean {
        val frame = WireProtocol.encodeRouting(entries)
        return broadcastFrame(frame) > 0
    }

    // ── Data classes ────────────────────────────────────────────────────────

    data class InboxMessage(
        val messageId: String,
        val sourceId: String,
        val destinationId: String,
        val body: String,
        val receivedAt: Long
    )

    private fun crc32(data: ByteArray): Int {
        val crc = java.util.zip.CRC32()
        crc.update(data)
        return crc.value.toInt()
    }
}
