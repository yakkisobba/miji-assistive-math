package com.miji.assistive_math.ml

import android.content.Context
import android.graphics.Bitmap
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor
import org.pytorch.torchvision.TensorImageUtils

class YoloDetector(context: Context) {

    private val model: Module = Module.load(assetFilePath(context, MODEL_ASSET))

    fun getGuidanceDirection(bitmap: Bitmap): String {
        val paperBox = detectPaper(bitmap) ?: return "searching"

        val (x1, y1, x2, y2) = paperBox

        // Paper must cover at least 18% of the frame — rejects tiny/partial detections
        val boxArea   = (x2 - x1) * (y2 - y1)
        val frameArea = INPUT_SIZE.toFloat() * INPUT_SIZE
        if (boxArea < frameArea * MIN_COVERAGE_FRAC) return "searching"

        val paperCx = (x1 + x2) / 2f
        val paperCy = (y1 + y2) / 2f
        val frameCx  = INPUT_SIZE / 2f
        val frameCy  = INPUT_SIZE / 2f
        val deadZone = INPUT_SIZE * DEAD_ZONE_FRAC

        val dx = paperCx - frameCx
        val dy = paperCy - frameCy

        val horiz = when {
            dx >  deadZone -> "right"
            dx < -deadZone -> "left"
            else           -> ""
        }
        val vert = when {
            dy >  deadZone -> "down"
            dy < -deadZone -> "up"
            else           -> ""
        }

        return when {
            horiz.isEmpty() && vert.isEmpty()           -> "hold_still"
            horiz.isNotEmpty() && vert.isNotEmpty()     -> "move_${vert}_${horiz}"
            else                                         -> "move_${horiz.ifEmpty { vert }}"
        }
    }

    fun detectPaper(bitmap: Bitmap): FloatArray? {
        val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val tensor  = TensorImageUtils.bitmapToFloat32Tensor(resized, NORM_MEAN, NORM_STD)

        val rawOutput = model.forward(IValue.from(tensor))
        val outTensor: Tensor = if (rawOutput.isTuple)
            rawOutput.toTuple()[0].toTensor()
        else
            rawOutput.toTensor()

        val data       = outTensor.dataAsFloatArray
        val numAnchors = 8400

        var bestConf = CONF_THRESHOLD
        var bestBox: FloatArray? = null

        for (a in 0 until numAnchors) {
            val conf = data[4 * numAnchors + a]   // class 0 (paper) score
            if (conf < bestConf) continue
            val cx = data[0 * numAnchors + a]
            val cy = data[1 * numAnchors + a]
            val w  = data[2 * numAnchors + a]
            val h  = data[3 * numAnchors + a]
            bestConf = conf
            bestBox  = floatArrayOf(cx - w/2f, cy - h/2f, cx + w/2f, cy + h/2f)
        }

        return bestBox
    }

    companion object {
        private const val MODEL_ASSET      = "symbol_detector.pt"
        private const val INPUT_SIZE       = 640
        private const val CONF_THRESHOLD   = 0.45f
        private const val DEAD_ZONE_FRAC   = 0.20f
        private const val MIN_COVERAGE_FRAC = 0.12f
        private val NORM_MEAN = floatArrayOf(0f, 0f, 0f)
        private val NORM_STD  = floatArrayOf(1f, 1f, 1f)
    }
}