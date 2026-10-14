package com.vidabay.paced.ui

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.vidabay.paced.history.RecentImage
import com.vidabay.paced.history.RecentImagesRepository
import com.vidabay.paced.image.VidaBayImageProcessor
import com.vidabay.paced.nfc.LogEntry
import com.vidabay.paced.nfc.NfcEngine
import com.vidabay.paced.nfc.NfcTransferState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var nfcEngine: NfcEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcEngine = NfcEngine(this)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF6C63FF),
                    secondary = Color(0xFF03DAC6),
                    surface = Color(0xFF1E1E2E),
                    background = Color(0xFF12121A)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(nfcEngine)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        nfcEngine.disarm()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(nfcEngine: NfcEngine) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var originalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var ditheredBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var currentPayload by remember { mutableStateOf<ByteArray?>(null) }

    var interPacketDelay by remember { mutableFloatStateOf(nfcEngine.interPacketDelayMs.toFloat()) }
    var preChargeDelay by remember { mutableFloatStateOf(nfcEngine.preChargeSoakDelayMs.toFloat()) }
    var preRefreshDelay by remember { mutableFloatStateOf(nfcEngine.preRefreshDelayMs.toFloat()) }

    val transferState by nfcEngine.transferState.collectAsState()
    val logEntries = remember { mutableStateListOf<LogEntry>() }

    var recentImages by remember { mutableStateOf(emptyList<RecentImage>()) }

    LaunchedEffect(Unit) {
        recentImages = RecentImagesRepository.getRecentImages(context)
        nfcEngine.logFlow.collectLatest { entry ->
            logEntries.add(entry)
            if (logEntries.size > 200) {
                logEntries.removeAt(0)
            }
        }
    }

    // Image Picker Launcher
    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val source = ImageDecoder.createSource(context.contentResolver, it)
                        ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                            decoder.isMutableRequired = true
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        MediaStore.Images.Media.getBitmap(context.contentResolver, it)
                    }

                    val targetDim = VidaBayImageProcessor.DEFAULT_DIMENSION
                    val square = VidaBayImageProcessor.createSquareBitmap(bitmap, targetDim)
                    val dithered = VidaBayImageProcessor.ditherFloydSteinberg(square, targetDim)
                    val payload = VidaBayImageProcessor.packToVidaBayPayload(dithered, targetDim)

                    // Save to history
                    val saved = RecentImagesRepository.saveRecentImage(context, dithered, payload)
                    val updatedRecent = RecentImagesRepository.getRecentImages(context)

                    withContext(Dispatchers.Main) {
                        originalBitmap = square
                        ditheredBitmap = dithered
                        currentPayload = payload
                        recentImages = updatedRecent
                        Toast.makeText(context, "Image processed (${payload.size} bytes ready for ${targetDim}x${targetDim})", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Failed to process image", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Error processing image: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("MagPaper", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text("Passive E-Ink NFC Driver (FM1280)", fontSize = 12.sp, color = Color.Gray)
                    }
                },
                actions = {
                    val nfcReady = nfcEngine.isNfcSupported && nfcEngine.isNfcEnabled
                    AssistChip(
                        onClick = { },
                        label = { Text(if (nfcReady) "NFC Active" else "NFC Disabled", fontSize = 11.sp) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Nfc,
                                contentDescription = null,
                                tint = if (nfcReady) Color(0xFF4CAF50) else Color(0xFFFF5252),
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (nfcReady) Color(0xFF1B5E20).copy(alpha = 0.3f) else Color(0xFFB71C1C).copy(alpha = 0.3f)
                        )
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(modifier = Modifier.height(4.dp)) }

            // 1. Image Picker & Previews Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Image Preview (400x400 E-Ink)", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Button(
                                onClick = { pickerLauncher.launch("image/*") },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Pick Image", fontSize = 13.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            // Original / Square
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    modifier = Modifier
                                        .size(130.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.DarkGray),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (originalBitmap != null) {
                                        Image(
                                            bitmap = originalBitmap!!.asImageBitmap(),
                                            contentDescription = "Original",
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Text("No Image", color = Color.Gray, fontSize = 12.sp)
                                    }
                                }
                                Text("Original", fontSize = 11.sp, color = Color.LightGray, modifier = Modifier.padding(top = 4.dp))
                            }

                            // Dithered 4-Color
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    modifier = Modifier
                                        .size(130.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .border(2.dp, Color(0xFF6C63FF), RoundedCornerShape(8.dp))
                                        .background(Color.Black),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (ditheredBitmap != null) {
                                        Image(
                                            bitmap = ditheredBitmap!!.asImageBitmap(),
                                            contentDescription = "Dithered 4-Color",
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Text("4-Color Dither", color = Color.Gray, fontSize = 12.sp)
                                    }
                                }
                                Text("4-Color E-Ink", fontSize = 11.sp, color = Color(0xFF03DAC6), modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
            }

            // 2. Alignment & Onboarding Guide Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF1E2638)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3F51B5).copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.HelpOutline,
                                contentDescription = null,
                                tint = Color(0xFF64B5F6),
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                "How to Transfer & Align NFC",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = Color.White
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            "The E-Ink magnet has no battery — it harvests 100% of its operating power directly from your phone's NFC radio. Follow these tips for reliable transfers:",
                            fontSize = 12.sp,
                            color = Color(0xFFCFD8DC),
                            lineHeight = 16.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.Top) {
                                Text("1. ", fontWeight = FontWeight.Bold, color = Color(0xFF64B5F6), fontSize = 12.sp)
                                Text(
                                    "Locate your phone's NFC sweet spot: On Pixel phones it is near the camera bar; on Samsung Galaxy/iPhones it is near the top or center back.",
                                    fontSize = 12.sp,
                                    color = Color.LightGray,
                                    lineHeight = 16.sp
                                )
                            }
                            Row(verticalAlignment = Alignment.Top) {
                                Text("2. ", fontWeight = FontWeight.Bold, color = Color(0xFF64B5F6), fontSize = 12.sp)
                                Text(
                                    "Hold completely flat & steady: Rest the phone directly against the magnet. Do not move or tilt while transferring (takes ~10-15s data + ~20s refresh).",
                                    fontSize = 12.sp,
                                    color = Color.LightGray,
                                    lineHeight = 16.sp
                                )
                            }
                            Row(verticalAlignment = Alignment.Top) {
                                Text("3. ", fontWeight = FontWeight.Bold, color = Color(0xFFFFB74D), fontSize = 12.sp)
                                Text(
                                    "Angle matters if it doesn't flip: If the transfer finishes but the screen does not refresh, the magnet's capacitor dropped voltage. Shift position by 1-2 cm or angle 10-15° and tap Retry.",
                                    fontSize = 12.sp,
                                    color = Color(0xFFFFCC80),
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }
            }

            // 3. Recently Sent Images Section
            if (recentImages.isNotEmpty()) {
                item {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Recently Sent Images", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text("${recentImages.size}/10 saved", fontSize = 11.sp, color = Color.Gray)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(recentImages) { item ->
                                val bmp = remember(item.id) { item.loadBitmap() }
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            bmp?.let {
                                                ditheredBitmap = it
                                                originalBitmap = it
                                                currentPayload = item.loadPayload()
                                                Toast.makeText(context, "Loaded from history: ready to send", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                        .padding(4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(72.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
                                            .background(Color.Black)
                                    ) {
                                        if (bmp != null) {
                                            Image(
                                                bitmap = bmp.asImageBitmap(),
                                                contentDescription = null,
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop
                                            )
                                        }
                                    }
                                    Text(item.timestamp, fontSize = 9.sp, color = Color.Gray, modifier = Modifier.padding(top = 2.dp))
                                }
                            }
                        }
                    }
                }
            }

            // 4. Action Buttons (Arm & One-Tap Retry)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val isArmed = transferState is NfcTransferState.ArmedWaitingForTag ||
                            transferState is NfcTransferState.Connected ||
                            transferState is NfcTransferState.PreCharging ||
                            transferState is NfcTransferState.Transferring

                    Button(
                        onClick = {
                            if (isArmed) {
                                nfcEngine.disarm()
                            } else {
                                currentPayload?.let { nfcEngine.armForTransfer(it) }
                                    ?: Toast.makeText(context, "Pick an image first", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isArmed) Color(0xFFFF5252) else Color(0xFF6C63FF)
                        )
                    ) {
                        Icon(
                            if (isArmed) Icons.Default.Cancel else Icons.Default.Nfc,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isArmed) "Cancel Arm" else "Arm NFC Transfer")
                    }

                    // One-Tap Retry Button
                    FilledTonalButton(
                        onClick = {
                            if (currentPayload != null) {
                                nfcEngine.retry()
                                Toast.makeText(context, "Re-armed for tap retry", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "No armed image to retry", Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = currentPayload != null,
                        modifier = Modifier.weight(0.6f)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Retry")
                    }
                }
            }

            // 5. Collapsible Advanced Connection & Energy Pacing Tuning
            item {
                var isExpanded by remember { mutableStateOf(false) }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { isExpanded = !isExpanded },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Advanced Energy & Pacing Tuning",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp
                                )
                                Text(
                                    if (isExpanded) "Tap to collapse" else "Optional: Fine-tune timings for weak phone coils",
                                    fontSize = 11.sp,
                                    color = Color.Gray
                                )
                            }
                            IconButton(onClick = { isExpanded = !isExpanded }) {
                                Icon(
                                    if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = if (isExpanded) "Collapse" else "Expand"
                                )
                            }
                        }

                        AnimatedVisibility(visible = isExpanded) {
                            Column(modifier = Modifier.padding(top = 12.dp)) {
                                // Presets
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            interPacketDelay = 35f
                                            preChargeDelay = 600f
                                            preRefreshDelay = 1500f
                                            nfcEngine.interPacketDelayMs = 35L
                                            nfcEngine.preChargeSoakDelayMs = 600L
                                            nfcEngine.preRefreshDelayMs = 1500L
                                        },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text("Standard (Default)", fontSize = 11.sp)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            interPacketDelay = 50f
                                            preChargeDelay = 1000f
                                            preRefreshDelay = 2000f
                                            nfcEngine.interPacketDelayMs = 50L
                                            nfcEngine.preChargeSoakDelayMs = 1000L
                                            nfcEngine.preRefreshDelayMs = 2000L
                                        },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text("Weak Coil / Pixel", fontSize = 11.sp)
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                // Inter-packet pacing slider
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Inter-Packet Delay", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                        Text(
                                            "Pauses between chunk packets to let the magnet capacitor recharge.",
                                            fontSize = 10.sp,
                                            color = Color.Gray
                                        )
                                    }
                                    Text("${interPacketDelay.toInt()} ms", fontWeight = FontWeight.Bold, color = Color(0xFF03DAC6), fontSize = 13.sp)
                                }
                                Slider(
                                    value = interPacketDelay,
                                    onValueChange = {
                                        interPacketDelay = it
                                        nfcEngine.interPacketDelayMs = it.toLong()
                                    },
                                    valueRange = 0f..100f,
                                    steps = 19
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                // Pre-charge delay slider
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Pre-Charge Soak Delay", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                        Text(
                                            "Initial settling delay right after NFC contact before sending data.",
                                            fontSize = 10.sp,
                                            color = Color.Gray
                                        )
                                    }
                                    Text("${preChargeDelay.toInt()} ms", fontWeight = FontWeight.Bold, color = Color(0xFF03DAC6), fontSize = 13.sp)
                                }
                                Slider(
                                    value = preChargeDelay,
                                    onValueChange = {
                                        preChargeDelay = it
                                        nfcEngine.preChargeSoakDelayMs = it.toLong()
                                    },
                                    valueRange = 100f..1500f,
                                    steps = 13
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                // Pre-refresh pause slider
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Pre-Refresh Charge Pause", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                        Text(
                                            "Allows high voltage charge pump to saturate before flipping E-Ink particles.",
                                            fontSize = 10.sp,
                                            color = Color.Gray
                                        )
                                    }
                                    Text("${preRefreshDelay.toInt()} ms", fontWeight = FontWeight.Bold, color = Color(0xFF03DAC6), fontSize = 13.sp)
                                }
                                Slider(
                                    value = preRefreshDelay,
                                    onValueChange = {
                                        preRefreshDelay = it
                                        nfcEngine.preRefreshDelayMs = it.toLong()
                                    },
                                    valueRange = 200f..2000f,
                                    steps = 17
                                )
                            }
                        }
                    }
                }
            }

            // 5. Transfer State & Progress Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = when (transferState) {
                            is NfcTransferState.Success -> Color(0xFF1B5E20).copy(alpha = 0.5f)
                            is NfcTransferState.Error -> Color(0xFFB71C1C).copy(alpha = 0.5f)
                            is NfcTransferState.ArmedWaitingForTag -> Color(0xFF311B92).copy(alpha = 0.5f)
                            is NfcTransferState.Transferring -> Color(0xFF004D40).copy(alpha = 0.5f)
                            else -> MaterialTheme.colorScheme.surface
                        }
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            when (transferState) {
                                is NfcTransferState.Idle -> Icon(Icons.Default.Info, contentDescription = null, tint = Color.Gray)
                                is NfcTransferState.ArmedWaitingForTag -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                is NfcTransferState.Connected -> Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF03DAC6))
                                is NfcTransferState.PreCharging -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                is NfcTransferState.Handshaking -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                is NfcTransferState.Transferring -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                is NfcTransferState.PreRefreshSoak -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                is NfcTransferState.RefreshingScreen -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                is NfcTransferState.PollingStatus -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                is NfcTransferState.Success -> Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50))
                                is NfcTransferState.Error -> Icon(Icons.Default.Error, contentDescription = null, tint = Color(0xFFFF5252))
                            }

                            Text(
                                text = when (val s = transferState) {
                                    is NfcTransferState.Idle -> "Idle - Pick image and arm NFC"
                                    is NfcTransferState.ArmedWaitingForTag -> "Armed: Tap phone against VidaBay Magnet..."
                                    is NfcTransferState.Connected -> "Tag Connected (${s.tagUid})"
                                    is NfcTransferState.PreCharging -> "Pre-charging capacitor bank..."
                                    is NfcTransferState.Handshaking -> "Performing session handshake & auth..."
                                    is NfcTransferState.Transferring -> "Writing block ${s.currentChunk}/${s.totalChunks} (${(s.progress * 100).toInt()}%)"
                                    is NfcTransferState.PreRefreshSoak -> "Pausing for pre-refresh capacitor soak..."
                                    is NfcTransferState.RefreshingScreen -> "Triggering physical E-Ink waveform..."
                                    is NfcTransferState.PollingStatus -> "Polling display completion..."
                                    is NfcTransferState.Success -> s.message
                                    is NfcTransferState.Error -> "Failed: ${s.error}"
                                },
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp
                            )
                        }

                        if (transferState is NfcTransferState.Transferring) {
                            val state = transferState as NfcTransferState.Transferring
                            Spacer(modifier = Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = Color(0xFF03DAC6)
                            )
                        }
                    }
                }
            }

            // 6. Live APDU / Hex Console
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0A10)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Live APDU & Hex Console", fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Color.LightGray)
                            TextButton(onClick = { logEntries.clear() }) {
                                Text("Clear", fontSize = 11.sp)
                            }
                        }

                        val listState = rememberLazyListState()
                        LaunchedEffect(logEntries.size) {
                            if (logEntries.isNotEmpty()) {
                                listState.animateScrollToItem(logEntries.size - 1)
                            }
                        }

                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        ) {
                            if (logEntries.isEmpty()) {
                                item {
                                    Text("Waiting for APDU traffic...", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.DarkGray)
                                }
                            } else {
                                items(logEntries) { entry ->
                                    val textColor = when {
                                        entry.isTx -> Color(0xFF64B5F6)
                                        entry.message.startsWith("Tag connected") -> Color(0xFF81C784)
                                        entry.message.startsWith("Screen refresh confirmed") -> Color(0xFFAED581)
                                        entry.message.contains("failed", ignoreCase = true) -> Color(0xFFE57373)
                                        else -> Color(0xFFE0E0E0)
                                    }
                                    Text(
                                        text = "[${entry.timestamp}] ${entry.message}",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        color = textColor,
                                        lineHeight = 13.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(20.dp)) }
        }
    }
}
