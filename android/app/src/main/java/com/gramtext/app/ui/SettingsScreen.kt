package com.gramtext.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gramtext.app.AppLanguage
import com.gramtext.app.LocalStrings
import com.gramtext.app.ServerStatus
import com.gramtext.app.UiState

@Composable
fun SettingsScreen(
    state: UiState,
    onBack: () -> Unit,
    onLanguage: (AppLanguage) -> Unit,
    onAutoRead: (Boolean) -> Unit,
    onSlowSpeech: (Boolean) -> Unit,
    onShowEngine: (Boolean) -> Unit,
    onLiveMode: (Boolean) -> Unit,
    onSaveServer: (String) -> Unit,
    onTestServer: (String) -> Unit,
) {
    val s = LocalStrings.current
    var url by remember(state.serverUrl) { mutableStateOf(state.serverUrl) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(56.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = s.back)
            }
            Text(s.settings, style = MaterialTheme.typography.headlineMedium)
        }

        Text(s.language, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChip(selected = state.language == AppLanguage.HINDI, onClick = { onLanguage(AppLanguage.HINDI) }, label = { Text("हिंदी") })
            FilterChip(selected = state.language == AppLanguage.ENGLISH, onClick = { onLanguage(AppLanguage.ENGLISH) }, label = { Text("English") })
        }
        HorizontalDivider()

        ToggleRow(s.liveSetting, s.liveSettingHint, state.liveMode, onLiveMode)
        ToggleRow(s.autoRead, s.autoReadHint, state.autoRead, onAutoRead)
        ToggleRow("${s.speed}: ${s.slow}", null, state.slowSpeech, onSlowSpeech)
        ToggleRow(s.showEngine, null, state.showEngine, onShowEngine)
        HorizontalDivider()

        Text(s.serverAddress, style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
            supportingText = {
                Text(
                    when (state.server) {
                        ServerStatus.ONLINE -> s.serverOnline
                        ServerStatus.OFFLINE -> s.serverOffline
                        ServerStatus.CHECKING -> s.serverChecking
                    }
                )
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { onTestServer(url) }, modifier = Modifier.weight(1f).height(56.dp)) { Text(s.testConnection) }
            Button(onClick = { onSaveServer(url) }, modifier = Modifier.weight(1f).height(56.dp)) { Text(s.save) }
        }
        Spacer(Modifier.height(8.dp))
        Text(s.disclaimer, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ToggleRow(title: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
