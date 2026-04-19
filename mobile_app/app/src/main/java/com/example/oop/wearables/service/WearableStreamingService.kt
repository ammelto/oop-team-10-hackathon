package com.example.oop.wearables.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.oop.wearables.WearablesConfig
import com.example.oop.wearables.WearablesServiceLocator
import com.example.oop.wearables.data.WearableRepositoryImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class WearableStreamingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var notificationJob: Job? = null
    private lateinit var repository: WearableRepositoryImpl

    override fun onCreate() {
        super.onCreate()
        repository = WearablesServiceLocator.repositoryImpl(applicationContext)
        StreamingNotification.ensureChannel(this)
        startForegroundCompat(StreamingNotification.build(this))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopStreaming()
                return START_NOT_STICKY
            }

            ACTION_START -> beginStreaming()
            null -> beginStreaming()
            else -> beginStreaming()
        }
        return START_STICKY
    }

    private fun beginStreaming() {
        if (notificationJob?.isActive == true) return
        scope.launch { repository.beginIngestion() }
        notificationJob = scope.launch {
            val manager = NotificationManagerCompat.from(this@WearableStreamingService)
            while (true) {
                val snapshot = repository.latestSnapshot()
                runCatching {
                    manager.notify(
                        StreamingNotification.NOTIFICATION_ID,
                        StreamingNotification.build(this@WearableStreamingService, snapshot),
                    )
                }
                delay(WearablesConfig.notificationRefreshInterval.inWholeMilliseconds)
            }
        }
    }

    private fun stopStreaming() {
        scope.launch {
            runCatching { repository.endIngestion() }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                StreamingNotification.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH,
            )
        } else {
            startForeground(StreamingNotification.NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val ACTION_START = "com.example.oop.wearables.action.START"
        const val ACTION_STOP = "com.example.oop.wearables.action.STOP"

        fun start(context: Context) {
            val intent = Intent(context, WearableStreamingService::class.java)
                .setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, WearableStreamingService::class.java)
                .setAction(ACTION_STOP)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
