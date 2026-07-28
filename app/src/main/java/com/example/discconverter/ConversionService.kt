package com.example.discconverter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ConversionService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    companion object {
        const val CHANNEL_ID = "conversion_service_channel"
        const val NOTIFICATION_ID = 1001

        const val EXTRA_INPUT_URI = "extra_input_uri"
        const val EXTRA_OUTPUT_URI = "extra_output_uri"
        const val EXTRA_CONVERSION_TYPE = "extra_conversion_type"

        const val ACTION_CONVERSION_PROGRESS = "com.example.discconverter.PROGRESS"
        const val ACTION_CONVERSION_COMPLETE = "com.example.discconverter.COMPLETE"
        const val ACTION_CONVERSION_ERROR = "com.example.discconverter.ERROR"

        const val EXTRA_PROGRESS = "extra_progress"
        const val EXTRA_ERROR_MESSAGE = "extra_error_message"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val inputUri: Uri? = intent?.getParcelableExtra(EXTRA_INPUT_URI)
        val outputUri: Uri? = intent?.getParcelableExtra(EXTRA_OUTPUT_URI)
        val typeName = intent?.getStringExtra(EXTRA_CONVERSION_TYPE)
        val type = typeName?.let { runCatching { ConversionType.valueOf(it) }.getOrNull() }

        if (inputUri != null && outputUri != null && type != null) {
            startForegroundServiceNotification()

            serviceScope.launch {
                try {
                    val progressCallback: (Float) -> Unit = { progress ->
                        updateNotification((progress * 100).toInt())
                        sendBroadcast(Intent(ACTION_CONVERSION_PROGRESS).apply {
                            putExtra(EXTRA_PROGRESS, progress)
                        })
                    }

                    when (type) {
                        ConversionType.BIN_TO_ISO -> DiscConverter.binToIso(this@ConversionService, inputUri, outputUri, progressCallback)
                        ConversionType.ISO_TO_ZSO -> DiscConverter.isoToZso(this@ConversionService, inputUri, outputUri, onProgress = progressCallback)
                        ConversionType.ZSO_TO_ISO -> DiscConverter.zsoToIso(this@ConversionService, inputUri, outputUri, progressCallback)
                    }

                    sendBroadcast(Intent(ACTION_CONVERSION_COMPLETE))
                } catch (e: Exception) {
                    sendBroadcast(Intent(ACTION_CONVERSION_ERROR).apply {
                        putExtra(EXTRA_ERROR_MESSAGE, e.localizedMessage ?: "Unknown error occurred")
                    })
                } finally {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        } else {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Disc Conversion Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundServiceNotification() {
        val notification = createNotification(0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(progressPercent: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(progressPercent))
    }

    private fun createNotification(progressPercent: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Converting Disc Image...")
            .setContentText("Progress: $progressPercent%")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setProgress(100, progressPercent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}
