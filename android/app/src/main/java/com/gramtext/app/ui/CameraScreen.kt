package com.gramtext.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gramtext.app.BuildConfig
import com.gramtext.app.LocalStrings
import com.gramtext.app.ServerStatus
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The app's main screen: live camera with a big scan box. Only what is inside the box is
 * read (frames and photos are cropped to [ScanBox] before upload).
 */
@Composable
fun CameraScreen(
    liveMode: Boolean,
    liveText: String,
    liveReading: Boolean,
    liveSpokeIn: Double?,
    playing: Boolean,
    server: ServerStatus,
    liveBusy: () -> Boolean,
    onLiveFrame: (ByteArray) -> Unit,
    onToggleLive: (Boolean) -> Unit,
    onRetryServer: () -> Unit,
    onGallery: () -> Unit,
    onSettings: () -> Unit,
    onCaptured: (Uri) -> Unit,
    onStopSpeech: () -> Unit,
) {
    val s = LocalStrings.current
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }

    if (!granted) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.PhotoCamera, null, Modifier.size(72.dp), tint = Violet)
            Text(s.cameraPermission, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Button(onClick = { permission.launch(Manifest.permission.CAMERA) }, Modifier.fillMaxWidth().height(64.dp)) {
                Text(s.grantPermission)
            }
            OutlinedButton(onClick = onGallery, Modifier.fillMaxWidth().height(56.dp)) {
                Icon(Icons.Filled.PhotoLibrary, null)
                Spacer(Modifier.size(8.dp))
                Text(s.pickGallery)
            }
        }
        return
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    }
    val live by rememberUpdatedState(liveMode)
    val busyCheck by rememberUpdatedState(liveBusy)
    val playingNow by rememberUpdatedState(playing)
    val frameHandler by rememberUpdatedState(onLiveFrame)
    val analyzer = remember {
        LiveAnalyzer(
            enabled = { live },
            busy = { busyCheck() },
            playing = { playingNow },
            onFrame = { frameHandler(it) },
            // Debug builds keep the last frame sent, to check the box crop with adb.
            debugFrame = if (BuildConfig.DEBUG) { jpeg -> File(context.cacheDir, "last_live_frame.jpg").writeBytes(jpeg) } else null,
        )
    }
    LaunchedEffect(liveMode) { if (liveMode) analyzer.reset() }

    var busy by remember { mutableStateOf(false) }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                ).build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { it.setAnalyzer(executor, analyzer) }

        val future = ProcessCameraProvider.getInstance(context)
        val bind = object : Runnable {
            override fun run() {
                val viewPort = previewView.viewPort
                if (viewPort == null) { // preview not laid out yet
                    previewView.post(this)
                    return
                }
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                provider.unbindAll()
                try {
                    // A shared viewport makes analysis frames and photos cover exactly what is
                    // visible on screen, so the box fractions map 1:1 to image pixels.
                    val group = UseCaseGroup.Builder().setViewPort(viewPort)
                        .addUseCase(preview).addUseCase(imageCapture).addUseCase(analysis).build()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, group)
                } catch (e: Exception) {
                    // Some very old devices cannot run 3 camera use cases at once: keep photo capture working.
                    provider.unbindAll()
                    val group = UseCaseGroup.Builder().setViewPort(viewPort).addUseCase(preview).addUseCase(imageCapture).build()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, group)
                }
            }
        }
        future.addListener(bind, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { future.get().unbindAll() }
            executor.shutdown()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val h = maxHeight
        AndroidView({ previewView }, Modifier.fillMaxSize())

        // Dim everything outside the scan box, then draw the box with corner marks.
        val boxColor = if (liveReading) Cyan else Color.White
        Canvas(Modifier.fillMaxSize()) {
            val rect = RoundRect(
                left = size.width * ScanBox.LEFT, top = size.height * ScanBox.TOP,
                right = size.width * ScanBox.RIGHT, bottom = size.height * ScanBox.BOTTOM,
                cornerRadius = CornerRadius(22.dp.toPx()),
            )
            val dim = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
                addRoundRect(rect)
            }
            drawPath(dim, Color.Black.copy(alpha = 0.55f))
            drawRoundRect(boxColor.copy(alpha = 0.7f), Offset(rect.left, rect.top), GeoSize(rect.width, rect.height),
                CornerRadius(22.dp.toPx()), style = Stroke(2.dp.toPx()))
            val len = 34.dp.toPx()
            val w = 6.dp.toPx()
            listOf(
                Triple(Offset(rect.left, rect.top), 1f, 1f), Triple(Offset(rect.right, rect.top), -1f, 1f),
                Triple(Offset(rect.left, rect.bottom), 1f, -1f), Triple(Offset(rect.right, rect.bottom), -1f, -1f),
            ).forEach { (corner, dx, dy) ->
                drawLine(boxColor, corner, Offset(corner.x + dx * len, corner.y), w, StrokeCap.Round)
                drawLine(boxColor, corner, Offset(corner.x, corner.y + dy * len), w, StrokeCap.Round)
            }
        }

        // Top bar: server status (left), Live toggle + Settings (right).
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            val (dot, label) = when (server) {
                ServerStatus.ONLINE -> Color(0xFF34D399) to s.serverOnline
                ServerStatus.OFFLINE -> Color(0xFFF87171) to s.serverOffline
                ServerStatus.CHECKING -> Color(0xFFFDE68A) to s.serverChecking
            }
            Row(
                Modifier.background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50)).clickable(onClick = onRetryServer)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).background(dot, CircleShape))
                Spacer(Modifier.size(8.dp))
                Text(label, color = Color.White, fontSize = 14.sp)
            }
            Spacer(Modifier.weight(1f))
            FilterChip(
                selected = liveMode,
                onClick = { onToggleLive(!liveMode) },
                label = { Text(if (liveMode) s.liveOn else s.liveOff, fontSize = 16.sp) },
                leadingIcon = { Icon(Icons.Filled.Bolt, null) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Color.Black.copy(alpha = 0.5f), labelColor = Color.White, iconColor = Color.White,
                    selectedContainerColor = Color(0xFFFBBF24), selectedLabelColor = Color.Black,
                    selectedLeadingIconColor = Color.Black,
                ),
                modifier = Modifier.height(44.dp),
            )
            Spacer(Modifier.size(6.dp))
            IconButton(onClick = onSettings, modifier = Modifier.size(48.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape)) {
                Icon(Icons.Filled.Settings, contentDescription = s.settings, tint = Color.White)
            }
        }

        // Read-out panel, just below the box.
        Column(
            Modifier
                .offset(y = h * ScanBox.BOTTOM + 10.dp)
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .heightIn(max = h * 0.2f)
                .background(Color.White.copy(alpha = 0.94f), RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    liveReading -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.5.dp, color = Violet)
                    playing -> Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = Violet)
                    else -> Icon(Icons.Filled.Bolt, null, tint = Violet)
                }
                Spacer(Modifier.size(8.dp))
                Text(
                    when {
                        liveReading -> s.liveReadingNow
                        liveSpokeIn != null && liveText.isNotBlank() -> "${s.spokeIn}: ${String.format(Locale.US, "%.1f", liveSpokeIn)} s"
                        else -> s.boxHint
                    },
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (liveMode && liveText.isNotBlank()) {
                Text(
                    liveText, fontSize = 21.sp, lineHeight = 30.sp, color = Color(0xFF1C1B1F),
                    modifier = Modifier.padding(top = 6.dp).verticalScroll(rememberScrollState()),
                )
            }
        }

        // Bottom row: gallery (left), shutter (center).
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp, vertical = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
                Box(
                    Modifier.size(60.dp).background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(16.dp))
                        .border(2.dp, Color.White, RoundedCornerShape(16.dp)).clickable(onClick = onGallery),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.PhotoLibrary, contentDescription = s.pickGallery, tint = Color.White, modifier = Modifier.size(30.dp))
                }
                Text(s.gallery, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(84.dp).background(Color.White, CircleShape).border(6.dp, Violet, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (busy) {
                    CircularProgressIndicator(color = Violet)
                } else {
                    IconButton(
                        onClick = {
                            busy = true
                            val file = File(context.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
                            imageCapture.takePicture(
                                ImageCapture.OutputFileOptions.Builder(file).build(),
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                        busy = false
                                        onCaptured(Uri.fromFile(file))
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        busy = false
                                    }
                                },
                            )
                        },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Icon(Icons.Filled.PhotoCamera, contentDescription = s.capture, tint = Violet, modifier = Modifier.size(40.dp))
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            // Stop: silence the voice at once. Red while speaking or reading.
            val canStop = playing || liveReading
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
                Box(
                    Modifier.size(60.dp)
                        .background(if (canStop) Danger else Color.White.copy(alpha = 0.18f), RoundedCornerShape(16.dp))
                        .border(2.dp, if (canStop) Danger else Color.White.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                        .clickable(onClick = onStopSpeech), // always tappable: a tap between sentences is never lost
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = s.stop, tint = Color.White.copy(alpha = if (canStop) 1f else 0.6f), modifier = Modifier.size(32.dp))
                }
                Text(s.stop, color = Color.White.copy(alpha = if (canStop) 1f else 0.6f), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
