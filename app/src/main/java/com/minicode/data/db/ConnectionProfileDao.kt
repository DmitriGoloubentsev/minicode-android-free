package com.minicode.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.minicode.data.db.entities.ConnectionProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConnectionProfileDao {
    @Query("SELECT * FROM connection_profiles ORDER BY sortOrder ASC, label ASC")
    fun getAllProfiles(): Flow<List<ConnectionProfileEntity>>

    @Query("SELECT * FROM connection_profiles WHERE id = :id")
    suspend fun getProfileById(id: String): ConnectionProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: ConnectionProfileEntity)

    @Update
    suspend fun updateProfile(profile: ConnectionProfileEntity)

    @Delete
    suspend fun deleteProfile(profile: ConnectionProfileEntity)

    @Query("DELETE FROM connection_profiles WHERE id = :id")
    suspend fun deleteProfileById(id: String)

    @Query("UPDATE connection_profiles SET lastUsedAt = :timestamp WHERE id = :id")
    suspend fun updateLastUsed(id: String, timestamp: String)
}
