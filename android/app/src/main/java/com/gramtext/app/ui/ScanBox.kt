package com.gramtext.app.ui

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * The scan box, as fractions of the visible camera area. It is used BOTH to draw the box
 * on screen and to crop every frame/photo before it is sent, so only text inside the box
 * is ever read.
 */
object ScanBox {
    const val LEFT = 0.04f
    const val TOP = 0.10f
    const val RIGHT = 0.96f
    const val BOTTOM = 0.62f

    /** Crop an upright bitmap (already rotated for display) to the box. */
    fun crop(bitmap: Bitmap): Bitmap {
        val left = (bitmap.width * LEFT).toInt()
        val top = (bitmap.height * TOP).toInt()
        val width = (bitmap.width * (RIGHT - LEFT)).toInt().coerceAtLeast(1)
        val height = (bitmap.height * (BOTTOM - TOP)).toInt().coerceAtLeast(1)
        return Bitmap.createBitmap(bitmap, left, top, width.coerceAtMost(bitmap.width - left), height.coerceAtMost(bitmap.height - top))
    }

    /**
     * Map a point given as fractions of the DISPLAYED (rotated) visible area to pixel
     * coordinates in the camera buffer, inside [cropRect] (the visible area in buffer coords).
     */
    fun toBuffer(u: Float, v: Float, rotation: Int, cropRect: Rect): Pair<Int, Int> {
        val (bu, bv) = when (rotation) {
            90 -> v to 1f - u
            180 -> 1f - u to 1f - v
            270 -> 1f - v to u
            else -> u to v
        }
        val x = cropRect.left + (bu * (cropRect.width() - 1)).toInt()
        val y = cropRect.top + (bv * (cropRect.height() - 1)).toInt()
        return x to y
    }
}
