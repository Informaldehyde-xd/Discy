package com.example.discconverter

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BatchProgressState(
    val isRunning: Boolean = false,
    val currentFileIndex: Int = 0,
    val totalFiles: Int = 0,
    val currentFileName: String = "",
    val fileProgress: Float = 0f,
    val errorMessage: String? = null
)

class ConversionService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var notificationManager: NotificationManager

    companion object {
        const val CHANNEL_ID = "conversion_service_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_BATCH = "ACTION_START_BATCH"
        const val EXTRA_INPUT_URIS = "EXTRA_INPUT_URIS"
        const val EXTRA_OUTPUT_DIR_URI = "EXTRA_OUTPUT_DIR_URI"
        const val EXTRA_MODE = "EXTRA_MODE"

        // Expose state to Compose UI
        private val _conversionState = MutableStateFlow(BatchProgressState())
        val conversionState: StateFlow<BatchProgressState> = _conversionState.asStateFlow()
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_BATCH) {
            val inputUris = intent.getParcelableArrayListExtra<Uri>(EXTRA_INPUT_URIS) ?: emptyList()
            val outputDirUri = intent.getParcelableExtra<Uri>(EXTRA_OUTPUT_DIR_URI)
            val modeName = intent.getStringExtra(EXTRA_MODE) ?: ConversionType.BIN_TO_ISO.name
            val mode = ConversionType.valueOf(modeName)

            if (inputUris.isNotEmpty() && outputDirUri != null) {
                startForegroundServiceWithNotification()
                runBatchTask(inputUris, outputDirUri, mode)
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundServiceWithNotification() {
        val notification = createNotification("Starting batch conversion...", 0, 100, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun runBatchTask(inputUris: List<Uri>, outputDirUri: Uri, mode: ConversionType) {
        serviceScope.launch {
            _conversionState.value = BatchProgressState(isRunning = true, totalFiles = inputUris.size)

            try {
                inputUris.forEachIndexed { index, uri ->
                    val originalName = DiscConverter.getFileName(this@ConversionService, uri)
                    val targetName = DiscConverter.deriveOutputName(originalName, mode)

                    _conversionState.value = _conversionState.value.copy(
                        currentFileIndex = index + 1,
                        currentFileName = originalName,
                        fileProgress = 0f
                    )

                    updateNotification(
                        title = "Converting (${index + 1}/${inputUris.size}): $originalName",
                        progress = 0
                    )

                    val targetFileUri = DocumentsContract.createDocument(
                        contentResolver,
                        outputDirUri,
                        "application/octet-stream",
                        targetName
                    )

                    if (targetFileUri != null) {
                        val progressCallback: (Float) -> Unit = { progress ->
                            _conversionState.value = _conversionState.value.copy(fileProgress = progress)
                            val pct = (progress * 100).toInt()
                            if (pct % 5 == 0) { // Throttle notification updates
                                updateNotification(
                                    title = "Converting (${index + 1}/${inputUris.size}): $originalName",
                                    progress = pct
                                )
                            }
                        }

                        when (mode) {
                            ConversionType.BIN_TO_ISO -> DiscConverter.binToIso(
                                this@ConversionService, uri, targetFileUri, progressCallback
                            )
                            ConversionType.ISO_TO_ZSO -> DiscConverter.isoToZso(
                                this@ConversionService, uri, targetFileUri, 6, progressCallback
                            )
                            ConversionType.ZSO_TO_ISO -> DiscConverter.zsoToIso(
                                this@ConversionService, uri, targetFileUri, progressCallback
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                _conversionState.value = _conversionState.value.copy(errorMessage = e.message)
            } finally {
                _conversionState.value = BatchProgressState(isRunning = false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun updateNotification(title: String, progress: Int) {
        val notification = createNotification(title, progress, 100, progress)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotification(
        contentTitle: String,
        progress: Int,
        maxProgress: Int,
        pct: Int
    ) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Disc Image Converter")
        .setContentText(contentTitle)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setOngoing(true)
        .setProgress(maxProgress, progress, false)
        .setSubText("$pct%")
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Disc Conversion Processing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress during active file conversions."
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
