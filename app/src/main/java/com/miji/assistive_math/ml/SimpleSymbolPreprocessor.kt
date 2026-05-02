package com.miji.assistive_math.ml

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log

object SimpleSymbolPreprocessor {

    private const val TAG = "SimpleSymbolPreprocessor"

    fun bitmapToModelInput(symbolBitmap: Bitmap): FloatArray {
        val prepared = prepareToMatchTraining(symbolBitmap)
        return bitmapToFloatArray(prepared)
    }

    fun preprocessToDebug32(symbolBitmap: Bitmap): Bitmap {
        return prepareToMatchTraining(symbolBitmap)
    }

    /**
     * Matches the training pipeline exactly:
     *   Pad(4, fill=white) -> Resize(32x32, bilinear) -> Normalize(mean=0.5, std=0.5)
     *
     * NO binarization. NO stroke correction. NO tight crop.
     * The model was trained on smooth grayscale images — we must match that.
     */
    private fun prepareToMatchTraining(bitmap: Bitmap): Bitmap {
        // Remove padding since training images are 28x28 with no padding
        // Resize directly to 28x28 to match the training input size
        val resized = Bitmap.createScaledBitmap(bitmap, 28, 28, true)

        // Log pixel stats if needed
        logPixelStats(resized)

        return resized
    }

    private fun bitmapToFloatArray(bitmap: Bitmap): FloatArray {
        val input = FloatArray(1 * 1 * 28 * 28)
        var index = 0

        for (y in 0 until 28) {
            for (x in 0 until 28) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val gray = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                input[index++] = (gray - 0.5f) / 0.5f  // Normalize to [-1, 1]
            }
        }
        return input
    }

    private fun logPixelStats(bitmap: Bitmap) {
        var darkCount = 0

        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (Color.red(bitmap.getPixel(x, y)) < 128) {
                    darkCount++
                }
            }
        }

        val lightCount = bitmap.width * bitmap.height - darkCount
        Log.d(TAG, "28x28 stats: dark=$darkCount, light=$lightCount")
    }
}