package com.example.oop.wearables.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.oop.MainActivity
import com.example.oop.R
import com.example.oop.wearables.model.VitalSample
import com.example.oop.wearables.model.VitalType

internal object StreamingNotification {

    const val CHANNEL_ID = "wearable_streaming"
    const val NOTIFICATION_ID = 0x7A1100

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.wearables_streaming_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.wearables_streaming_notification_channel_desc)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(
        context: Context,
        snapshot: Map<VitalType, VitalSample> = emptyMap(),
    ): android.app.Notification {
        ensureChannel(context)

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val stopIntent = PendingIntent.getService(
            context,
            1,
            Intent(context, WearableStreamingService::class.java)
                .setAction(WearableStreamingService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.wearables_streaming_notification_title))
            .setContentText(renderContent(snapshot))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .addAction(
                0,
                context.getString(R.string.wearables_streaming_notification_stop_action),
                stopIntent,
            )
            .build()
    }

    private fun renderContent(snapshot: Map<VitalType, VitalSample>): String {
        if (snapshot.isEmpty()) return "Waiting for samples"
        val parts = mutableListOf<String>()
        snapshot[VitalType.HeartRate]?.let { parts += "HR ${it.value.toInt()} bpm" }
        snapshot[VitalType.OxygenSaturation]?.let { parts += "SpO2 ${it.value.toInt()}%" }
        snapshot[VitalType.HrvRmssd]?.let { parts += "HRV ${it.value.toInt()} ms" }
        snapshot[VitalType.RespiratoryRate]?.let { parts += "Resp ${it.value.toInt()}" }
        return if (parts.isEmpty()) "Streaming" else parts.joinToString(" · ")
    }
}
