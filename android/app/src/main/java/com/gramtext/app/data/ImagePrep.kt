package com.gramtext.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.gramtext.app.ui.ScanBox
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Load a photo, fix its rotation and shrink it to at most 1280 px before upload.
 * A 12 MP phone photo (~4 MB) becomes ~150 KB, which matters on slow rural networks.
 */
object ImagePrep {
    private const val MAX_SIDE = 1280  // sharp enough for labels; ~150 KB upload

    class Prepared(val jpeg: ByteArray, val preview: Bitmap)

    /** [cropToBox] = true for camera photos: keep only the scan box (what the user aimed at). */
    fun fromUri(context: Context, uri: Uri, cropToBox: Boolean = false): Prepared {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "not an image" }

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE * 2) sample *= 2
        val decoded = resolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalArgumentException("decode failed")

        val rotation = resolver.openInputStream(uri)!!.use {
            ExifInterface(it).rotationDegrees
        }
        var bitmap = rotate(decoded, rotation)
        if (cropToBox) bitmap = ScanBox.crop(bitmap)
        bitmap = shrink(bitmap)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Prepared(out.toByteArray(), bitmap)
    }

    private fun rotate(src: Bitmap, rotation: Int): Bitmap {
        if (rotation == 0) return src
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    private fun shrink(src: Bitmap): Bitmap {
        val scale = minOf(1f, MAX_SIDE.toFloat() / max(src.width, src.height))
        if (scale == 1f) return src
        return Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
    }
}
