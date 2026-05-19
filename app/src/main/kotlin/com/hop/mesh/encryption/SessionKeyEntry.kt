package com.hop.mesh.encryption

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persist derived pairwise session keys so E2EE survives app restarts.
 */
@Entity(tableName = "session_keys")
data class SessionKeyEntry(
    @PrimaryKey val nodeId: String,
    val sessionKey: ByteArray,
    val createdAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SessionKeyEntry
        if (nodeId != other.nodeId) return false
        if (!sessionKey.contentEquals(other.sessionKey)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = nodeId.hashCode()
        result = 31 * result + sessionKey.contentHashCode()
        return result
    }
}
