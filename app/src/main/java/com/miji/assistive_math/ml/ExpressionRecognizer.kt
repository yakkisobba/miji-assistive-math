package com.miji.assistive_math.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.ArrayDeque
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class ExpressionRecognizer(
    context: Context
) {
    private val classifier = SymbolClassifier(context.applicationContext)

    fun recognizeExpression(bitmap: Bitmap): RecognitionOutput {
        Log.d(TAG, "Input bitmap: width=${bitmap.width}, height=${bitmap.height}")

        // Crop the center area where the equation should be.
        var scanCrop = cropCenterArea(
            bitmap = bitmap,
            widthRatio = 0.931f,
            heightRatio = 0.7f
        )
        val ratio = scanCrop.width / scanCrop.height.toFloat()
        val maxWidth = 720
        scanCrop = scanCrop.scale(maxWidth,(maxWidth*(1/ratio)).toInt())

        Log.d(TAG, "Scan crop: width=${scanCrop.width}, height=${scanCrop.height}")

        // --- BINARY copy (used only for segmentation) ---
        val binary = makeBlackOnWhite(toGrayscale(scanCrop))
        val cleanedBinary = removeBorderConnectedInk(binary)
//        DebugImageSaver.saveBitmap(appContext, binary, "debug_binary_before_cleanup.png")
//        DebugImageSaver.saveBitmap(appContext, cleanedBinary, "debug_binary_after_cleanup.png")
//        Log.d(TAG, "Saved debug binary images for inspection")
        // Crop tightly around the actual expression (binary used for finding ink bounds).
        val expressionBinary = cropToInkBoundingBox(
            bitmap = cleanedBinary,
            padding = 4
        )

//        // Apply the same crop region to the grayscale image so coordinates match.
//        val expressionGrayscale = cropToInkBoundingBoxGrayscale(
//            grayscale = grayscaleCrop,
//            binary = cleanedBinary,
//            padding = 25
//        )

        Log.d(
            TAG,
            "Expression crop: width=${expressionBinary.width}, height=${expressionBinary.height}"
        )

        // Segment symbols using binary image (gives clean connected components).
        val symbolRects = segmentSymbolsByConnectedComponents(expressionBinary)

        Log.d(TAG, "Symbol rects count: ${symbolRects.size}")
        symbolRects.forEachIndexed { i, rect ->
            Log.d(TAG, "Symbol rect $i: $rect")
        }

        val predictions = mutableListOf<PredictionResult>()

        for ((index, rect) in symbolRects.withIndex()) {

            val symbolBitmapBW = cropBitmapWithPadding(
                bitmap = expressionBinary,
                rect = rect,
                padding = 14
            )

            val inputArray = SimpleSymbolPreprocessor.bitmapToModelInput(symbolBitmapBW,48)
            val prediction = classifier.classify(inputArray,48)

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

    // ── Grayscale conversion ───────────────────────────────────────────────────

    /**
     * Converts bitmap to grayscale without binarizing.
     * Preserves smooth pixel values — matches training data appearance.
     */
    private fun toGrayscale(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        val output = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint()
        val colorMatrix = ColorMatrix()
        colorMatrix.setSaturation(0.0f)

        val colorFilter = ColorMatrixColorFilter(colorMatrix)

        paint.colorFilter = colorFilter
        canvas.drawBitmap(bitmap,0F,0F,paint)
        return output
    }

    // ── Grayscale expression crop (mirrors binary crop bounds) ─────────────────

    /**
     * Crops the grayscale image using the same ink bounding box computed from the binary image.
     * This ensures grayscale and binary crops are perfectly aligned.
     */
    private fun cropToInkBoundingBoxGrayscale(
        grayscale: Bitmap,
        binary: Bitmap,
        padding: Int
    ): Bitmap {
        val width = binary.width
        val height = binary.height

        val pixels = IntArray(width * height)
        binary.getPixels(pixels, 0, width, 0, 0, width, height)

        var left = width
        var top = height
        var right = -1
        var bottom = -1

        for (y in 0 until height) {
            for (x in 0 until width) {
                val gray = Color.red(pixels[y * width + x])
                if (gray < 128) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }

        if (right < left || bottom < top) {
            return grayscale
        }

        left = maxOf(0, left - padding)
        top = maxOf(0, top - padding)
        right = minOf(width - 1, right + padding)
        bottom = minOf(height - 1, bottom + padding)

        val cropWidth = right - left + 1
        val cropHeight = bottom - top + 1

        // Clamp to grayscale bitmap dimensions (should match but be safe).
        val safeRight = minOf(left + cropWidth, grayscale.width)
        val safeBottom = minOf(top + cropHeight, grayscale.height)
        val safeWidth = safeRight - left
        val safeHeight = safeBottom - top

        if (safeWidth <= 0 || safeHeight <= 0) return grayscale

        return Bitmap.createBitmap(grayscale, left, top, safeWidth, safeHeight)
    }

    // ── Center crop ────────────────────────────────────────────────────────────

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

        return Bitmap.createBitmap(bitmap, left, top, safeWidth, safeHeight)
    }

    // ── Binarization ───────────────────────────────────────────────────────────


    private fun otsuMethod(numCoroutines: Int,width : Int,height : Int, pixelGrays: IntArray): IntArray {
        //Regular otsu method
        val histogram = IntArray(256)
        val blockSize = ceil(width*height / numCoroutines.toFloat()).toInt()
        runBlocking(Dispatchers.Default) {
            val mutex = Mutex()
            val deferreds = mutableListOf<Job>()

            val windowSize = 15
            val kernelNum = windowSize/2
            for (i in 0 until numCoroutines){
                deferreds.add(
                    async {
                        for (index in i * blockSize until (i + 1) * blockSize) {
                            val x = index % width
                            val y = index / width
                            val pos = x + y * width
                            mutex.withLock {
                                histogram[pixelGrays[pos]]++
                            }
                        }

                    }
                )
            }
            deferreds.joinAll()
        }

        val total = pixelGrays.size
        var totalSum = 0L
        for (i in 0..255) totalSum += i.toLong() * histogram[i].toLong()

        var backgroundSum = 0L
        var backgroundWeight = 0
        var maxVariance = 0.0
        var threshold = 128

        for (i in 0..255) {
            backgroundWeight += histogram[i]
            if (backgroundWeight == 0) continue

            val foregroundWeight = total - backgroundWeight
            if (foregroundWeight == 0) break

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

        val outputPixels = IntArray(total)

        runBlocking(Dispatchers.Default) {
            val mutex = Mutex()
            val deferreds = mutableListOf<Job>()

            val windowSize = 15
            val kernelNum = windowSize/2
            for (i in 0 until numCoroutines){
                deferreds.add(
                    async {
                        for (index in i * blockSize until (i + 1) * blockSize) {
                            val x = index % width
                            val y = index / width
                            val pos = x + y * width
                            outputPixels[pos] =
                                if (pixelGrays[pos] > threshold) Color.WHITE else Color.BLACK
                        }

                    }
                )
            }
            deferreds.joinAll()
        }

        return outputPixels
    }

    private fun nicksMethod(numCoroutines : Int,width : Int,height: Int, pixelGrays: IntArray): IntArray {
        val outputPixels = IntArray(width*height)
        val blockSize = ceil(width*height / numCoroutines.toFloat()).toInt()
        //Begin Nicks Method
        runBlocking(Dispatchers.Default) {
            val mutex = Mutex()
            val deferreds = mutableListOf<Job>()

            val windowSize = 15
            val kernelNum = windowSize/2
            for (i in 0 until numCoroutines){
                deferreds.add(
                    async {
                        for (index in i * blockSize until (i + 1) * blockSize) {
                            val x = index % width
                            val y = index / width
                            var mean = 0.0
                            var sqrs = 0.0
                            val pos = x + y * width
                            for (h in -kernelNum ..kernelNum) {
                                for (w in -kernelNum..kernelNum) {
                                    val tx = (x + w).coerceIn(0, width - 1)
                                    val ty = (y + h).coerceIn(0, height - 1)
                                    val pixel = pixelGrays[tx + ty * width]
                                    mean += pixel
                                    sqrs += pixel.toDouble().pow(2)
                                }
                            }

                            val np = windowSize*windowSize
                            mean /= np

                            val threshold =
                                 -0.1 * sqrt((sqrs - mean.pow(2)) / np) + mean
                            mutex.withLock {
                                outputPixels[pos] =
                                    if (pixelGrays[pos] > threshold) Color.WHITE else Color.BLACK
                            }
                        }

                    }
                )
            }
            deferreds.joinAll()
        }
        return outputPixels
    }
    private fun makeBlackOnWhite(grayscale: Bitmap): Bitmap {
        val width = grayscale.width
        val height = grayscale.height

        val pixels = IntArray(width * height)
        grayscale.getPixels(pixels, 0, width, 0, 0, width, height)
        val pixelGrays = pixels.map { Color.red(it) }.toIntArray()

        //DSCE + Otsu Method based on Mustafa et al. (https://doi.org/10.32604/cmc.2022.019801)
        val meanLocalArr = DoubleArray(width * height)
        val stdLocalArr = DoubleArray(width * height)
        var sumGlobal = 0.0
        var sumSqrGlobal = 0.0

        val numCoroutines = 40
        val blockSize = ceil(width*height / numCoroutines.toFloat()).toInt()

        runBlocking(Dispatchers.Default) {
            val mutex = Mutex()
            val deferreds = mutableListOf<Job>()

            for (i in 0 until numCoroutines){
                deferreds.add(
                    async {
                        for (index in i*blockSize until (min(((i+1)*blockSize),width*height)) ) {
                            val x = index % width
                            val y = index / width
                            var sumLocal = 0.0
                            var sumSqrLocal = 0.0
                            for (h in -1..1) {
                                for (w in -1..1) {
                                    //Calculate 3x3
                                    val tx = (x + w).coerceIn(0, width - 1)
                                    val ty = (y + h).coerceIn(0, height - 1)
                                    val pixel = pixelGrays[tx + ty * width]
                                    sumLocal += pixel
                                    sumSqrLocal += pixel.toFloat().pow(2)
                                }
                            }
                            val mean = sumLocal / 9
                            meanLocalArr[x + y * width] = mean
                            stdLocalArr[x + y * width] =
                                sqrt(sumSqrLocal / 9 - (sumLocal/9).pow(2))
                            mutex.withLock {
                                sumGlobal += pixelGrays[x + y * width]
                                sumSqrGlobal += pixelGrays[x + y * width].toDouble().pow(2)
                            }
                        }
                    }
                )
            }
            deferreds.joinAll()
        }
        val meanGlobal = sumGlobal / (width*height)
        val stdGlobal = sqrt(sumSqrGlobal/ (width*height) - (sumGlobal/(width*height)).pow(2))

        for (i in pixelGrays.indices){
            if (stdLocalArr[i] < stdGlobal && meanLocalArr[i] > meanGlobal) {
                pixelGrays[i] = meanGlobal.toInt()
            }
        }

        val output = createBitmap(width,height, Bitmap.Config.ARGB_8888)
        output.setPixels(nicksMethod(numCoroutines,width,height,pixelGrays), 0, width, 0, 0, width, height)

        return output
    }

    // ── Border ink removal ─────────────────────────────────────────────────────

    private fun removeBorderConnectedInk(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val total = width * height

        val pixels = IntArray(total)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val isBlack = BooleanArray(total)
        for (i in pixels.indices) isBlack[i] = Color.red(pixels[i]) < 128

        val visited = BooleanArray(total)
        val queue = ArrayDeque<Int>()

        fun enqueueIfBlack(index: Int) {
            if (index in 0 until total && isBlack[index] && !visited[index]) {
                visited[index] = true
                queue.add(index)
            }
        }

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
            if (!isBlack[index]) continue
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
        for (i in 0 until total) outputPixels[i] = if (isBlack[i]) Color.BLACK else Color.WHITE

        val output = createBitmap(width, height)
        output.setPixels(outputPixels, 0, width, 0, 0, width, height)
        return output
    }

    // ── Ink bounding box crop ──────────────────────────────────────────────────

    private fun cropToInkBoundingBox(bitmap: Bitmap, padding: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var left = width
        var top = height
        var right = -1
        var bottom = -1

        for (y in 0 until height) {
            for (x in 0 until width) {
                val gray = Color.red(pixels[y * width + x])
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

        Log.d(TAG, "Ink crop bounds: left=$left, top=$top, right=$right, bottom=$bottom")

        return Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)
    }

    // ── Connected component segmentation ──────────────────────────────────────

    private fun segmentSymbolsByConnectedComponents(bitmap: Bitmap): List<Rect> {
        val width = bitmap.width
        val height = bitmap.height
        val total = width * height

        val pixels = IntArray(total)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val isBlack = BooleanArray(total)
        for (i in pixels.indices) isBlack[i] = Color.red(pixels[i]) < 128

        val visited = BooleanArray(total)
        val components = mutableListOf<ComponentBox>()

        for (startIndex in 0 until total) {
            if (!isBlack[startIndex] || visited[startIndex]) continue

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
            Log.d(TAG, "Raw component $index: rect=${component.rect}, area=${component.area}")
            cropBitmapWithPadding(bitmap, component.rect, 4)
//            DebugImageSaver.saveBitmap(appContext, crop, "debug_component_${index}.png")
        }

        Log.d(TAG, "Total raw components detected: ${components.size}")

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
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue
                    val neighborIndex = ny * width + nx
                    if (isBlack[neighborIndex] && !visited[neighborIndex]) {
                        visited[neighborIndex] = true
                        queue.add(neighborIndex)
                    }
                }
            }
        }

        val rect = Rect(minX, minY, maxX + 1, maxY + 1)
        return ComponentBox(rect = rect, area = area)
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

        if (componentWidth < imageWidth * 0.025 && componentHeight < imageHeight * 0.025) {
            return false
        }

        if (componentHeight < imageHeight * 0.01 && componentWidth < imageWidth * 0.20) {
            return false
        }

        if (componentWidth < imageWidth * 0.01 && componentHeight < imageHeight * 0.20) {
            return false
        }

        val minArea = maxOf(150, (imageArea * 0.000025).toInt())
        val maxArea = (imageArea * 0.25).toInt()

        if (component.area < minArea) return false

        if (component.area > maxArea) {
            Log.d(TAG, "Rejected huge component: rect=$rect area=${component.area}")
            return false
        }

        if (componentWidth < 2 || componentHeight < 2) return false

        if (componentWidth > imageWidth * 0.95 && componentHeight < imageHeight * 0.08) {
            Log.d(TAG, "Rejected horizontal border component: rect=$rect")
            return false
        }

        if (componentHeight > imageHeight * 0.95 && componentWidth < imageWidth * 0.08) {
            Log.d(TAG, "Rejected vertical border component: rect=$rect")
            return false
        }

        if (componentWidth > imageWidth * 0.90 && componentHeight > imageHeight * 0.60) {
            Log.d(TAG, "Rejected near-full-image component: rect=$rect")
            return false
        }

        return true
    }

    // ── Rect merging ───────────────────────────────────────────────────────────

    private fun mergeCloseRects(
        rects: List<Rect>,
        imageWidth: Int,
        imageHeight: Int
    ): List<Rect> {
        if (rects.isEmpty()) return rects

        val closeGap = maxOf(3, (minOf(imageWidth, imageHeight) * 0.01f).toInt())
        var currentRects = rects.map { Rect(it) }.toMutableList()
        currentRects.forEachIndexed { i, rect -> Log.d(TAG, "Merged rect $i: $rect") }

        var changed = true

        while (changed) {
            changed = false
            val used = BooleanArray(currentRects.size)
            val newRects = mutableListOf<Rect>()

            for (i in currentRects.indices) {
                if (used[i]) continue
                var current = Rect(currentRects[i])
                used[i] = true

                for (j in i + 1 until currentRects.size) {
                    if (used[j]) continue
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

        val filtered = currentRects.filter { rect ->
            val area = rect.width() * rect.height()
            val minArea = imageWidth * imageHeight * 0.002
            rect.width() >= 8 && rect.height() >= 8 && area >= minArea
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

        val closeSideBySide = horizontalGap <= closeGap && verticalOverlapRatio > 0.20f
        val closeStacked = verticalGap <= closeGap && horizontalOverlapRatio > 0.20f
        val veryClose = horizontalGap <= 3 && verticalGap <= 3

        return closeSideBySide || closeStacked || veryClose
    }

    private fun horizontalGap(a: Rect, b: Rect): Int = when {
        a.right < b.left -> b.left - a.right
        b.right < a.left -> a.left - b.right
        else -> 0
    }

    private fun verticalGap(a: Rect, b: Rect): Int = when {
        a.bottom < b.top -> b.top - a.bottom
        b.bottom < a.top -> a.top - b.bottom
        else -> 0
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

    private fun unionRect(a: Rect, b: Rect): Rect = Rect(
        minOf(a.left, b.left),
        minOf(a.top, b.top),
        maxOf(a.right, b.right),
        maxOf(a.bottom, b.bottom)
    )

    // ── Symbol crop ────────────────────────────────────────────────────────────

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

        if (width <= 0 || height <= 0) return bitmap

        return Bitmap.createBitmap(bitmap, left, top, width, height)
    }

    // ── Expression building ────────────────────────────────────────────────────

    private fun buildExpression(labels: List<String>): String {
        return labels.joinToString("") { labelToToken(it) }
    }

    private fun labelToToken(label: String): String = when (label) {
        "plus" -> "+"
        "minus" -> "-"
        "x" -> "*"
        "slash" -> "/"
        "dot" -> "."
        else -> label
    }

    // ── Data classes ───────────────────────────────────────────────────────────

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