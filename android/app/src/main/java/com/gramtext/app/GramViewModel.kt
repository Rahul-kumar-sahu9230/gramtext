package com.gramtext.app

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gramtext.app.audio.SpeechPlayer
import com.gramtext.app.data.ApiClient
import com.gramtext.app.data.ApiException
import com.gramtext.app.data.ImagePrep
import com.gramtext.app.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class Screen { CAMERA, RESULT, SETTINGS }
enum class ServerStatus { CHECKING, ONLINE, OFFLINE }

/** A message shown in a snackbar. Either a fixed UI string or a server-provided one. */
data class UiMessage(val text: (Strings) -> String, val id: Long = System.nanoTime())

data class UiState(
    val screen: Screen = Screen.CAMERA,
    val language: AppLanguage = AppLanguage.HINDI,
    val server: ServerStatus = ServerStatus.CHECKING,
    val preview: Bitmap? = null,
    val text: String = "",
    val recognizedText: String = "",
    val scanId: Int? = null,
    val engine: String? = null,
    val confidence: Double? = null,
    val scanning: Boolean = false,
    val loadingAudio: Boolean = false,
    val playing: Boolean = false,
    val feedbackGiven: Boolean = false,
    val askCorrection: Boolean = false,
    val autoRead: Boolean = true,
    val slowSpeech: Boolean = false,
    val showEngine: Boolean = false,
    val serverUrl: String = "",
    val liveMode: Boolean = true,
    val liveText: String = "",
    val liveReading: Boolean = false,
    /** Seconds from sending a live frame to the first spoken word (shown on screen). */
    val liveSpokeIn: Double? = null,
    val message: UiMessage? = null,
)

class GramViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val api = ApiClient(prefs)
    private val player = SpeechPlayer(app.cacheDir)
    private var scanJob: Job? = null
    private var ttsJob: Job? = null

    /** True while a live frame is being read; the camera analyzer skips frames meanwhile. */
    val liveBusy = AtomicBoolean(false)
    /** Bumped by the Stop button: audio still arriving for an older read is dropped. */
    private val liveGeneration = AtomicInteger(0)
    private var liveJob: Job? = null
    private var serverJob: Job? = null
    private var lastSpoken = ""

    private val _state = MutableStateFlow(
        UiState(
            language = prefs.language,
            autoRead = prefs.autoRead,
            slowSpeech = prefs.slowSpeech,
            showEngine = prefs.showEngine,
            serverUrl = prefs.serverUrl,
            liveMode = prefs.liveMode,
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        checkServer()
    }

    fun checkServer(url: String = prefs.serverUrl) {
        serverJob?.cancel() // a newer check (e.g. a new URL from Settings) replaces the old one
        _state.update { it.copy(server = ServerStatus.CHECKING) }
        serverJob = viewModelScope.launch {
            // Render's free plan sleeps when idle and takes ~50 s to wake, so keep trying for a while.
            repeat(8) {
                if (api.health(url)) {
                    _state.update { it.copy(server = ServerStatus.ONLINE) }
                    return@launch
                }
                delay(2_000)
            }
            _state.update { it.copy(server = ServerStatus.OFFLINE) }
        }
    }

    fun navigate(screen: Screen) {
        if (screen != Screen.RESULT) stopAudio()
        _state.update { it.copy(screen = screen) }
    }

    /** Back from Result/Settings returns to the live camera; on the camera the system closes the app. */
    fun back() {
        if (_state.value.screen == Screen.RESULT) scanJob?.cancel()
        if (_state.value.screen != Screen.CAMERA) navigate(Screen.CAMERA)
    }

    fun copied() = say { it.copied }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun say(text: (Strings) -> String) = _state.update { it.copy(message = UiMessage(text)) }

    private fun sayError(e: Throwable) = when {
        e is ApiException && e.kind == "network" -> say { it.networkError }
        e is ApiException && e.kind == "timeout" -> say { it.timeoutError }
        else -> {
            val msg = e.message ?: ""
            say { msg }
        }
    }

    /* ---------------- scanning ---------------- */

    /** [fromCamera] = shutter photo: crop to the scan box. Gallery photos are used whole. */
    fun onImageSelected(uri: Uri, fromCamera: Boolean = false) {
        scanJob?.cancel()
        ttsJob?.cancel()
        player.clear()
        scanJob = viewModelScope.launch {
            val prepared = try {
                withContext(Dispatchers.IO) { ImagePrep.fromUri(getApplication(), uri, cropToBox = fromCamera) }
            } catch (e: Exception) {
                say { it.imageError }
                return@launch
            }
            _state.update {
                it.copy(
                    screen = Screen.RESULT, preview = prepared.preview, text = "", recognizedText = "",
                    scanId = null, engine = null, confidence = null, scanning = true,
                    playing = false, feedbackGiven = false, askCorrection = false,
                )
            }
            // One streamed request: text appears and speech starts sentence by sentence.
            val speed = if (_state.value.slowSpeech) 0.8f else 1.0f
            val autoRead = _state.value.autoRead
            player.startStream(speed) { playing -> _state.update { it.copy(playing = playing) } }
            try {
                api.read(prepared.jpeg).collect { ev ->
                    when (ev.optString("type")) {
                        "text" -> _state.update {
                            it.copy(
                                text = ev.optString("text"), recognizedText = ev.optString("text"),
                                engine = ev.optString("engine").ifEmpty { null }, server = ServerStatus.ONLINE,
                            )
                        }
                        "audio" -> {
                            val mp3 = Base64.decode(ev.optString("data"), Base64.DEFAULT)
                            player.addChunk(mp3, play = autoRead)
                        }
                        "error" -> {
                            val detail = ev.optString("detail")
                            say { detail }
                        }
                        "done" -> {
                            val text = ev.optString("text")
                            player.endStream(text.trim())
                            _state.update {
                                it.copy(
                                    text = text, recognizedText = text, scanning = false,
                                    scanId = if (ev.isNull("scan_id")) null else ev.optInt("scan_id"),
                                    engine = ev.optString("engine").ifEmpty { null },
                                    confidence = if (ev.isNull("confidence")) null else ev.optDouble("confidence"),
                                )
                            }
                            if (text.isBlank()) say { it.noText }
                        }
                    }
                }
                _state.update { it.copy(scanning = false) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(scanning = false) }
                if (e is ApiException && e.kind == "network") _state.update { it.copy(server = ServerStatus.OFFLINE) }
                sayError(e)
            }
        }
    }

    /* ---------------- live reading (camera keeps running) ---------------- */

    fun setLiveMode(value: Boolean) {
        prefs.liveMode = value
        if (!value) stopAudio()
        lastSpoken = ""
        _state.update { it.copy(liveMode = value) }
    }

    /**
     * A steady, new camera view. Read it aloud unless it is the label we just read;
     * a new label interrupts whatever is being spoken.
     */
    fun onLiveFrame(jpeg: ByteArray) {
        if (!liveBusy.compareAndSet(false, true)) return
        val generation = liveGeneration.get()
        liveJob = viewModelScope.launch {
            val sentAt = System.currentTimeMillis()
            _state.update { it.copy(liveReading = true) }
            var decided = false
            var skip = false
            var latest = ""
            val speed = if (_state.value.slowSpeech) 0.8f else 1.0f
            try {
                api.read(jpeg).collect { ev ->
                    if (skip || generation != liveGeneration.get()) return@collect // stopped by the user
                    when (ev.optString("type")) {
                        "text" -> {
                            latest = ev.optString("text")
                            if (decided) _state.update { it.copy(liveText = latest) }
                        }
                        "audio" -> {
                            if (!decided) {
                                if (sameLabel(ev.optString("text"), lastSpoken)) {
                                    skip = true
                                    return@collect
                                }
                                decided = true
                                player.startStream(speed) { playing -> _state.update { it.copy(playing = playing) } }
                                val spokeIn = (System.currentTimeMillis() - sentAt) / 1000.0
                                _state.update { it.copy(liveText = latest, liveSpokeIn = spokeIn) }
                            }
                            player.addChunk(Base64.decode(ev.optString("data"), Base64.DEFAULT), play = true)
                        }
                        "done" -> {
                            val text = ev.optString("text").trim()
                            if (text.isEmpty()) {
                                lastSpoken = "" // label taken away: showing it again reads it again
                            } else if (decided) {
                                lastSpoken = text
                                player.endStream(text)
                                _state.update {
                                    it.copy(
                                        liveText = text, text = text, recognizedText = text,
                                        scanId = if (ev.isNull("scan_id")) null else ev.optInt("scan_id"),
                                        engine = ev.optString("engine").ifEmpty { null },
                                        confidence = if (ev.isNull("confidence")) null else ev.optDouble("confidence"),
                                        feedbackGiven = false, server = ServerStatus.ONLINE,
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (e is ApiException && e.kind == "network") {
                    _state.update { it.copy(server = ServerStatus.OFFLINE) }
                    say { it.networkError }
                }
            } finally {
                if (generation == liveGeneration.get()) { // a Stop already reset these
                    _state.update { it.copy(liveReading = false) }
                    liveBusy.set(false)
                }
            }
        }
    }

    fun onTextChanged(text: String) {
        if (_state.value.playing) stopAudio()
        _state.update { it.copy(text = text) }
    }

    /* ---------------- speech ---------------- */

    fun speak() {
        val text = _state.value.text.trim()
        if (text.isEmpty() || _state.value.loadingAudio) return
        if (_state.value.playing) {
            stopAudio()
            return
        }
        if (player.hasAudioFor(text)) {
            startPlayback()
            return
        }
        ttsJob = viewModelScope.launch {
            _state.update { it.copy(loadingAudio = true) }
            try {
                val mp3 = api.tts(text)
                withContext(Dispatchers.IO) { player.store(text, mp3) }
                _state.update { it.copy(loadingAudio = false) }
                startPlayback()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(loadingAudio = false) }
                sayError(e)
            }
        }
    }

    private fun startPlayback() {
        val speed = if (_state.value.slowSpeech) 0.8f else 1.0f
        try {
            player.play(speed) { _state.update { it.copy(playing = false) } }
            _state.update { it.copy(playing = true) }
        } catch (e: Exception) {
            _state.update { it.copy(playing = false) }
            say { it.networkError }
        }
    }

    /**
     * Stop button on the camera screen: silence now, drop the rest of the current read, and
     * do not read the same label again while it stays in front of the camera.
     */
    fun stopSpeech() {
        liveGeneration.incrementAndGet()
        liveJob?.cancel() // cancel the request too, so the next label can be read right away
        liveBusy.set(false)
        _state.update { it.copy(liveReading = false) }
        _state.value.liveText.takeIf { it.isNotBlank() }?.let { lastSpoken = it.trim() }
        stopAudio()
    }

    fun stopAudio() {
        player.stop()
        _state.update { it.copy(playing = false) }
    }

    /* ---------------- feedback (field pilot metrics) ---------------- */

    fun feedback(helpful: Boolean) {
        val s = _state.value
        val edited = s.text.trim() != s.recognizedText.trim()
        if (!helpful && edited) {
            _state.update { it.copy(askCorrection = true) }
            return
        }
        sendFeedback(helpful, null)
    }

    fun answerCorrection(send: Boolean) {
        _state.update { it.copy(askCorrection = false) }
        sendFeedback(false, if (send) _state.value.text.trim() else null)
    }

    private fun sendFeedback(helpful: Boolean, corrected: String?) {
        val scanId = _state.value.scanId
        _state.update { it.copy(feedbackGiven = true) }
        viewModelScope.launch {
            runCatching { api.feedback(scanId, helpful, corrected) }
            say { it.thanks }
        }
    }

    /* ---------------- settings ---------------- */

    fun setLanguage(language: AppLanguage) {
        prefs.language = language
        _state.update { it.copy(language = language) }
    }

    fun setAutoRead(value: Boolean) {
        prefs.autoRead = value
        _state.update { it.copy(autoRead = value) }
    }

    fun setSlowSpeech(value: Boolean) {
        prefs.slowSpeech = value
        _state.update { it.copy(slowSpeech = value) }
    }

    fun setShowEngine(value: Boolean) {
        prefs.showEngine = value
        _state.update { it.copy(showEngine = value) }
    }

    fun saveServerUrl(url: String) {
        prefs.serverUrl = url
        _state.update { it.copy(serverUrl = prefs.serverUrl) }
        say { it.saved }
        checkServer()
    }

    override fun onCleared() {
        player.clear()
    }
}

/** Same label as before? OCR of two camera frames can differ by a character or two. */
internal fun sameLabel(sentence: String, previous: String): Boolean {
    val a = sentence.replace(Regex("""\s+"""), " ").trim().take(120)
    val b = previous.replace(Regex("""\s+"""), " ").trim().take(a.length + 4)
    if (a.isEmpty() || b.isEmpty()) return false
    var prev = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val cur = IntArray(b.length + 1)
        cur[0] = i
        for (j in 1..b.length) {
            cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        prev = cur
    }
    return prev[b.length] <= maxOf(2, Math.round(a.length * 0.2f))
}

