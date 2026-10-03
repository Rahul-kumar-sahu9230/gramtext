package com.gramtext.app.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Plays the backend's MP3 audio.
 *
 * Streaming: sentences arrive one by one from /api/read and play back-to-back, so speech
 * starts as soon as the first sentence is ready. When the stream ends, all sentences are
 * joined into one file so "Read aloud" replays instantly without the network.
 * All methods must be called on the main thread.
 */
class SpeechPlayer(private val cacheDir: File) {
    private var player: MediaPlayer? = null
    private var cachedText: String? = null
    private val file get() = File(cacheDir, "gramtext_tts.mp3")

    private val queue = ArrayDeque<File>()
    private var joined = ByteArrayOutputStream()
    private var chunkIndex = 0
    private var onState: (Boolean) -> Unit = {}
    private var speed = 1.0f

    fun hasAudioFor(text: String) = cachedText == text && file.exists()

    /** Replay the cached full audio (or a freshly downloaded one via [store]). */
    fun store(text: String, mp3: ByteArray) {
        release()
        file.writeBytes(mp3)
        cachedText = text
    }

    fun play(speed: Float, onDone: () -> Unit) {
        release()
        player = newPlayer(file, speed) { onDone() }
    }

    /* ---------- streaming ---------- */

    fun startStream(speed: Float, onState: (playing: Boolean) -> Unit) {
        clear()
        this.speed = speed
        this.onState = onState
        joined = ByteArrayOutputStream()
        chunkIndex = 0
    }

    fun addChunk(mp3: ByteArray, play: Boolean) {
        joined.write(mp3)
        if (!play) return
        val chunk = File(cacheDir, "gramtext_chunk_${chunkIndex++}.mp3").apply { writeBytes(mp3) }
        queue.addLast(chunk)
        if (player == null) playNext()
    }

    fun endStream(text: String) {
        if (joined.size() > 0) {
            file.writeBytes(joined.toByteArray())
            cachedText = text
        }
    }

    private fun playNext() {
        release()
        val next = queue.removeFirstOrNull()
        if (next == null) {
            onState(false)
            return
        }
        player = try {
            newPlayer(next, speed) { next.delete(); playNext() }
        } catch (e: Exception) {
            next.delete()
            null
        }
        if (player == null) playNext() else onState(true)
    }

    private fun newPlayer(source: File, speed: Float, onDone: () -> Unit) = MediaPlayer().apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        setDataSource(source.absolutePath)
        setOnCompletionListener { onDone() }
        setOnErrorListener { _, _, _ -> onDone(); true }
        prepare()
        playbackParams = playbackParams.setSpeed(speed)
        start()
    }

    fun stop() {
        queue.forEach { it.delete() }
        queue.clear()
        release()
    }

    fun clear() {
        stop()
        cachedText = null
        file.delete()
    }

    private fun release() {
        player?.run {
            setOnCompletionListener(null)
            runCatching { if (isPlaying) stop() }
            release()
        }
        player = null
    }
}
