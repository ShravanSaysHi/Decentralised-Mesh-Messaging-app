package com.hop.mesh.routing

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RoutingDao {

    @Query("SELECT * FROM routing_table ORDER BY cost ASC, hopCount ASC")
    fun getAllEntries(): Flow<List<RoutingEntry>>

    @Query("SELECT * FROM routing_table ORDER BY cost ASC, hopCount ASC")
    suspend fun getAllEntriesSnapshot(): List<RoutingEntry>

    @Query("SELECT * FROM routing_table WHERE nodeId = :nodeId LIMIT 1")
    suspend fun getEntry(nodeId: String): RoutingEntry?

    @Query("SELECT nextHop FROM routing_table WHERE nodeId = :nodeId ORDER BY cost ASC, hopCount ASC LIMIT 1")
    suspend fun getNextHop(nodeId: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: RoutingEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<RoutingEntry>)

    @Delete
    suspend fun delete(entry: RoutingEntry)

    @Query("DELETE FROM routing_table WHERE nodeId = :nodeId")
    suspend fun deleteByNodeId(nodeId: String)

    @Query("DELETE FROM routing_table WHERE lastSeen < :beforeMs")
    suspend fun deleteStale(beforeMs: Long)

    @Query("UPDATE routing_table SET lastSeen = :now WHERE nodeId = :nodeId")
    suspend fun updateLastSeen(nodeId: String, now: Long)

    @Query("DELETE FROM routing_table WHERE nextHop = :nextHop AND hopCount > 0")
    suspend fun deleteByNextHop(nextHop: String)
}
