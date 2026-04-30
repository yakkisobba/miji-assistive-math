package com.miji.assistive_math.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import java.util.ArrayDeque

class ExpressionRecognizer(
    context: Context
) {
    private val appContext = context.applicationContext
    private val classifier = SymbolClassifier(context.applicationContext)

    fun recognizeExpression(bitmap: Bitmap): RecognitionOutput {
        Log.d(TAG, "Input bitmap: width=${bitmap.width}, height=${bitmap.height}")


        // The equation should be inside the scan frame, so we crop the center first.
        // This removes a lot of background, phone preview borders, paper edges, and shadows.
        val scanCrop = cropCenterArea(
            bitmap = bitmap,
            widthRatio = 0.92f,
            heightRatio = 0.68f
        )

        Log.d(TAG, "Scan crop: width=${scanCrop.width}, height=${scanCrop.height}")

        // Convert to black symbols on white background.
        val binary = makeBlackOnWhite(scanCrop)

        // Remove large dark regions attached to image borders.
        // This helps when the paper edge or shadow is detected as "ink".
        val cleanedBinary = removeBorderConnectedInk(binary)

        // Crop tightly around the actual expression.
        val expressionCrop = cropToInkBoundingBox(
            bitmap = cleanedBinary,
            padding = 25
        )

        Log.d(
            TAG,
            "Expression crop: width=${expressionCrop.width}, height=${expressionCrop.height}"
        )

        // Segment individual symbols.
        val symbolRects = segmentSymbolsByConnectedComponents(expressionCrop)

        Log.d(TAG, "Detected symbol rects: ${symbolRects.size}")
        symbolRects.forEachIndexed { index, rect ->
            Log.d(TAG, "Rect ${index + 1}: $rect")
        }

        val predictions = mutableListOf<PredictionResult>()

        for ((index, rect) in symbolRects.withIndex()) {
            val symbolBitmap = cropBitmapWithPadding(
                bitmap = expressionCrop,
                rect = rect,
                padding = 14
            )

            Log.d(
                TAG,
                "Symbol crop ${index + 1}: " +
                        "width=${symbolBitmap.width}, height=${symbolBitmap.height}, rect=$rect"
            )

            // Save original symbol crop.
            DebugImageSaver.saveBitmap(
                context = appContext,
                bitmap = symbolBitmap,
                fileName = "symbol_${index + 1}_original.png"
            )

            // Save exact 32x32 image going into the model.
            val debug32 = SimpleSymbolPreprocessor.preprocessToDebug32(symbolBitmap)

            DebugImageSaver.saveBitmap(
                context = appContext,
                bitmap = debug32,
                fileName = "symbol_${index + 1}_model_32.png"
            )

            val inputArray = SimpleSymbolPreprocessor.bitmapToModelInput(symbolBitmap)
            val prediction = classifier.classify(inputArray)

            predictions.add(prediction)

            Log.d(
                TAG,
                "Prediction ${index + 1}: ${prediction.label}, " +
                        "conf=${prediction.confidence}, " +
                        "top2=${prediction.secondLabel}, " +
                        "top2conf=${prediction.secondConfidence}, " +
                        "accepted=${prediction.accepted}"
            )
        }

        val labels = predictions.map { it.label }
        val expression = buildExpression(labels)

        Log.d(TAG, "Final labels: $labels")
        Log.d(TAG, "Final expression: $expression")

        return RecognitionOutput(
            labels = labels,
            expression = expression,
            predictions = predictions,
            detectedSymbolCount = symbolRects.size
        )
    }



    private fun cropCenterArea(
        bitmap: Bitmap,
        widthRatio: Float,
        heightRatio: Float
    ): Bitmap {
        val cropWidth = (bitmap.width * widthRatio).toInt()
        val cropHeight = (bitmap.height * heightRatio).toInt()

        val left = ((bitmap.width - cropWidth) / 2).coerceAtLeast(0)
        val top = ((bitmap.height - cropHeight) / 2).coerceAtLeast(0)

        val safeWidth = cropWidth.coerceAtMost(bitmap.width - left)
        val safeHeight = cropHeight.coerceAtMost(bitmap.height - top)

        return Bitmap.createBitmap(
            bitmap,
            left,
            top,
            safeWidth,
            safeHeight
        )
    }

    private fun makeBlackOnWhite(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        val pixels = IntArray(width * height)
        bitmap.getPixels(
            pixels,
            0,
            width,
            0,
            0,
            width,
            height
        )

        val grayValues = IntArray(width * height)

        for (i in pixels.indices) {
            val pixel = pixels[i]

            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)

            // Weighted grayscale is better than simple average.
            val gray = ((0.299 * r) + (0.587 * g) + (0.114 * b)).toInt()
            grayValues[i] = gray.coerceIn(0, 255)
        }

        val otsuThreshold = calculateOtsuThreshold(grayValues)

        // Clamp threshold so shadows/paper edges are less likely to become black.
        // Your previous fixed threshold 160 was too aggressive for camera images.
        val finalThreshold = (otsuThreshold + 10).coerceIn(115, 150)

        Log.d(TAG, "Otsu threshold=$otsuThreshold, final threshold=$finalThreshold")

        val outputPixels = IntArray(width * height)

        var darkCount = 0
        var lightCount = 0

        for (i in grayValues.indices) {
            val gray = grayValues[i]

            if (gray < finalThreshold) {
                outputPixels[i] = Color.BLACK
                darkCount++
            } else {
                outputPixels[i] = Color.WHITE
                lightCount++
            }
        }

        Log.d(TAG, "Binary darkCount=$darkCount, lightCount=$lightCount")

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(
            outputPixels,
            0,
            width,
            0,
            0,
            width,
            height
        )

        return ensureBlackSymbolsWhiteBackground(output)
    }

    private fun calculateOtsuThreshold(grayValues: IntArray): Int {
        val histogram = IntArray(256)

        for (value in grayValues) {
            histogram[value.coerceIn(0, 255)]++
        }

        val total = grayValues.size

        var totalSum = 0L
        for (i in 0..255) {
            totalSum += i.toLong() * histogram[i].toLong()
        }

        var backgroundSum = 0L
        var backgroundWeight = 0

        var maxVariance = 0.0
        var threshold = 128

        for (i in 0..255) {
            backgroundWeight += histogram[i]

            if (backgroundWeight == 0) {
                continue
            }

            val foregroundWeight = total - backgroundWeight

            if (foregroundWeight == 0) {
                break
            }

            backgroundSum += i.toLong() * histogram[i].toLong()

            val backgroundMean = backgroundSum.toDouble() / backgroundWeight.toDouble()
            val foregroundMean =
                (totalSum - backgroundSum).toDouble() / foregroundWeight.toDouble()

            val betweenClassVariance =
                backgroundWeight.toDouble() *
                        foregroundWeight.toDouble() *
                        (backgroundMean - foregroundMean) *
                        (backgroundMean - foregroundMean)

            if (betweenClassVariance > maxVariance) {
                maxVariance = betweenClassVariance
                threshold = i
            }
        }

        return threshold
    }

    private fun ensureBlackSymbolsWhiteBackground(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        val pixels = IntArray(width * height)
        bitmap.getPixels(
            pixels,
            0,
            width,
            0,
            0,
            width,
            height
        )

        var darkCount = 0
        var lightCount = 0

        for (pixel in pixels) {
            val gray = Color.red(pixel)

            if (gray < 128) {
                darkCount++
            } else {
                lightCount++
            }
        }

        // White background should be the majority.
        if (darkCount <= lightCount) {
            return bitmap
        }

        Log.d(TAG, "Image appears inverted. Inverting to black-on-white.")

        val invertedPixels = IntArray(width * height)

        for (i in pixels.indices) {
            val gray = Color.red(pixels[i])
            val inv = 255 - gray
            invertedPixels[i] = Color.rgb(inv, inv, inv)
        }

        val inverted = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        inverted.setPixels(
            invertedPixels,
            0,
            width,
            0,
            0,
            width,
            height
        )

        return inverted
    }

    private fun removeBorderConnectedInk(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val total = width * height

        val pixels = IntArray(total)
        bitmap.getPixels(
            pixels,
            0,
            width,
            0,
            0,
            width,
            height
        )

        val isBlack = BooleanArray(total)

        for (i in pixels.indices) {
            isBlack[i] = Color.red(pixels[i]) < 128
        }

        val visited = BooleanArray(total)
        val queue = ArrayDeque<Int>()

        fun enqueueIfBlack(index: Int) {
            if (index in 0 until total && isBlack[index] && !visited[index]) {
                visited[index] = true
                queue.add(index)
            }
        }

        // Start flood-fill from black pixels touching the image border.
        for (x in 0 until width) {
            enqueueIfBlack(x)
            enqueueIfBlack((height - 1) * width + x)
        }

        for (y in 0 until height) {
            enqueueIfBlack(y * width)
            enqueueIfBlack(y * width + (width - 1))
        }

        var removedCount = 0

        while (queue.isNotEmpty()) {
            val index = queue.removeFirst()

            if (!isBlack[index]) {
                continue
            }

            isBlack[index] = false
            removedCount++

            val x = index % width
            val y = index / width

            if (x > 0) enqueueIfBlack(index - 1)
            if (x < width - 1) enqueueIfBlack(index + 1)
            if (y > 0) enqueueIfBlack(index - width)
            if (y < height - 1) enqueueIfBlack(index + width)
        }

        Log.d(TAG, "Removed border-connected ink pixels: $removedCount")

        val outputPixels = IntArray(total)

        for (i in 0 until total) {
            outputPixels[i] = if (isBlack[i]) Color.BLACK else Color.WHITE
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(
            outputPixels,
            0,
            width,
            0,
            0,
            width,
            height
        )

        return output
    }

    private fun cropToInkBoundingBox(bitmap: Bitmap, padding: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        val pixels = IntArray(width * height)
        bitmap.getPixels(
            pixels,
            0,
            width,
            0,
            0,
            width,
            height
        )

        var left = width
        var top = height
        var right = -1
        var bottom = -1

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val gray = Color.red(pixels[index])

                if (gray < 128) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }

        if (right < left || bottom < top) {
            Log.d(TAG, "No ink found during expression crop.")
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
            "Ink crop bounds: left=$left, top=$top, right=$right, bottom=$bottom"
        )

        return Bitmap.createBitmap(
            bitmap,
            left,
            top,
            cropWidth,
            cropHeight
        )
    }

    private fun segmentSymbolsByConnectedComponents(bitmap: Bitmap): List<Rect> {
        val width = bitmap.width
        val height = bitmap.height
        val total = width * height

        val pixels = IntArray(total)
        bitmap.getPixels(
            pixels,
            0,
            width,
            0,
            0,
            width,
            height
        )

        val isBlack = BooleanArray(total)

        for (i in pixels.indices) {
            isBlack[i] = Color.red(pixels[i]) < 128
        }

        val visited = BooleanArray(total)
        val components = mutableListOf<ComponentBox>()

        for (startIndex in 0 until total) {
            if (!isBlack[startIndex] || visited[startIndex]) {
                continue
            }

            val component = floodFillComponent(
                startIndex = startIndex,
                width = width,
                height = height,
                isBlack = isBlack,
                visited = visited
            )

            if (isUsefulComponent(component, imageWidth = width, imageHeight = height)) {
                components.add(component)
            }
        }

        Log.d(TAG, "Raw useful components: ${components.size}")

        components.forEachIndexed { index, component ->
            Log.d(
                TAG,
                "Component ${index + 1}: " +
                        "rect=${component.rect}, area=${component.area}"
            )
        }

        val initialRects = components.map { it.rect }.sortedBy { it.left }

        val mergedRects = mergeCloseRects(
            rects = initialRects,
            imageWidth = width,
            imageHeight = height
        )

        return mergedRects.sortedBy { it.left }
    }

    private fun floodFillComponent(
        startIndex: Int,
        width: Int,
        height: Int,
        isBlack: BooleanArray,
        visited: BooleanArray
    ): ComponentBox {
        val queue = ArrayDeque<Int>()

        visited[startIndex] = true
        queue.add(startIndex)

        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        var area = 0

        while (queue.isNotEmpty()) {
            val index = queue.removeFirst()

            val x = index % width
            val y = index / width

            area++

            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y

            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) {
                        continue
                    }

                    val nx = x + dx
                    val ny = y + dy

                    if (nx < 0 || nx >= width || ny < 0 || ny >= height) {
                        continue
                    }

                    val neighborIndex = ny * width + nx

                    if (isBlack[neighborIndex] && !visited[neighborIndex]) {
                        visited[neighborIndex] = true
                        queue.add(neighborIndex)
                    }
                }
            }
        }

        // Android Rect uses right/bottom as exclusive.
        val rect = Rect(
            minX,
            minY,
            maxX + 1,
            maxY + 1
        )

        return ComponentBox(
            rect = rect,
            area = area
        )
    }

    private fun isUsefulComponent(
        component: ComponentBox,
        imageWidth: Int,
        imageHeight: Int
    ): Boolean {
        val rect = component.rect
        val imageArea = imageWidth * imageHeight

        val componentWidth = rect.width()
        val componentHeight = rect.height()

        // Reject tiny noise specks.
        if (componentWidth < imageWidth * 0.025 && componentHeight < imageHeight * 0.025) {
            return false
        }

        // Reject very thin horizontal noise lines.
        if (componentHeight < imageHeight * 0.01 && componentWidth < imageWidth * 0.20) {
            return false
        }

        // Reject very thin vertical noise lines.
        if (componentWidth < imageWidth * 0.01 && componentHeight < imageHeight * 0.20) {
            return false
        }

        val minArea = maxOf(150, (imageArea * 0.000025).toInt())
        val maxArea = (imageArea * 0.25).toInt()

        if (component.area < minArea) {
            return false
        }

        if (component.area > maxArea) {
            Log.d(TAG, "Rejected huge component: rect=$rect area=${component.area}")
            return false
        }

        if (componentWidth < 2 || componentHeight < 2) {
            return false
        }

        // Remove very wide border/shadow line.
        if (componentWidth > imageWidth * 0.95 && componentHeight < imageHeight * 0.08) {
            Log.d(TAG, "Rejected horizontal border component: rect=$rect")
            return false
        }

        // Remove very tall border/shadow line.
        if (componentHeight > imageHeight * 0.95 && componentWidth < imageWidth * 0.08) {
            Log.d(TAG, "Rejected vertical border component: rect=$rect")
            return false
        }

        // Remove near full image blobs.
        if (componentWidth > imageWidth * 0.90 && componentHeight > imageHeight * 0.60) {
            Log.d(TAG, "Rejected near-full-image component: rect=$rect")
            return false
        }

        return true
    }

    private fun mergeCloseRects(
        rects: List<Rect>,
        imageWidth: Int,
        imageHeight: Int
    ): List<Rect> {
        if (rects.isEmpty()) {
            return rects
        }

        val closeGap = maxOf(6, (minOf(imageWidth, imageHeight) * 0.015f).toInt())

        var currentRects = rects.map { Rect(it) }.toMutableList()
        var changed = true

        while (changed) {
            changed = false

            val used = BooleanArray(currentRects.size)
            val newRects = mutableListOf<Rect>()

            for (i in currentRects.indices) {
                if (used[i]) {
                    continue
                }

                var current = Rect(currentRects[i])
                used[i] = true

                for (j in i + 1 until currentRects.size) {
                    if (used[j]) {
                        continue
                    }

                    val next = currentRects[j]

                    if (shouldMerge(current, next, closeGap)) {
                        current = unionRect(current, next)
                        used[j] = true
                        changed = true
                    }
                }

                newRects.add(current)
            }

            currentRects = newRects.sortedBy { it.left }.toMutableList()
        }

        // Final filtering after merging.
        val filtered = currentRects.filter { rect ->
            val area = rect.width() * rect.height()
            val minArea = imageWidth * imageHeight * 0.002

            rect.width() >= 8 &&
                    rect.height() >= 8 &&
                    area >= minArea
        }

        Log.d(TAG, "Merged symbol rects: ${filtered.size}")

        return filtered.sortedBy { it.left }
    }

    private fun shouldMerge(a: Rect, b: Rect, closeGap: Int): Boolean {
        val horizontalGap = horizontalGap(a, b)
        val verticalGap = verticalGap(a, b)

        val horizontalOverlap = horizontalOverlap(a, b)
        val verticalOverlap = verticalOverlap(a, b)

        val minWidth = minOf(a.width(), b.width()).coerceAtLeast(1)
        val minHeight = minOf(a.height(), b.height()).coerceAtLeast(1)

        val horizontalOverlapRatio = horizontalOverlap.toFloat() / minWidth.toFloat()
        val verticalOverlapRatio = verticalOverlap.toFloat() / minHeight.toFloat()

        // Merge broken parts of the same symbol:
        // - pieces close left/right with vertical overlap
        // - pieces close top/bottom with horizontal overlap
        val closeSideBySide =
            horizontalGap <= closeGap && verticalOverlapRatio > 0.20f

        val closeStacked =
            verticalGap <= closeGap && horizontalOverlapRatio > 0.20f

        val veryClose =
            horizontalGap <= 3 && verticalGap <= 3

        return closeSideBySide || closeStacked || veryClose
    }

    private fun horizontalGap(a: Rect, b: Rect): Int {
        return when {
            a.right < b.left -> b.left - a.right
            b.right < a.left -> a.left - b.right
            else -> 0
        }
    }

    private fun verticalGap(a: Rect, b: Rect): Int {
        return when {
            a.bottom < b.top -> b.top - a.bottom
            b.bottom < a.top -> a.top - b.bottom
            else -> 0
        }
    }

    private fun horizontalOverlap(a: Rect, b: Rect): Int {
        val left = maxOf(a.left, b.left)
        val right = minOf(a.right, b.right)
        return maxOf(0, right - left)
    }

    private fun verticalOverlap(a: Rect, b: Rect): Int {
        val top = maxOf(a.top, b.top)
        val bottom = minOf(a.bottom, b.bottom)
        return maxOf(0, bottom - top)
    }

    private fun unionRect(a: Rect, b: Rect): Rect {
        return Rect(
            minOf(a.left, b.left),
            minOf(a.top, b.top),
            maxOf(a.right, b.right),
            maxOf(a.bottom, b.bottom)
        )
    }

    private fun cropBitmapWithPadding(
        bitmap: Bitmap,
        rect: Rect,
        padding: Int
    ): Bitmap {
        val left = maxOf(0, rect.left - padding)
        val top = maxOf(0, rect.top - padding)
        val right = minOf(bitmap.width, rect.right + padding)
        val bottom = minOf(bitmap.height, rect.bottom + padding)

        val width = right - left
        val height = bottom - top

        if (width <= 0 || height <= 0) {
            return bitmap
        }

        return Bitmap.createBitmap(
            bitmap,
            left,
            top,
            width,
            height
        )
    }

    private fun buildExpression(labels: List<String>): String {
        return labels.joinToString("") {
            labelToToken(it)
        }
    }

    private fun labelToToken(label: String): String {
        return when (label) {
            "plus" -> "+"
            "minus" -> "-"
            "x" -> "*"
            "slash" -> "/"
            "dot" -> "."
            else -> label
        }
    }

    private data class ComponentBox(
        val rect: Rect,
        val area: Int
    )

    companion object {
        private const val TAG = "ExpressionRecognizer"
    }
}

data class RecognitionOutput(
    val labels: List<String>,
    val expression: String,
    val predictions: List<PredictionResult>,
    val detectedSymbolCount: Int
)