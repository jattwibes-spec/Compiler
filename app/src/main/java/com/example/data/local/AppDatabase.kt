package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ProjectEntity::class, BuildJobEntity::class, FileBackupEntity::class],
    version = 1,
    exportSchema = false
)
abstract class WibesDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun buildJobDao(): BuildJobDao
    abstract fun fileBackupDao(): FileBackupDao
}

object DatabaseProvider {
    @Volatile
    private var INSTANCE: WibesDatabase? = null

    fun getDatabase(context: Context): WibesDatabase {
        return INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                WibesDatabase::class.java,
                "wibes_compiler_ai.db"
            ).build().also { INSTANCE = it }
        }
    }
}
