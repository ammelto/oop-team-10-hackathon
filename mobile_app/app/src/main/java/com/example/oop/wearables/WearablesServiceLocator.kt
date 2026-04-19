package com.example.oop.wearables

import android.content.Context
import com.example.oop.wearables.data.WearableDatabase
import com.example.oop.wearables.data.WearableRepository
import com.example.oop.wearables.data.WearableRepositoryImpl
import com.example.oop.wearables.model.WearableSource
import com.example.oop.wearables.source.HealthConnectSource
import com.example.oop.wearables.source.MockSource
import com.example.oop.wearables.source.SamsungHealthSource
import com.example.oop.wearables.source.WearableDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

object WearablesServiceLocator {

    @Volatile
    private var cachedImpl: WearableRepositoryImpl? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun repository(context: Context): WearableRepository = repositoryImpl(context)

    internal fun repositoryImpl(context: Context): WearableRepositoryImpl {
        val existing = cachedImpl
        if (existing != null) return existing
        return synchronized(this) {
            cachedImpl ?: build(context.applicationContext).also { cachedImpl = it }
        }
    }

    private fun build(appContext: Context): WearableRepositoryImpl {
        val database = WearableDatabase.get(appContext)
        val healthConnect = HealthConnectSource(appContext)
        val samsung = SamsungHealthSource()
        val mock = MockSource()
        val sources: List<WearableDataSource> = listOf(healthConnect, samsung, mock)
        val initialEnabled = setOf(WearableSource.HEALTH_CONNECT)
        return WearableRepositoryImpl(
            appContext = appContext,
            scope = scope,
            database = database,
            sources = sources,
            initialEnabled = initialEnabled,
            healthConnectPermissionsProvider = { healthConnect.missingPermissions() },
        )
    }
}
