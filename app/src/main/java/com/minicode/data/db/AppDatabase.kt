package com.minicode.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.minicode.data.db.entities.ConnectionProfileEntity

@Database(
    entities = [ConnectionProfileEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun connectionProfileDao(): ConnectionProfileDao
}
