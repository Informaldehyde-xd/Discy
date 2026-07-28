package com.example.discconverter

import android.Manifest
import android.content.Intent
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    BatchConverterScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchConverterScreen() {
    val context = LocalContext.current
    var selectedMode by remember { mutableStateOf(ConversionType.BIN_TO_ISO) }
    var inputUris by remember { mutableStateOf<List<Uri>>(emptyList()) }

    // Collect foreground service state
    val state by ConversionService.conversionState.collectAsState()

    // Permission launcher for Notifications (Android 13+)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(context, "Notification permission required for background progress", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // Directory Picker Launcher
    val dirPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { outputDirUri ->
        if (outputDirUri != null && inputUris.isNotEmpty()) {
            val intent = Intent(context, ConversionService::class.java).apply {
                action = ConversionService.ACTION_START_BATCH
                putParcelableArrayListExtra(ConversionService.EXTRA_INPUT_URIS, ArrayList(inputUris))
                putExtra(ConversionService.EXTRA_OUTPUT_DIR_URI, outputDirUri)
                putExtra(ConversionService.EXTRA_MODE, selectedMode.name)
            }
            ContextCompat.startForegroundService(context, intent)
            inputUris = emptyList()
        }
    }

    // Multiple Files Picker Launcher
    val multipleFilesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            inputUris = uris
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Batch Disc Converter", style = MaterialTheme.typography.headlineMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selectedMode == ConversionType.BIN_TO_ISO,
                onClick = { if (!state.isRunning) { selectedMode = ConversionType.BIN_TO_ISO; inputUris = emptyList() } },
                label = { Text("BIN → ISO") }
            )
            FilterChip(
                selected = selectedMode == ConversionType.ISO_TO_ZSO,
                onClick = { if (!state.isRunning) { selectedMode = ConversionType.ISO_TO_ZSO; inputUris = emptyList() } },
                label = { Text("ISO → ZSO") }
            )
            FilterChip(
                selected = selectedMode == ConversionType.ZSO_TO_ISO,
                onClick = { if (!state.isRunning) { selectedMode = ConversionType.ZSO_TO_ISO; inputUris = emptyList() } },
                label = { Text("ZSO → ISO") }
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    val mimeTypes = arrayOf("application/octet-stream", "application/x-iso9660-image", "*/*")
                    multipleFilesLauncher.launch(mimeTypes)
                },
                enabled = !state.isRunning
            ) {
                Text(if (inputUris.isEmpty()) "Select Files" else "Add / Change Files")
            }

            if (inputUris.isNotEmpty()) {
                OutlinedButton(
                    onClick = { inputUris = emptyList() },
                    enabled = !state.isRunning
                ) {
                    Text("Clear")
                }
            }
        }

        if (inputUris.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Selected (${inputUris.size} files):",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(inputUris) { uri ->
                            val name = DiscConverter.getFileName(context, uri)
                            Text(text = "• $name", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                    }
                }
            }

            Button(
                onClick = { dirPickerLauncher.launch(null) },
                enabled = !state.isRunning,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Select Destination Folder & Start")
            }
        } else if (!state.isRunning) {
            Spacer(modifier = Modifier.weight(1f))
            Text(
                "No files selected.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.weight(1f))
        }

        // Live status from Foreground Service
        if (state.isRunning) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Converting file ${state.currentFileIndex} of ${state.totalFiles}",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = state.currentFileName,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1
                    )
                    LinearProgressIndicator(
                        progress = { state.fileProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "${(state.fileProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            }
        }
    }
}
