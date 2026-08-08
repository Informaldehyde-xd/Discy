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
import androidx.documentfile.provider.DocumentFile
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

        const val EXTRA_INPUT_URIS = "extra_input_uris"
        const val EXTRA_OUTPUT_DIR_URI = "extra_output_dir_uri"
        const val EXTRA_CONVERSION_TYPE = "extra_conversion_type"

        const val ACTION_CONVERSION_PROGRESS = "com.example.discconverter.PROGRESS"
        const val ACTION_CONVERSION_ERROR = "com.example.discconverter.ERROR"
        const val ACTION_BATCH_COMPLETE = "com.example.discconverter.BATCH_COMPLETE"

        const val EXTRA_PROGRESS = "extra_progress"
        const val EXTRA_ERROR_MESSAGE = "extra_error_message"
        const val EXTRA_FILE_NAME = "extra_file_name"
        const val EXTRA_BATCH_INDEX = "extra_batch_index"
        const val EXTRA_BATCH_TOTAL = "extra_batch_total"
        const val EXTRA_SUCCESS_COUNT = "extra_success_count"
        const val EXTRA_FAILED_COUNT = "extra_failed_count"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val inputUris: ArrayList<Uri>? = intent?.getParcelableArrayListExtra(EXTRA_INPUT_URIS)
        val outputDirUri: Uri? = intent?.getParcelableExtra(EXTRA_OUTPUT_DIR_URI)
        val typeName = intent?.getStringExtra(EXTRA_CONVERSION_TYPE)
        val type = typeName?.let { runCatching { ConversionType.valueOf(it) }.getOrNull() }

        if (!inputUris.isNullOrEmpty() && outputDirUri != null && type != null) {
            startForegroundServiceNotification()

            serviceScope.launch {
                val outputDir = DocumentFile.fromTreeUri(this@ConversionService, outputDirUri)
                val total = inputUris.size
                var successCount = 0
                var failedCount = 0

                for ((index, inputUri) in inputUris.withIndex()) {
                    val fileName = DiscConverter.getFileName(this@ConversionService, inputUri)

                    try {
                        val outputName = DiscConverter.deriveOutputName(fileName, type)
                        val outputFile = outputDir?.createFile("application/octet-stream", outputName)
                            ?: throw IllegalStateException("Could not create '$outputName' in the selected folder")

                        var lastUpdatePercent = -1
                        val progressCallback: (Float) -> Unit = { progress ->
                            val currentPercent = (progress * 100).toInt()
                            if (currentPercent > lastUpdatePercent) {
                                lastUpdatePercent = currentPercent
                                updateNotification(index + 1, total, fileName, currentPercent)
                                sendBroadcast(Intent(ACTION_CONVERSION_PROGRESS).apply {
                                    putExtra(EXTRA_PROGRESS, progress)
                                    putExtra(EXTRA_FILE_NAME, fileName)
                                    putExtra(EXTRA_BATCH_INDEX, index + 1)
                                    putExtra(EXTRA_BATCH_TOTAL, total)
                                })
                            }
                        }

                        when (type) {
                            ConversionType.BIN_TO_ISO -> DiscConverter.binToIso(this@ConversionService, inputUri, outputFile.uri, progressCallback)
                            ConversionType.ISO_TO_ZSO -> DiscConverter.isoToZso(this@ConversionService, inputUri, outputFile.uri, onProgress = progressCallback)
                            ConversionType.ZSO_TO_ISO -> DiscConverter.zsoToIso(this@ConversionService, inputUri, outputFile.uri, progressCallback)
                        }

                        successCount++
                    } catch (e: Exception) {
                        failedCount++
                        sendBroadcast(Intent(ACTION_CONVERSION_ERROR).apply {
                            putExtra(EXTRA_ERROR_MESSAGE, "$fileName: ${e.localizedMessage ?: "Unknown error"}")
                            putExtra(EXTRA_FILE_NAME, fileName)
                            putExtra(EXTRA_BATCH_INDEX, index + 1)
                            putExtra(EXTRA_BATCH_TOTAL, total)
                        })
                        // Keep going - one bad file shouldn't abort the whole batch
                    }
                }

                sendBroadcast(Intent(ACTION_BATCH_COMPLETE).apply {
                    putExtra(EXTRA_SUCCESS_COUNT, successCount)
                    putExtra(EXTRA_FAILED_COUNT, failedCount)
                    putExtra(EXTRA_BATCH_TOTAL, total)
                })

                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
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
        val notification = createNotification(1, 1, "Preparing...", 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(fileIndex: Int, totalFiles: Int, fileName: String, progressPercent: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(fileIndex, totalFiles, fileName, progressPercent))
    }

    private fun createNotification(fileIndex: Int, totalFiles: Int, fileName: String, progressPercent: Int): Notification {
        val title = if (totalFiles > 1) "Converting $fileIndex of $totalFiles" else "Converting Disc Image..."
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("$fileName — $progressPercent%")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setProgress(100, progressPercent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}
