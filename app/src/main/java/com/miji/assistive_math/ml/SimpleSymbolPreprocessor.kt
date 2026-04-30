package com.miji.assistive_math.ml

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log

object SimpleSymbolPreprocessor {

    private const val TAG = "SimpleSymbolPreprocessor"

    fun bitmapToModelInput(symbolBitmap: Bitmap): FloatArray {
        val debug32 = preprocessToDebug32(symbolBitmap)
        return bitmap32ToFloatArray(debug32)
    }

    fun preprocessToDebug32(symbolBitmap: Bitmap): Bitmap {
        // 1. Force crop into black symbol on white background.
        val binary = toBinaryBlackOnWhite(symbolBitmap)

        // 2. Crop tightly around actual ink.
        val tightCrop = cropToInkBoundingBox(binary, padding = 2)

        // 3. Resize to 28x28 and center in 32x32.
        // This is temporary until we visually confirm the crop.
        val centered32 = resizeStretchToCentered32(
            bitmap = tightCrop,
            targetSize = 28
        )

        // 4. Correct stroke amount.
        val corrected32 = adaptiveStrokeCorrection(centered32)

        logPixelStats(corrected32)

        return corrected32
    }

    private fun toBinaryBlackOnWhite(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        val output = Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888
        )

        var darkCount = 0
        var lightCount = 0

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = bitmap.getPixel(x, y)

                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                val gray = ((0.299 * r) + (0.587 * g) + (0.114 * b)).toInt()
                    .coerceIn(0, 255)

                val color = if (gray < 180) {
                    darkCount++
                    Color.BLACK
                } else {
                    lightCount++
                    Color.WHITE
                }

                output.setPixel(x, y, color)
            }
        }

        val total = darkCount + lightCount
        val darkRatio = if (total > 0) darkCount.toFloat() / total.toFloat() else 0f

        return if (darkRatio > 0.70f) {
            Log.d(TAG, "Crop appears inverted. Inverting. darkRatio=$darkRatio")
            invert(output)
        } else {
            output
        }
    }

    private fun cropToInkBoundingBox(bitmap: Bitmap, padding: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        var left = width
        var top = height
        var right = -1
        var bottom = -1

        for (y in 0 until height) {
            for (x in 0 until width) {
                val gray = Color.red(bitmap.getPixel(x, y))

                if (gray < 128) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }

        if (right < left || bottom < top) {
            Log.d(TAG, "No ink found in crop.")
            return bitmap
        }

        left = maxOf(0, left - padding)
        top = maxOf(0, top - padding)
        right = minOf(width - 1, right + padding)
        bottom = minOf(height - 1, bottom + padding)

        val cropWidth = right - left + 1
        val cropHeight = bottom - top + 1

        Log.d(
            TAG,
            "Tight crop: left=$left, top=$top, right=$right, bottom=$bottom, " +
                    "width=$cropWidth, height=$cropHeight"
        )

        return Bitmap.createBitmap(
            bitmap,
            left,
            top,
            cropWidth,
            cropHeight
        )
    }

    private fun resizeStretchToCentered32(
        bitmap: Bitmap,
        targetSize: Int
    ): Bitmap {
        val resized = Bitmap.createScaledBitmap(
            bitmap,
            targetSize,
            targetSize,
            false
        )

        val output = Bitmap.createBitmap(
            32,
            32,
            Bitmap.Config.ARGB_8888
        )

        val canvas = Canvas(output)
        canvas.drawColor(Color.WHITE)

        val left = ((32 - targetSize) / 2).toFloat()
        val top = ((32 - targetSize) / 2).toFloat()

        val paint = Paint()
        paint.isAntiAlias = false
        paint.isFilterBitmap = false

        canvas.drawBitmap(resized, left, top, paint)

        Log.d(
            TAG,
            "Resize stretch centered: original=${bitmap.width}x${bitmap.height}, " +
                    "new=${targetSize}x${targetSize}"
        )

        return output
    }

    private fun adaptiveStrokeCorrection(bitmap: Bitmap): Bitmap {
        var current = bitmap
        var darkCount = countDarkPixels(current)

        Log.d(TAG, "Before stroke correction dark=$darkCount")

        if (darkCount < 150) {
            current = dilateBlackCrossOnce(current)
            darkCount = countDarkPixels(current)
            Log.d(TAG, "After dilation dark=$darkCount")
        }

        if (darkCount > 420) {
            current = erodeBlackOnce(current)
            darkCount = countDarkPixels(current)
            Log.d(TAG, "After erosion dark=$darkCount")
        }

        return current
    }

    private fun dilateBlackCrossOnce(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        val output = Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888
        )

        for (y in 0 until height) {
            for (x in 0 until width) {
                var hasBlackNeighbor = false

                val neighbors = arrayOf(
                    Pair(x, y),
                    Pair(x - 1, y),
                    Pair(x + 1, y),
                    Pair(x, y - 1),
                    Pair(x, y + 1)
                )

                for ((nx, ny) in neighbors) {
                    if (nx < 0 || nx >= width || ny < 0 || ny >= height) {
                        continue
                    }

                    if (Color.red(bitmap.getPixel(nx, ny)) < 128) {
                        hasBlackNeighbor = true
                        break
                    }
                }

                output.setPixel(
                    x,
                    y,
                    if (hasBlackNeighbor) Color.BLACK else Color.WHITE
                )
            }
        }

        return output
    }

    private fun erodeBlackOnce(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        val output = Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888
        )

        for (y in 0 until height) {
            for (x in 0 until width) {
                var allBlack = true

                val neighbors = arrayOf(
                    Pair(x, y),
                    Pair(x - 1, y),
                    Pair(x + 1, y),
                    Pair(x, y - 1),
                    Pair(x, y + 1)
                )

                for ((nx, ny) in neighbors) {
                    if (nx < 0 || nx >= width || ny < 0 || ny >= height) {
                        allBlack = false
                        break
                    }

                    if (Color.red(bitmap.getPixel(nx, ny)) >= 128) {
                        allBlack = false
                        break
                    }
                }

                output.setPixel(
                    x,
                    y,
                    if (allBlack) Color.BLACK else Color.WHITE
                )
            }
        }

        return output
    }

    private fun countDarkPixels(bitmap: Bitmap): Int {
        var darkCount = 0

        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (Color.red(bitmap.getPixel(x, y)) < 128) {
                    darkCount++
                }
            }
        }

        return darkCount
    }

    private fun bitmap32ToFloatArray(bitmap: Bitmap): FloatArray {
        val input = FloatArray(1 * 1 * 32 * 32)

        var index = 0

        for (y in 0 until 32) {
            for (x in 0 until 32) {
                val gray = Color.red(bitmap.getPixel(x, y)).toFloat() / 255.0f

                val normalized = (gray - 0.5f) / 0.5f

                input[index] = normalized
                index++
            }
        }

        return input
    }

    private fun invert(bitmap: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(
            bitmap.width,
            bitmap.height,
            Bitmap.Config.ARGB_8888
        )

        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val gray = Color.red(bitmap.getPixel(x, y))
                val inv = 255 - gray

                output.setPixel(
                    x,
                    y,
                    Color.rgb(inv, inv, inv)
                )
            }
        }

        return output
    }

    private fun logPixelStats(bitmap: Bitmap) {
        val darkCount = countDarkPixels(bitmap)
        val lightCount = bitmap.width * bitmap.height - darkCount

        Log.d(
            TAG,
            "32x32 crop stats: dark=$darkCount, light=$lightCount, width=32, height=32"
        )
    }
}