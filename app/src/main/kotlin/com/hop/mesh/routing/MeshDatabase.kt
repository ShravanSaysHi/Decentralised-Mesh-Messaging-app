package com.hop.mesh.routing

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.hop.mesh.encryption.SessionKeyDao
import com.hop.mesh.encryption.SessionKeyEntry

@Database(entities = [RoutingEntry::class, SessionKeyEntry::class], version = 4, exportSchema = false)
abstract class MeshDatabase : RoomDatabase() {
    abstract fun routingDao(): RoutingDao
    abstract fun sessionKeyDao(): SessionKeyDao

    companion object {
        private const val DB_NAME = "hop_mesh.db"

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE routing_table ADD COLUMN sequenceNumber INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE routing_table ADD COLUMN deviceName TEXT NOT NULL DEFAULT 'Unknown'")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `session_keys` (`nodeId` TEXT NOT NULL, `sessionKey` BLOB NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`nodeId`))")
            }
        }

        @Volatile
        private var instance: MeshDatabase? = null

        fun getInstance(context: Context): MeshDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MeshDatabase::class.java,
                    DB_NAME
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
            }
        }
    }
}
