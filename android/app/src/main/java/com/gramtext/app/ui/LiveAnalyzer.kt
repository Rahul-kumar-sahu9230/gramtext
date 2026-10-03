package com.gramtext.app.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max

/**
 * Watches the camera feed and hands over a JPEG of the SCAN BOX ONLY when a new label is
 * held steady inside it:
 *  - every 0.2 s it makes a 32x24 grayscale "signature" of the box area,
 *  - steady  = the box content barely changed since the last check (camera not moving),
 *  - changed = it differs from the last frame that was sent (a new label),
 *  - never while a previous frame is still being read, never more than once per 0.6 s.
 * Movement or text outside the box is ignored and never sent.
 */
class LiveAnalyzer(
    private val enabled: () -> Boolean,
    private val busy: () -> Boolean,
    private val onFrame: (ByteArray) -> Unit,
    private val debugFrame: ((ByteArray) -> Unit)? = null,
) : ImageAnalysis.Analyzer {
    private var previous: FloatArray? = null
    private var sent: FloatArray? = null
    private var lastCheck = 0L
    private var lastScan = 0L

    override fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (!enabled() || now - lastCheck < CHECK_MS) return
            lastCheck = now

            val signature = signature(image)
            val steady = diff(signature, previous) < STEADY
            previous = signature
            val changed = diff(signature, sent) > CHANGED
            if (!steady || !changed || busy() || now - lastScan < MIN_GAP_MS) return

            sent = signature
            lastScan = now
            val jpeg = boxJpeg(image)
            debugFrame?.invoke(jpeg)
            onFrame(jpeg)
        } catch (e: Exception) {
            // A bad frame must never stop the camera; skip it.
        } finally {
            image.close()
        }
    }

    /** Forget the last label so the same one is read again (e.g. Live switched back on). */
    fun reset() {
        sent = null
    }

    /** 32x24 grayscale samples taken only inside the scan box, in display orientation. */
    private fun signature(image: ImageProxy): FloatArray {
        val plane = image.planes[0] // RGBA_8888
        val buffer = plane.buffer
        val rotation = image.imageInfo.rotationDegrees
        val crop = image.cropRect
        val out = FloatArray(SIG_W * SIG_H)
        for (gy in 0 until SIG_H) {
            val v = ScanBox.TOP + (ScanBox.BOTTOM - ScanBox.TOP) * (gy + 0.5f) / SIG_H
            for (gx in 0 until SIG_W) {
                val u = ScanBox.LEFT + (ScanBox.RIGHT - ScanBox.LEFT) * (gx + 0.5f) / SIG_W
                val (x, y) = ScanBox.toBuffer(u, v, rotation, crop)
                val i = y * plane.rowStride + x * plane.pixelStride
                val r = buffer.get(i).toInt() and 0xFF
                val g = buffer.get(i + 1).toInt() and 0xFF
                val b = buffer.get(i + 2).toInt() and 0xFF
                out[gy * SIG_W + gx] = (r + g + b) / 3f
            }
        }
        return out
    }

    private fun diff(a: FloatArray, b: FloatArray?): Float {
        if (b == null || a.size != b.size) return 255f
        var sum = 0f
        for (i in a.indices) sum += abs(a[i] - b[i])
        return sum / a.size
    }

    /** Visible area (viewport) -> upright -> scan box only -> JPEG, at most 1024 px. */
    private fun boxJpeg(image: ImageProxy): ByteArray {
        val full = image.toBitmap()
        val crop = image.cropRect
        val visible = if (crop.width() < full.width || crop.height() < full.height) {
            Bitmap.createBitmap(full, crop.left, crop.top, crop.width(), crop.height())
        } else full
        val rotation = image.imageInfo.rotationDegrees
        val upright = if (rotation != 0) {
            Bitmap.createBitmap(visible, 0, 0, visible.width, visible.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        } else visible
        var box = ScanBox.crop(upright)
        val scale = minOf(1f, MAX_SIDE.toFloat() / max(box.width, box.height))
        if (scale < 1f) {
            box = Bitmap.createScaledBitmap(box, (box.width * scale).toInt(), (box.height * scale).toInt(), true)
        }
        return ByteArrayOutputStream().also { box.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
    }

    private companion object {
        const val SIG_W = 32
        const val SIG_H = 24
        const val CHECK_MS = 200L
        const val MIN_GAP_MS = 600L
        const val STEADY = 7f
        const val CHANGED = 10f
        const val MAX_SIDE = 1024
    }
}
