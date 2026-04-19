package com.example.oop.wearables.source

import android.content.Context
import com.example.oop.wearables.model.SourceStatus
import com.example.oop.wearables.model.VitalSample
import com.example.oop.wearables.model.WearableSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

class SamsungHealthSource : WearableDataSource {

    private val _status = MutableStateFlow<SourceStatus>(SourceStatus.Uninitialized)

    override val id: WearableSource = WearableSource.SAMSUNG_HEALTH

    override val status: StateFlow<SourceStatus> = _status.asStateFlow()

    override suspend fun initialize(context: Context) {
        _status.value = SourceStatus.Unsupported
    }

    override fun vitals(): Flow<VitalSample> = emptyFlow()

    override suspend fun close() {
        _status.value = SourceStatus.Uninitialized
    }
}
