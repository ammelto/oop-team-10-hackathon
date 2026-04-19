package com.example.oop.wearables.source

import android.content.Context
import com.example.oop.wearables.model.SourceStatus
import com.example.oop.wearables.model.VitalSample
import com.example.oop.wearables.model.WearableSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface WearableDataSource {

    val id: WearableSource

    val status: StateFlow<SourceStatus>

    suspend fun initialize(context: Context)

    fun vitals(): Flow<VitalSample>

    suspend fun close()
}
