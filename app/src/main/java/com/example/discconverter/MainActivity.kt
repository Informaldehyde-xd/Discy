package com.example.discconverter

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private var progressState = mutableFloatStateOf(0f)
    private var isConvertingState = mutableStateOf(false)
    private var statusMessageState = mutableStateOf("Select a file to convert")

    private val conversionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ConversionService.ACTION_CONVERSION_PROGRESS -> {
                    val progress = intent.getFloatExtra(ConversionService.EXTRA_PROGRESS, 0f)
                    progressState.floatValue = progress
                    statusMessageState.value = "Converting: ${(progress * 100).toInt()}%"
                }
                ConversionService.ACTION_CONVERSION_COMPLETE -> {
                    isConvertingState.value = false
                    progressState.floatValue = 1f
                    statusMessageState.value = "Conversion completed successfully!"
                    Toast.makeText(this@MainActivity, "Conversion Done!", Toast.LENGTH_SHORT).show()
                }
                ConversionService.ACTION_CONVERSION_ERROR -> {
                    isConvertingState.value = false
                    val error = intent.getStringExtra(ConversionService.EXTRA_ERROR_MESSAGE) ?: "Unknown error"
                    statusMessageState.value = "Error: $error"
                    Toast.makeText(this@MainActivity, "Failed: $error", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val filter = IntentFilter().apply {
            addAction(ConversionService.ACTION_CONVERSION_PROGRESS)
            addAction(ConversionService.ACTION_CONVERSION_COMPLETE)
            addAction(ConversionService.ACTION_CONVERSION_ERROR)
        }

        ContextCompat.registerReceiver(
            this,
            conversionReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ConverterScreen(
                        isConverting = isConvertingState.value,
                        progress = progressState.floatValue,
                        statusMessage = statusMessageState.value,
                        onStartConversion = { mode, inputUri, outputUri ->
                            requestNotificationPermissionAndStart(mode, inputUri, outputUri)
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(conversionReceiver)
    }

    private fun requestNotificationPermissionAndStart(mode: ConversionType, inputUri: Uri, outputUri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
        startConversionService(mode, inputUri, outputUri)
    }

    private fun startConversionService(mode: ConversionType, inputUri: Uri, outputUri: Uri) {
        isConvertingState.value = true
        progressState.floatValue = 0f
        statusMessageState.value = "Starting conversion..."

        val intent = Intent(this, ConversionService::class.java).apply {
            putExtra(ConversionService.EXTRA_INPUT_URI, inputUri)
            putExtra(ConversionService.EXTRA_OUTPUT_URI, outputUri)
            putExtra(ConversionService.EXTRA_CONVERSION_TYPE, mode.name)
        }
        ContextCompat.startForegroundService(this, intent)
    }
}

@Composable
fun ConverterScreen(
    isConverting: Boolean,
    progress: Float,
    statusMessage: String,
    onStartConversion: (ConversionType, Uri, Uri) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var selectedMode by remember { mutableStateOf(ConversionType.BIN_TO_ISO) }
    var inputUri by remember { mutableStateOf<Uri?>(null) }
    var inputFileName by remember { mutableStateOf("") }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { outputUri ->
        if (outputUri != null && inputUri != null) {
            onStartConversion(selectedMode, inputUri!!, outputUri)
        }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            inputUri = uri
            inputFileName = DiscConverter.getFileName(context, uri)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "Disc Converter", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(24.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ConversionType.entries.forEach { mode ->
                FilterChip(
                    selected = selectedMode == mode,
                    onClick = { selectedMode = mode },
                    label = { Text(mode.name.replace('_', ' ')) }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { openDocumentLauncher.launch(arrayOf("*/*")) },
            enabled = !isConverting
        ) {
            Text("Select Source File")
        }

        if (inputFileName.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "Selected: $inputFileName", style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                val defaultOutput = DiscConverter.deriveOutputName(inputFileName, selectedMode)
                createDocumentLauncher.launch(defaultOutput)
            },
            enabled = !isConverting && inputUri != null
        ) {
            Text("Convert & Save As...")
        }

        Spacer(modifier = Modifier.height(32.dp))

        if (isConverting || progress > 0f) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        Text(text = statusMessage, style = MaterialTheme.typography.bodyLarge)
    }
}
