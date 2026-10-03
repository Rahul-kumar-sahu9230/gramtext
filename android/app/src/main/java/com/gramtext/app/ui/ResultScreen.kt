package com.gramtext.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gramtext.app.LocalStrings
import com.gramtext.app.UiState

@Composable
fun ResultScreen(
    state: UiState,
    onBack: () -> Unit,
    onTextChange: (String) -> Unit,
    onSpeak: () -> Unit,
    onSlowSpeech: (Boolean) -> Unit,
    onNewPhoto: () -> Unit,
    onFeedback: (Boolean) -> Unit,
    onCorrectionAnswer: (Boolean) -> Unit,
    onCopied: () -> Unit,
) {
    val s = LocalStrings.current
    val clipboard = LocalClipboardManager.current

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Box {
            state.preview?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(220.dp).background(Color.Black),
                )
            }
            IconButton(
                onClick = onBack,
                modifier = Modifier.padding(12.dp).size(56.dp).background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50)),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = s.back, tint = Color.White)
            }
        }

        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (state.scanning) {
                Text(s.reading, style = MaterialTheme.typography.titleLarge)
                LinearProgressIndicator(Modifier.fillMaxWidth().height(8.dp))
                return@Column
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.detectedText, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (state.text.isNotBlank()) {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(state.text)); onCopied() }) {
                        Icon(Icons.Filled.ContentCopy, null, Modifier.size(20.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(s.copy)
                    }
                }
            }
            OutlinedTextField(
                value = state.text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                textStyle = TextStyle(fontSize = 24.sp, lineHeight = 36.sp),
                placeholder = { Text(s.editHint) },
                supportingText = { Text(s.editHint) },
            )

            if (state.showEngine && state.engine != null) {
                val engine = if (state.engine == "str") s.engineModel else s.engineGemini
                val conf = state.confidence?.let { " · ${(it * 100).toInt()}%" } ?: ""
                Text("${s.readBy}: $engine$conf", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Button(
                onClick = onSpeak,
                enabled = state.text.isNotBlank() && !state.loadingAudio,
                modifier = Modifier.fillMaxWidth().height(76.dp),
                shape = RoundedCornerShape(22.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (state.playing) Danger else Violet),
            ) {
                when {
                    state.loadingAudio -> {
                        CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 3.dp)
                        Spacer(Modifier.size(12.dp))
                        Text(s.preparingAudio, style = MaterialTheme.typography.titleMedium)
                    }
                    state.playing -> {
                        Icon(Icons.Filled.Stop, null, Modifier.size(34.dp))
                        Spacer(Modifier.size(12.dp))
                        Text(s.stop, style = MaterialTheme.typography.titleLarge)
                    }
                    else -> {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, null, Modifier.size(34.dp))
                        Spacer(Modifier.size(12.dp))
                        Text(s.readAloud, style = MaterialTheme.typography.titleLarge)
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(s.speed, style = MaterialTheme.typography.bodyLarge)
                FilterChip(selected = state.slowSpeech, onClick = { onSlowSpeech(true) }, label = { Text(s.slow) })
                FilterChip(selected = !state.slowSpeech, onClick = { onSlowSpeech(false) }, label = { Text(s.normal) })
            }

            OutlinedButton(onClick = onNewPhoto, modifier = Modifier.fillMaxWidth().height(60.dp), shape = RoundedCornerShape(18.dp)) {
                Icon(Icons.Filled.PhotoCamera, null)
                Spacer(Modifier.size(10.dp))
                Text(s.scanAgain)
            }

            if (state.text.isNotBlank() && !state.feedbackGiven) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(s.wasCorrect, style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = { onFeedback(true) },
                                modifier = Modifier.weight(1f).height(60.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Success),
                            ) {
                                Icon(Icons.Filled.ThumbUp, null)
                                Spacer(Modifier.size(8.dp))
                                Text(s.yes)
                            }
                            Button(
                                onClick = { onFeedback(false) },
                                modifier = Modifier.weight(1f).height(60.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Danger),
                            ) {
                                Icon(Icons.Filled.ThumbDown, null)
                                Spacer(Modifier.size(8.dp))
                                Text(s.no)
                            }
                        }
                    }
                }
            }

            Text(s.disclaimer, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (state.askCorrection) {
        AlertDialog(
            onDismissRequest = { onCorrectionAnswer(false) },
            title = { Text(s.sendCorrection) },
            text = { Text(s.sendCorrectionBody) },
            confirmButton = { Button(onClick = { onCorrectionAnswer(true) }) { Text(s.send) } },
            dismissButton = { TextButton(onClick = { onCorrectionAnswer(false) }) { Text(s.dontSend) } },
        )
    }
}
