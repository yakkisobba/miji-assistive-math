package com.miji.assistive_math.ml

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.core.graphics.scale

object SimpleSymbolPreprocessor {

    private const val TAG = "SimpleSymbolPreprocessor"

    fun bitmapToModelInput(symbolBitmap: Bitmap, img_size: Int): FloatArray {
        val prepared = prepareToMatchTraining(symbolBitmap, img_size)
        return bitmapToFloatArray(prepared, img_size)
    }

    fun preprocessToDebug32(symbolBitmap: Bitmap, img_size: Int): Bitmap {
        return prepareToMatchTraining(symbolBitmap, img_size)
    }

    private fun prepareToMatchTraining(bitmap: Bitmap, img_size: Int): Bitmap {
        val resized = bitmap.scale(img_size, img_size)

        // Log pixel stats if needed
//        logPixelStats(resized)

        return resized
    }

    private fun bitmapToFloatArray(bitmap: Bitmap, size: Int): FloatArray {
        val input = FloatArray(1 * 1 * size * size)
        var index = 0

        for (y in 0 until size) {
            for (x in 0 until size) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val gray = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                input[index++] = (gray - 0.5f) / 0.5f
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
        Log.d(TAG, "48x48 stats: dark=$darkCount, light=$lightCount")
    }
}