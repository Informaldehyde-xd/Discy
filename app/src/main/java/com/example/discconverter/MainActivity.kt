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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private var progressState = mutableFloatStateOf(0f)
    private var isConvertingState = mutableStateOf(false)
    private var statusMessageState = mutableStateOf("")
    private var isErrorState = mutableStateOf(false)

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
                    isErrorState.value = true
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
                    color = Color(0xFF050A15)
                ) {
                    ConverterScreen(
                        isConverting = isConvertingState.value,
                        progress = progressState.floatValue,
                        statusMessage = statusMessageState.value,
                        isError = isErrorState.value,
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
        isErrorState.value = false
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
    isError: Boolean,
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

    // Colors
    val bgGradient = Brush.linearGradient(listOf(Color(0xFF030713), Color(0xFF071126), Color(0xFF02050D)))
    val cardGradient = Brush.linearGradient(listOf(Color(0xFF112242), Color(0xFF060E1F)))

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgGradient)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(0x26438CFF), RoundedCornerShape(12.dp))
                            .border(1.dp, Color(0x26438CFF), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color(0xFF81ACFF), modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "DISC CONVERTER",
                        color = Color(0xFFD9E8FF),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        letterSpacing = 1.2.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .background(Color(0x29377FF3), RoundedCornerShape(16.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("READY", color = Color(0xFF9BC5FF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.weight(0.5f))

            // Main Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .shadow(32.dp, RoundedCornerShape(24.dp), spotColor = Color.Black)
                    .clip(RoundedCornerShape(24.dp))
                    .background(cardGradient)
                    .border(1.dp, Color(0x29A1C5FF), RoundedCornerShape(24.dp))
                    .padding(32.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    
                    // Custom Disc Art
                    Box(modifier = Modifier.size(120.dp), contentAlignment = Alignment.Center) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawCircle(brush = Brush.radialGradient(listOf(Color(0xFF081126), Color(0xFF438CFF), Color(0xFF0A1C40), Color(0xFF071126))), radius = size.width / 2)
                            drawCircle(color = Color(0x12BEDAFF), radius = (size.width / 2) - 10f, style = Stroke(2f))
                            drawCircle(color = Color(0xFF050A15), radius = size.width / 6)
                        }
                    }

                    Spacer(modifier = Modifier.height(28.dp))
                    Text("DISC IMAGE UTILITY", color = Color(0xFF7AAEFF), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Disc Converter", color = Color(0xFFF2F7FF), fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Prepare your disc image for the format you need.", color = Color(0xFF9FB1D0), fontSize = 14.sp, textAlign = TextAlign.Center)

                    Spacer(modifier = Modifier.height(36.dp))

                    // Format Selection
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ConversionType.entries.forEach { mode ->
                            val isSelected = selectedMode == mode
                            val bg = if (isSelected) Brush.linearGradient(listOf(Color(0xFF1D58B8), Color(0xFF387DF0))) else Brush.linearGradient(listOf(Color(0x8C040C1D), Color(0x8C040C1D)))
                            val border = if (isSelected) Color(0xFF438CFF) else Color(0x2E9EC2FF)
                            val textColor = if (isSelected) Color.White else Color(0xFFA9BBD9)

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(bg)
                                    .border(1.dp, border, RoundedCornerShape(12.dp))
                                    .clickable { selectedMode = mode }
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(mode.name.replace('_', ' '), color = textColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Select File Area
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xC7071229))
                            .clickable { openDocumentLauncher.launch(arrayOf("*/*")) }
                    ) {
                        // Dashed Border
                        Canvas(modifier = Modifier.matchParentSize()) {
                            drawRoundRect(
                                color = Color(0x6183B0FF),
                                size = size,
                                cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx()),
                                style = Stroke(width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 15f), 0f))
                            )
                        }
                        
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(Color(0x1A60A5FA), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFF93C5FD), modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Select source file", color = Color(0xFFEAF2FF), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Text(if (inputFileName.isEmpty()) "BIN, ISO, ZSO, CSO, or IMG" else inputFileName, color = Color(0xFF8EA3C6), fontSize = 12.sp, maxLines = 1)
                            }
                            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = Color(0xB3BFDBFE))
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Convert Button
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isConverting || inputUri == null) Brush.linearGradient(listOf(Color(0xFF3177EB).copy(alpha = 0.5f), Color(0xFF55A1FF).copy(alpha = 0.5f)))
                                else Brush.linearGradient(listOf(Color(0xFF3177EB), Color(0xFF55A1FF)))
                            )
                            .clickable(enabled = !isConverting && inputUri != null) {
                                val defaultOutput = DiscConverter.deriveOutputName(inputFileName, selectedMode)
                                createDocumentLauncher.launch(defaultOutput)
                            }
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Convert & Save As...", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }

                    // Progress Area
                    if (isConverting || progress > 0f || statusMessage.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        // Track
                        Box(modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color(0x219EBEF6), CircleShape)) {
                            Box(modifier = Modifier
                                .fillMaxWidth(progress.coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(Brush.horizontalGradient(listOf(Color(0xFF357FF3), Color(0xFF8CC1FF))), CircleShape))
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier
                            .background(Color(0xA1020814), RoundedCornerShape(12.dp))
                            .border(1.dp, Color(0x2197B8EC), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                            .fillMaxWidth()) {
                            Icon(
                                imageVector = if (isError) Icons.Default.Warning else Icons.Default.Info,
                                contentDescription = null,
                                tint = if (isError) Color(0xFFFF6B6B) else Color(0xFF83B0FF),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(statusMessage, color = if (isError) Color(0xFFFF6B6B) else Color(0xFF9FB1D0), fontSize = 12.sp)
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            // Footer
            Text(
                text = "Built for clean disc-image workflows",
                color = Color(0xFF64799E),
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 24.dp)
            )
        }
    }
}
