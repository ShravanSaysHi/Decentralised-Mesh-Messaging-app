package com.hop.mesh.messaging

import com.hop.mesh.routing.RoutingEntry
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Binary wire format over Bluetooth.
 * Frame: [1 byte type][4 byte payload length][payload]
 * Type: 0=ROUTING, 1=MESSAGE_FULL, 2=BLOCK, 3=ACK, 4=MESSAGE_HEADER (fragmented)
 */
object WireProtocol {

    const val TYPE_ROUTING = 0
    const val TYPE_MESSAGE_FULL = 1
    const val TYPE_BLOCK = 2
    const val TYPE_ACK = 3
    const val TYPE_MESSAGE_HEADER = 4
    const val TYPE_IDENTITY = 5
    const val TYPE_KEY_EXCHANGE = 7

    private const val ID_LEN = 36
    private const val HEADER_LEN = 1 + 4

    fun encodeRouting(entries: List<RoutingEntry>): ByteArray {
        val arr = JSONArray()
        for (e in entries) {
            val o = JSONObject()
            o.put("nodeId", e.nodeId)
            o.put("deviceName", e.deviceName)
            o.put("nextHop", e.nextHop)
            o.put("cost", e.cost)
            o.put("lastSeen", e.lastSeen)
            o.put("hopCount", e.hopCount)
            o.put("sequenceNumber", e.sequenceNumber)
            arr.put(o)
        }
        val payload = arr.toString().toByteArray(StandardCharsets.UTF_8)
        return ByteBuffer.allocate(HEADER_LEN + payload.size)
            .put(TYPE_ROUTING.toByte())
            .putInt(payload.size)
            .put(payload)
            .array()
    }

    fun decodeRouting(data: ByteArray): List<RoutingEntry>? {
        if (data.size < HEADER_LEN || data[0].toInt() and 0xFF != TYPE_ROUTING) return null
        val len = ByteBuffer.wrap(data, 1, 4).getInt()
        if (data.size < HEADER_LEN + len) return null
        val json = String(data, HEADER_LEN, len, StandardCharsets.UTF_8)
        val arr = JSONArray(json)
        val list = mutableListOf<RoutingEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                RoutingEntry(
                    nodeId = o.getString("nodeId"),
                    deviceName = o.optString("deviceName", "Unknown"),
                    nextHop = o.getString("nextHop"),
                    cost = o.getInt("cost"),
                    lastSeen = o.getLong("lastSeen"),
                    hopCount = o.getInt("hopCount"),
                    sequenceNumber = o.optLong("sequenceNumber", 0L)
                )
            )
        }
        return list
    }

    fun encodeMessageFull(packet: MessagePacket): ByteArray {
        val p = packet.payloadCiphertext
        val buf = ByteBuffer.allocate(HEADER_LEN + ID_LEN * 3 + 1 + 8 + 4 + p.size + 4)
        buf.put(TYPE_MESSAGE_FULL.toByte())
        val payloadStart = HEADER_LEN + 4
        val bodyLen = ID_LEN * 3 + 1 + 8 + 4 + p.size + 4
        buf.putInt(bodyLen)
        putId(buf, packet.messageId)
        putId(buf, packet.sourceId)
        putId(buf, packet.destinationId)
        buf.put(packet.ttl.toByte())
        buf.putLong(packet.sequenceNumber)
        buf.putInt(p.size)
        buf.put(p)
        buf.putInt(packet.checksum)
        return buf.array()
    }

    private fun putId(buf: ByteBuffer, id: String) {
        val b = id.padEnd(ID_LEN).take(ID_LEN).toByteArray(StandardCharsets.UTF_8)
        buf.put(b, 0, minOf(b.size, ID_LEN))
        if (b.size < ID_LEN) buf.put(ByteArray(ID_LEN - b.size))
    }

    private fun getId(buf: ByteBuffer): String {
        val b = ByteArray(ID_LEN)
        buf.get(b)
        return String(b, StandardCharsets.UTF_8).trim()
    }

    fun decodeMessageFull(data: ByteArray): MessagePacket? {
        if (data.size < HEADER_LEN + 4) return null
        val type = data[0].toInt() and 0xFF
        if (type != TYPE_MESSAGE_FULL) return null
        val len = ByteBuffer.wrap(data, 1, 4).getInt()
        if (data.size < HEADER_LEN + len) return null
        val buf = ByteBuffer.wrap(data, HEADER_LEN, len)
        val messageId = getId(buf)
        val sourceId = getId(buf)
        val destinationId = getId(buf)
        val ttl = buf.get().toInt() and 0xFF
        val seq = buf.getLong()
        val plen = buf.getInt()
        if (buf.remaining() < plen + 4) return null
        val payload = ByteArray(plen).also { buf.get(it) }
        val checksum = buf.getInt()
        return MessagePacket(messageId, sourceId, destinationId, ttl, seq, payload, checksum)
    }

    fun encodeBlock(block: MessageBlock): ByteArray {
        val p = block.payload
        val bodyLen = ID_LEN + 4 + 4 + 4 + p.size + 4
        val buf = ByteBuffer.allocate(HEADER_LEN + bodyLen)
        buf.put(TYPE_BLOCK.toByte())
        buf.putInt(bodyLen)
        putId(buf, block.messageId)
        buf.putInt(block.blockId)
        buf.putInt(block.totalBlocks)
        buf.putInt(p.size)
        buf.put(p)
        buf.putInt(block.checksum)
        return buf.array()
    }

    fun decodeBlock(data: ByteArray): MessageBlock? {
        if (data.size < HEADER_LEN + 4) return null
        if ((data[0].toInt() and 0xFF) != TYPE_BLOCK) return null
        val len = ByteBuffer.wrap(data, 1, 4).getInt()
        if (data.size < HEADER_LEN + len) return null
        val buf = ByteBuffer.wrap(data, HEADER_LEN, len)
        val messageId = getId(buf)
        val blockId = buf.getInt()
        val totalBlocks = buf.getInt()
        val plen = buf.getInt()
        if (buf.remaining() < plen + 4) return null
        val payload = ByteArray(plen).also { buf.get(it) }
        val checksum = buf.getInt()
        return MessageBlock(messageId, blockId, totalBlocks, payload, checksum)
    }

    fun encodeAck(messageId: String, blockId: Int): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_LEN + ID_LEN + 4)
        buf.put(TYPE_ACK.toByte())
        buf.putInt(ID_LEN + 4)
        putId(buf, messageId)
        buf.putInt(blockId)
        return buf.array()
    }

    fun decodeAck(data: ByteArray): Pair<String, Int>? {
        if (data.size < HEADER_LEN + ID_LEN + 4) return null
        if ((data[0].toInt() and 0xFF) != TYPE_ACK) return null
        val buf = ByteBuffer.wrap(data, HEADER_LEN, data.size - HEADER_LEN)
        val messageId = getId(buf)
        val blockId = buf.getInt()
        return messageId to blockId
    }

    fun encodeMessageHeader(messageId: String, sourceId: String, destId: String, ttl: Int, seq: Long, checksum: Int): ByteArray {
        val bodyLen = ID_LEN * 3 + 1 + 8 + 4
        val buf = ByteBuffer.allocate(HEADER_LEN + bodyLen)
        buf.put(TYPE_MESSAGE_HEADER.toByte())
        buf.putInt(bodyLen)
        putId(buf, messageId)
        putId(buf, sourceId)
        putId(buf, destId)
        buf.put(ttl.toByte())
        buf.putLong(seq)
        buf.putInt(checksum)
        return buf.array()
    }

    fun decodeMessageHeader(data: ByteArray): MessagePacketHeaderData? {
        if (data.size < HEADER_LEN + 4) return null
        if ((data[0].toInt() and 0xFF) != TYPE_MESSAGE_HEADER) return null
        val len = ByteBuffer.wrap(data, 1, 4).getInt()
        if (data.size < HEADER_LEN + len) return null
        val buf = ByteBuffer.wrap(data, HEADER_LEN, len)
        return MessagePacketHeaderData(
            getId(buf), getId(buf), getId(buf),
            buf.get().toInt() and 0xFF, buf.getLong(), buf.getInt()
        )
    }

    data class MessagePacketHeaderData(val messageId: String, val sourceId: String, val destinationId: String, val ttl: Int, val sequenceNumber: Long, val checksum: Int)

    fun encodeIdentity(nodeId: String, deviceName: String): ByteArray {
        val o = JSONObject()
        o.put("nodeId", nodeId)
        o.put("deviceName", deviceName)
        val payload = o.toString().toByteArray(StandardCharsets.UTF_8)
        return ByteBuffer.allocate(HEADER_LEN + payload.size)
            .put(TYPE_IDENTITY.toByte())
            .putInt(payload.size)
            .put(payload)
            .array()
    }

    fun decodeIdentity(data: ByteArray): Pair<String, String>? {
        if (data.size < HEADER_LEN || (data[0].toInt() and 0xFF) != TYPE_IDENTITY) return null
        val len = ByteBuffer.wrap(data, 1, 4).getInt()
        if (data.size < HEADER_LEN + len) return null
        val json = String(data, HEADER_LEN, len, StandardCharsets.UTF_8)
        val o = JSONObject(json)
        return o.getString("nodeId") to o.optString("deviceName", "Unknown")
    }

    /**
     * Key Exchange: [TYPE_KEY_EXCHANGE][PayloadLen][SourceNodeId][DestNodeId][PublicKeyBytes]
     * Pulse packet for E2EE handshake.
     * PublicKey for X25519 is exactly 32 bytes.
     */
    fun encodeKeyExchange(sourceNodeId: String, destNodeId: String, publicKey: ByteArray): ByteArray {
        val payloadLen = ID_LEN * 2 + publicKey.size
        val buf = ByteBuffer.allocate(HEADER_LEN + payloadLen)
        buf.put(TYPE_KEY_EXCHANGE.toByte())
        buf.putInt(payloadLen)
        putId(buf, sourceNodeId)
        putId(buf, destNodeId)
        buf.put(publicKey)
        return buf.array()
    }

    /**
     * @return Triple(SourceId, DestId, PublicKey)
     */
    fun decodeKeyExchange(data: ByteArray): Triple<String, String, ByteArray>? {
        if (data.size < HEADER_LEN || (data[0].toInt() and 0xFF) != TYPE_KEY_EXCHANGE) return null
        val len = ByteBuffer.wrap(data, 1, 4).getInt()
        if (data.size < HEADER_LEN + len) return null
        val buf = ByteBuffer.wrap(data, HEADER_LEN, len)
        val sourceId = getId(buf)
        val destId = getId(buf)
        val pubKeySize = len - (ID_LEN * 2)
        if (pubKeySize < 0) return null
        val pubKey = ByteArray(pubKeySize)
        buf.get(pubKey)
        return Triple(sourceId, destId, pubKey)
    }

    fun frameType(data: ByteArray): Int = if (data.isNotEmpty()) data[0].toInt() and 0xFF else -1
}
