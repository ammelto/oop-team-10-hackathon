package com.example.oop.wearables.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "vital_samples",
    indices = [
        Index(value = ["timestamp_epoch_ms"]),
        Index(value = ["type", "timestamp_epoch_ms"]),
        Index(value = ["source"]),
    ],
)
data class VitalEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "timestamp_epoch_ms") val timestampEpochMs: Long,
    val type: String,
    val value: Double,
    val unit: String,
    val source: String,
    val device: String?,
    @ColumnInfo(name = "provider_record_id") val providerRecordId: String?,
    @ColumnInfo(name = "metadata_json") val metadataJson: String,
)
