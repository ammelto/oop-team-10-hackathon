package com.example.oop.wearables.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface VitalDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(samples: List<VitalEntity>)

    @Query(
        """
        SELECT * FROM vital_samples
        WHERE timestamp_epoch_ms BETWEEN :fromMs AND :toMs
          AND type IN (:typeCodes)
        ORDER BY timestamp_epoch_ms ASC
        """
    )
    suspend fun window(fromMs: Long, toMs: Long, typeCodes: List<String>): List<VitalEntity>

    @Query(
        """
        SELECT * FROM vital_samples
        WHERE type = :typeCode
        ORDER BY timestamp_epoch_ms DESC
        LIMIT 1
        """
    )
    suspend fun latest(typeCode: String): VitalEntity?

    @Query("SELECT COUNT(*) FROM vital_samples")
    suspend fun count(): Long

    @Query("DELETE FROM vital_samples WHERE timestamp_epoch_ms < :beforeMs")
    suspend fun pruneOlderThan(beforeMs: Long): Int
}
