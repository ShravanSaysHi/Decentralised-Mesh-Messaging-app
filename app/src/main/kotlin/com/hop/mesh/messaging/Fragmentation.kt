package com.hop.mesh.messaging

/**
 * Phase 6: Split payload into fixed-size blocks; reassemble.
 */
object Fragmentation {
    const val BLOCK_PAYLOAD_SIZE = 512

    fun split(messageId: String, payload: ByteArray): List<MessageBlock> {
        val list = mutableListOf<MessageBlock>()
        var offset = 0
        var blockId = 0
        val totalBlocks = (payload.size + BLOCK_PAYLOAD_SIZE - 1) / BLOCK_PAYLOAD_SIZE
        while (offset < payload.size) {
            val chunk = payload.copyOfRange(offset, minOf(offset + BLOCK_PAYLOAD_SIZE, payload.size))
            val checksum = crc32(chunk)
            list.add(MessageBlock(messageId, blockId++, totalBlocks, chunk, checksum))
            offset += BLOCK_PAYLOAD_SIZE
        }
        return list
    }

    /**
     * Reassemble blocks by messageId. Returns null until all blocks received and checksums valid.
     */
    class Reassembler {
        private val blocks = mutableMapOf<String, MutableMap<Int, MessageBlock>>()

        fun addBlock(block: MessageBlock): ByteArray? {
            val byId = blocks.getOrPut(block.messageId) { mutableMapOf() }
            if (block.blockId in byId) return null
            if (crc32(block.payload) != block.checksum) return null
            byId[block.blockId] = block
            val total = block.totalBlocks
            if (byId.size != total) return null
            return (0 until total).mapNotNull { byId[it]?.payload }
                .reduce { a, b -> a + b }
                .also { blocks.remove(block.messageId) }
        }
    }
}
