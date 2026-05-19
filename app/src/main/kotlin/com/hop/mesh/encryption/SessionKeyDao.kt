package com.hop.mesh.encryption

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SessionKeyDao {
    @Query("SELECT * FROM session_keys WHERE nodeId = :nodeId")
    suspend fun getKey(nodeId: String): SessionKeyEntry?

    @Query("SELECT * FROM session_keys")
    suspend fun getAllKeys(): List<SessionKeyEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: SessionKeyEntry)

    @Query("DELETE FROM session_keys WHERE nodeId = :nodeId")
    suspend fun delete(nodeId: String)
}
