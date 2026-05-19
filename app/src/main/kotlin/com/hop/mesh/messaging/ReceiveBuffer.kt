package com.hop.mesh.messaging

/**
 * Accumulates bytes and feeds complete length-prefixed frames to the protocol layer.
 */
class ReceiveBuffer(private val onFrame: (ByteArray) -> Unit) {
    private val buffer = ArrayDeque<Byte>()
    private var pendingLength = -1

    fun append(data: ByteArray, offset: Int, length: Int) {
        for (i in offset until (offset + length)) buffer.addLast(data[i])
        drain()
    }

    private fun drain() {
        while (buffer.size >= 4) {
            if (pendingLength < 0) {
                pendingLength = ((buffer[0].toInt() and 0xFF) shl 24) or
                    ((buffer[1].toInt() and 0xFF) shl 16) or
                    ((buffer[2].toInt() and 0xFF) shl 8) or
                    (buffer[3].toInt() and 0xFF)
                if (pendingLength <= 0 || pendingLength > 64 * 1024) {
                    buffer.removeFirst()
                    pendingLength = -1
                    continue
                }
            }
            if (buffer.size < 4 + pendingLength) break
            repeat(4) { buffer.removeFirst() }
            val frame = ByteArray(pendingLength) { buffer.removeFirst() }
            pendingLength = -1
            onFrame(frame)
        }
    }
}
