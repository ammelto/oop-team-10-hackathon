package com.example.oop.wearables

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

object WearablesConfig {

    val healthConnectPollInterval: Duration = 5.seconds

    val bootstrapReadWindow: Duration = 5.seconds * 60

    val sharedFlowBufferCapacity: Int = 256

    val retentionWindow: Duration = 7.days

    val persistBatchMaxSize: Int = 32

    val persistBatchMaxLatency: Duration = 500.milliseconds

    val notificationRefreshInterval: Duration = 10.seconds

    const val MOCK_AUTOENABLE_GRACE_SECONDS: Long = 30L
}
