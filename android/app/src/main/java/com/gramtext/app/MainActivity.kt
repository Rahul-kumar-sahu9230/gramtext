package com.gramtext.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gramtext.app.ui.CameraScreen
import com.gramtext.app.ui.GramTextTheme
import com.gramtext.app.ui.ResultScreen
import com.gramtext.app.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val vm: GramViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GramTextTheme { GramTextApp(vm) } }
    }

    override fun onStop() {
        super.onStop()
        vm.stopAudio()
    }
}

@Composable
private fun GramTextApp(vm: GramViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val strings = stringsFor(state.language)
    val snackbar = remember { SnackbarHostState() }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.onImageSelected(uri)
    }
    val pickFromGallery = {
        gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it.text(strings))
            vm.messageShown()
        }
    }
    BackHandler(enabled = state.screen != Screen.CAMERA) { vm.back() }

    CompositionLocalProvider(LocalStrings provides strings) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            Surface(Modifier.fillMaxSize().padding(padding), color = MaterialTheme.colorScheme.background) {
                when (state.screen) {
                    Screen.CAMERA -> CameraScreen(
                        liveMode = state.liveMode,
                        liveText = state.liveText,
                        liveReading = state.liveReading,
                        playing = state.playing,
                        liveBusy = { vm.liveBusy.get() },
                        onLiveFrame = vm::onLiveFrame,
                        onToggleLive = vm::setLiveMode,
                        liveSpokeIn = state.liveSpokeIn,
                        server = state.server,
                        onRetryServer = { vm.checkServer() },
                        onGallery = pickFromGallery,
                        onSettings = { vm.navigate(Screen.SETTINGS) },
                        onCaptured = { uri -> vm.onImageSelected(uri, fromCamera = true) },
                        onStopSpeech = vm::stopSpeech,
                    )
                    Screen.RESULT -> ResultScreen(
                        state = state,
                        onBack = vm::back,
                        onTextChange = vm::onTextChanged,
                        onSpeak = vm::speak,
                        onSlowSpeech = vm::setSlowSpeech,
                        onNewPhoto = { vm.navigate(Screen.CAMERA) },
                        onFeedback = vm::feedback,
                        onCorrectionAnswer = vm::answerCorrection,
                        onCopied = vm::copied,
                    )
                    Screen.SETTINGS -> SettingsScreen(
                        state = state,
                        onBack = vm::back,
                        onLanguage = vm::setLanguage,
                        onAutoRead = vm::setAutoRead,
                        onSlowSpeech = vm::setSlowSpeech,
                        onShowEngine = vm::setShowEngine,
                        onLiveMode = vm::setLiveMode,
                        onSaveServer = vm::saveServerUrl,
                        onTestServer = { vm.checkServer(it) },
                    )
                }
            }
        }
    }
}
