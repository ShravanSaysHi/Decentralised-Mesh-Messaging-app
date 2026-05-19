package com.hop.mesh.messaging

import java.util.UUID

/**
 * Phase 4: Application-level message packet.
 * MessageID | SourceID | DestinationID | TTL | SequenceNumber | EncryptedPayload | Checksum
 */
data class MessagePacket(
    val messageId: String,
    val sourceId: String,
    val destinationId: String,
    val ttl: Int,
    val sequenceNumber: Long,
    val payloadCiphertext: ByteArray,
    val checksum: Int
) {
    override fun equals(other: Any?) = (other is MessagePacket) &&
        messageId == other.messageId && sourceId == other.sourceId &&
        destinationId == other.destinationId && ttl == other.ttl &&
        sequenceNumber == other.sequenceNumber &&
        payloadCiphertext.contentEquals(other.payloadCiphertext) &&
        checksum == other.checksum
    override fun hashCode(): Int {
        var result = messageId.hashCode()
        result = 31 * result + sourceId.hashCode()
        result = 31 * result + destinationId.hashCode()
        result = 31 * result + ttl
        result = 31 * result + sequenceNumber.hashCode()
        result = 31 * result + payloadCiphertext.contentHashCode()
        result = 31 * result + checksum
        return result
    }

    companion object {
        const val MAX_TTL = 15
        const val DEFAULT_TTL = 10
    }
}

/**
 * Phase 6: Single block for fragmentation.
 * BlockID | MessageID | TotalBlocks | Payload
 */
data class MessageBlock(
    val messageId: String,
    val blockId: Int,
    val totalBlocks: Int,
    val payload: ByteArray,
    val checksum: Int
) {
    override fun equals(other: Any?) = (other is MessageBlock) &&
        messageId == other.messageId && blockId == other.blockId &&
        totalBlocks == other.totalBlocks && payload.contentEquals(other.payload) &&
        checksum == other.checksum
    override fun hashCode(): Int {
        var result = messageId.hashCode()
        result = 31 * result + blockId
        result = 31 * result + totalBlocks
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + checksum
        return result
    }
}

fun crc32(bytes: ByteArray): Int {
    var crc = 0xFFFFFFFF.toInt()
    for (b in bytes) {
        crc = crc xor (b.toInt() and 0xFF)
        repeat(8) {
            crc = if ((crc and 1) != 0) (crc ushr 1) xor 0xEDB88320.toInt() else crc ushr 1
        }
    }
    return crc xor 0xFFFFFFFF.toInt()
}
