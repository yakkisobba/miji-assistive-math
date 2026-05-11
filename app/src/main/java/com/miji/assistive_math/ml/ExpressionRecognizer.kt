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
import java.util.ArrayDeque
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import kotlin.math.pow
import kotlin.math.sqrt


class ExpressionRecognizer(context: Context) {
    private val appContext = context.applicationContext
    private val classifier = SymbolClassifier(context.applicationContext)

    fun recognizeExpression(bitmap: Bitmap): RecognitionOutput {
        Log.d(TAG, "Input bitmap: width=${bitmap.width}, height=${bitmap.height}")

        var scanCrop = cropCenterArea(bitmap, widthRatio = 0.92f, heightRatio = 0.68f)
        scanCrop = scanCrop.scale(2048, 2048)

        val grayscaleCrop = toGrayscale(scanCrop)
        val binary        = makeBlackOnWhite(grayscaleCrop)
        val cleanedBinary = removeBorderConnectedInk(binary)

        val expressionBinary    = cropToInkBoundingBox(cleanedBinary, padding = 25)
        val expressionGrayscale = cropToInkBoundingBoxGrayscale(grayscaleCrop, cleanedBinary, padding = 25)
        Log.d(TAG, "Expression crop: ${expressionBinary.width}×${expressionBinary.height}")

        val symbolRects = segmentSymbolsByConnectedComponents(expressionBinary)
        Log.d(TAG, "Symbol rects: ${symbolRects.size}")
        symbolRects.forEachIndexed { i, rect ->
            DebugImageSaver.saveBitmap(appContext, cropBitmapWithPadding(expressionBinary, rect, 10), "debug_symbol_rect_$i.png")
        }

        val predictions = mutableListOf<PredictionResult>()
        for ((index, rect) in symbolRects.withIndex()) {
            val symbolBitmapBW = cropBitmapWithPadding(expressionBinary, rect, padding = 14)
            DebugImageSaver.saveBitmap(appContext, symbolBitmapBW, "symbol_${index + 1}_original.png")
            val inputArray = SimpleSymbolPreprocessor.bitmapToModelInput(symbolBitmapBW, 48)
            val prediction = classifier.classify(inputArray, 48)
            predictions.add(prediction)
            Log.d(TAG, "Symbol ${index+1}: ${prediction.label} conf=${prediction.confidence}")
        }

        val labels     = predictions.map { it.label }
        val expression = buildExpression(labels)
        Log.d(TAG, "Expression: $expression  Labels: $labels")

        return RecognitionOutput(labels, expression, predictions, symbolRects.size)
    }

    // ── Grayscale ──────────────────────────────────────────────────────────────

    private fun toGrayscale(bitmap: Bitmap): Bitmap {
        val out = createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val cm  = ColorMatrix().also { it.setSaturation(0f) }
        Canvas(out).drawBitmap(bitmap, 0f, 0f, Paint().also { it.colorFilter = ColorMatrixColorFilter(cm) })
        return out
    }

    // ── Binarization: DSCE + Otsu ──────────────────────────────────────────────

    private fun makeBlackOnWhite(grayscale: Bitmap): Bitmap {
        val width = grayscale.width; val height = grayscale.height; val n = width * height
        val pixels = IntArray(n)
        grayscale.getPixels(pixels, 0, width, 0, 0, width, height)
        val g = IntArray(n) { Color.red(pixels[it]) }

        val meanLocal = FloatArray(n); val stdLocal = FloatArray(n)
        var meanGlobal = 0f

        for (y in 0 until height) {
            val r1 = y * width
            val r0 = if (y > 0) (y - 1) * width else r1
            val r2 = if (y < height - 1) (y + 1) * width else r1
            for (x in 0 until width) {
                val c1 = x
                val c0 = if (x > 0) x - 1 else c1
                val c2 = if (x < width - 1) x + 1 else c1
                meanGlobal += g[c1 + r1]
                val mean = (g[c0+r0]+g[c1+r0]+g[c2+r0]+g[c0+r1]+g[c1+r1]+g[c2+r1]+g[c0+r2]+g[c1+r2]+g[c2+r2]).toFloat()/9f
                val std  = sqrt(((mean-g[c0+r0]).pow(2)+(mean-g[c1+r0]).pow(2)+(mean-g[c2+r0]).pow(2)+
                        (mean-g[c0+r1]).pow(2)+(mean-g[c1+r1]).pow(2)+(mean-g[c2+r1]).pow(2)+
                        (mean-g[c0+r2]).pow(2)+(mean-g[c1+r2]).pow(2)+(mean-g[c2+r2]).pow(2))/9f)
                meanLocal[c1+r1] = mean; stdLocal[c1+r1] = std
            }
        }

        meanGlobal /= n.toFloat()
        var varSum = 0f; for (v in g) varSum += (meanGlobal - v).pow(2)
        val stdGlobal = sqrt(varSum / n.toFloat())

        val histogram = IntArray(256)
        for (i in 0 until n) {
            val bucket = if (stdLocal[i] < stdGlobal && meanLocal[i] > meanGlobal)
                meanGlobal.toInt().coerceIn(0, 255) else g[i].coerceIn(0, 255)
            histogram[bucket]++
        }

        var totalSum = 0L; for (i in 0..255) totalSum += i.toLong() * histogram[i]
        var bgSum = 0L; var bgW = 0; var maxVar = 0.0; var threshold = 128
        for (i in 0..255) {
            bgW += histogram[i]; if (bgW == 0) continue
            val fgW = n - bgW; if (fgW == 0) break
            bgSum += i.toLong() * histogram[i]
            val bgM = bgSum.toDouble()/bgW; val fgM = (totalSum-bgSum).toDouble()/fgW
            val v = bgW.toDouble()*fgW*(bgM-fgM).pow(2)
            if (v > maxVar) { maxVar = v; threshold = i }
        }
        Log.d(TAG, "DSCE+Otsu threshold=$threshold")

        val out = IntArray(n) { if (g[it] < threshold) Color.BLACK else Color.WHITE }
        val result = createBitmap(width, height)
        result.setPixels(out, 0, width, 0, 0, width, height)
        return ensureBlackSymbolsWhiteBackground(result)
    }

    private fun ensureBlackSymbolsWhiteBackground(bitmap: Bitmap): Bitmap {
        val w = bitmap.width; val h = bitmap.height
        val px = IntArray(w * h); bitmap.getPixels(px, 0, w, 0, 0, w, h)
        val dark = px.count { Color.red(it) < 128 }
        if (dark <= px.size / 2) return bitmap
        Log.d(TAG, "Inverted — flipping to black-on-white.")
        val inv = IntArray(px.size) { if (Color.red(px[it]) < 128) Color.WHITE else Color.BLACK }
        val out = createBitmap(w, h); out.setPixels(inv, 0, w, 0, 0, w, h); return out
    }
    // ── Grayscale ink crop ─────────────────────────────────────────────────────

    private fun cropToInkBoundingBoxGrayscale(grayscale: Bitmap, binary: Bitmap, padding: Int): Bitmap {
        val width = binary.width; val height = binary.height
        val px = IntArray(width * height); binary.getPixels(px, 0, width, 0, 0, width, height)
        var left = width; var top = height; var right = -1; var bottom = -1
        for (y in 0 until height) for (x in 0 until width) {
            if (Color.red(px[y * width + x]) < 128) {
                if (x < left) left = x; if (x > right) right = x
                if (y < top) top = y; if (y > bottom) bottom = y
            }
        }
        if (right < left || bottom < top) return grayscale
        left = maxOf(0,left-padding); top = maxOf(0,top-padding)
        right = minOf(width-1,right+padding); bottom = minOf(height-1,bottom+padding)
        val cw = minOf(right-left+1, grayscale.width-left)
        val ch = minOf(bottom-top+1, grayscale.height-top)
        if (cw <= 0 || ch <= 0) return grayscale
        return Bitmap.createBitmap(grayscale, left, top, cw, ch)
    }

    // ── Center crop ────────────────────────────────────────────────────────────

    private fun cropCenterArea(bitmap: Bitmap, widthRatio: Float, heightRatio: Float): Bitmap {
        val cw   = (bitmap.width * widthRatio).toInt().coerceAtMost(bitmap.width)
        val ch   = (bitmap.height * heightRatio).toInt().coerceAtMost(bitmap.height)
        val left = ((bitmap.width - cw) / 2).coerceAtLeast(0)
        val top  = ((bitmap.height - ch) / 2).coerceAtLeast(0)
        return Bitmap.createBitmap(bitmap, left, top, cw, ch)
    }

    // ── Border ink removal ─────────────────────────────────────────────────────

    private fun removeBorderConnectedInk(bitmap: Bitmap): Bitmap {
        val width = bitmap.width; val height = bitmap.height; val total = width * height
        val px = IntArray(total); bitmap.getPixels(px, 0, width, 0, 0, width, height)
        val isBlack = BooleanArray(total) { Color.red(px[it]) < 128 }
        val visited = BooleanArray(total); val queue = ArrayDeque<Int>()
        fun enq(i: Int) { if (i in 0 until total && isBlack[i] && !visited[i]) { visited[i]=true; queue.add(i) } }
        for (x in 0 until width)  { enq(x); enq((height-1)*width+x) }
        for (y in 0 until height) { enq(y*width); enq(y*width+width-1) }
        var removed = 0
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst(); if (!isBlack[i]) continue
            isBlack[i] = false; removed++
            val x = i % width; val y = i / width
            if (x > 0) enq(i-1); if (x < width-1) enq(i+1)
            if (y > 0) enq(i-width); if (y < height-1) enq(i+width)
        }
        Log.d(TAG, "Border ink removed: $removed px")
        val out = IntArray(total) { if (isBlack[it]) Color.BLACK else Color.WHITE }
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, width, 0, 0, width, height); return result
    }

    // ── Ink bounding box crop ──────────────────────────────────────────────────

    private fun cropToInkBoundingBox(bitmap: Bitmap, padding: Int): Bitmap {
        val width = bitmap.width; val height = bitmap.height
        val px = IntArray(width * height); bitmap.getPixels(px, 0, width, 0, 0, width, height)
        var left = width; var top = height; var right = -1; var bottom = -1
        for (y in 0 until height) for (x in 0 until width) {
            if (Color.red(px[y * width + x]) < 128) {
                if (x < left) left=x; if (x > right) right=x
                if (y < top) top=y; if (y > bottom) bottom=y
            }
        }
        if (right < left || bottom < top) { Log.d(TAG,"No ink."); return bitmap }
        left=maxOf(0,left-padding); top=maxOf(0,top-padding)
        right=minOf(width-1,right+padding); bottom=minOf(height-1,bottom+padding)
        return Bitmap.createBitmap(bitmap, left, top, right-left+1, bottom-top+1)
    }

    // ── Connected component segmentation ──────────────────────────────────────

    private fun segmentSymbolsByConnectedComponents(bitmap: Bitmap): List<Rect> {
        val width = bitmap.width; val height = bitmap.height; val total = width * height
        val px = IntArray(total); bitmap.getPixels(px, 0, width, 0, 0, width, height)
        val isBlack = BooleanArray(total) { Color.red(px[it]) < 128 }
        val visited = BooleanArray(total); val components = mutableListOf<ComponentBox>()
        for (start in 0 until total) {
            if (!isBlack[start] || visited[start]) continue
            val c = floodFillComponent(start, width, height, isBlack, visited)
            if (isUsefulComponent(c, width, height)) components.add(c)
        }
        Log.d(TAG, "Raw components: ${components.size}")
        components.forEachIndexed { i, c ->
            DebugImageSaver.saveBitmap(appContext, cropBitmapWithPadding(bitmap, c.rect, 8), "debug_component_$i.png")
        }
        return mergeCloseRects(components.map { it.rect }.sortedBy { it.left }, width, height).sortedBy { it.left }
    }

    private fun floodFillComponent(start: Int, width: Int, height: Int, isBlack: BooleanArray, visited: BooleanArray): ComponentBox {
        val queue = ArrayDeque<Int>(); visited[start]=true; queue.add(start)
        var minX=width; var minY=height; var maxX=-1; var maxY=-1; var area=0
        while (queue.isNotEmpty()) {
            val i=queue.removeFirst(); val x=i%width; val y=i/width
            area++
            if (x<minX) minX=x; if (x>maxX) maxX=x
            if (y<minY) minY=y; if (y>maxY) maxY=y
            for (dy in -1..1) for (dx in -1..1) {
                if (dx==0&&dy==0) continue
                val nx=x+dx; val ny=y+dy
                if (nx<0||nx>=width||ny<0||ny>=height) continue
                val ni=ny*width+nx
                if (isBlack[ni]&&!visited[ni]) { visited[ni]=true; queue.add(ni) }
            }
        }
        return ComponentBox(Rect(minX,minY,maxX+1,maxY+1),area)
    }

    private fun isUsefulComponent(c: ComponentBox, iw: Int, ih: Int): Boolean {
        val r=c.rect; val cw=r.width(); val ch=r.height()
        if (cw<iw*0.025&&ch<ih*0.025) return false
        if (ch<ih*0.01 &&cw<iw*0.20)  return false
        if (cw<iw*0.01 &&ch<ih*0.20)  return false
        val minArea=maxOf(150,(iw*ih*0.000025).toInt())
        if (c.area<minArea) return false
        if (c.area>iw*ih*0.25) return false
        if (cw<2||ch<2) return false
        if (cw>iw*0.95&&ch<ih*0.08) return false
        if (ch>ih*0.95&&cw<iw*0.08) return false
        if (cw>iw*0.90&&ch>ih*0.60) return false
        return true
    }

    // ── Rect merging ───────────────────────────────────────────────────────────

    private fun mergeCloseRects(rects: List<Rect>, iw: Int, ih: Int): List<Rect> {
        if (rects.isEmpty()) return rects
        val closeGap      = maxOf(6, (minOf(iw,ih)*0.015f).toInt())
        val typicalHeight = rects.maxOf { it.height() }
        var cur = rects.map { Rect(it) }.toMutableList()
        var changed = true
        while (changed) {
            changed=false; val used=BooleanArray(cur.size); val nxt=mutableListOf<Rect>()
            for (i in cur.indices) {
                if (used[i]) continue; var r=Rect(cur[i]); used[i]=true
                for (j in i+1 until cur.size) {
                    if (used[j]) continue
                    if (shouldMerge(r,cur[j],closeGap,typicalHeight)) { r=unionRect(r,cur[j]); used[j]=true; changed=true }
                }
                nxt.add(r)
            }
            cur=nxt.sortedBy { it.left }.toMutableList()
        }
        val minArea = iw*ih*0.001
        // A single handwritten symbol shouldn't span more than 30% of the expression width.
        // This catches catastrophic merges where adjacent symbols were pulled together.
        val maxSymbolWidth = (iw * 0.30f).toInt()
        return cur.filter {
            it.width()>=6 && it.height()>=4 && it.width()*it.height()>=minArea && it.width()<maxSymbolWidth
        }.sortedBy { it.left }
    }

    private fun shouldMerge(a: Rect, b: Rect, closeGap: Int, typicalHeight: Int): Boolean {
        val hg=hGap(a,b); val vg=vGap(a,b)
        val ho=hOvlp(a,b); val vo=vOvlp(a,b)
        val minW=minOf(a.width(),b.width()).coerceAtLeast(1)
        val minH=minOf(a.height(),b.height()).coerceAtLeast(1)
        val maxH=maxOf(a.height(),b.height()).coerceAtLeast(1)
        val hoR=ho.toFloat()/minW; val voR=vo.toFloat()/minH; val hR=minH.toFloat()/maxH
        // Lowered from 0.55 → 0.45 so the guard fires more readily for tall operators like +/x
        val fullH = typicalHeight * 0.45f
        // Tight gap for side-by-side: broken strokes gap ~0-8px; adjacent symbols gap ~15px+
        val sideGap = maxOf(4, closeGap / 3)

        // Overlapping / touching components merge unconditionally — these are broken strokes
        // of the same symbol (e.g. the two diagonals of x when they don't quite cross).
        // This must be checked BEFORE the fullH guard so crossing strokes aren't blocked.
        if (hg<=3 && vg<=3) return true

        // Two full-height side-by-side components = separate symbols (e.g. "2" and "x")
        if (a.height()>=fullH && b.height()>=fullH && hg in 1..closeGap && voR>0.3f) return false

        val closeSideBySide = hg<=sideGap && voR>0.20f && hR>=0.3f
        val closeStacked    = vg<=closeGap && hoR>0.20f
        return closeSideBySide || closeStacked
    }


    private fun hGap(a:Rect,b:Rect)  = when{a.right<b.left->b.left-a.right;b.right<a.left->a.left-b.right;else->0}
    private fun vGap(a:Rect,b:Rect)  = when{a.bottom<b.top->b.top-a.bottom;b.bottom<a.top->a.top-b.bottom;else->0}
    private fun hOvlp(a:Rect,b:Rect) = maxOf(0,minOf(a.right,b.right)-maxOf(a.left,b.left))
    private fun vOvlp(a:Rect,b:Rect) = maxOf(0,minOf(a.bottom,b.bottom)-maxOf(a.top,b.top))
    private fun unionRect(a:Rect,b:Rect)=Rect(minOf(a.left,b.left),minOf(a.top,b.top),maxOf(a.right,b.right),maxOf(a.bottom,b.bottom))

    private fun cropBitmapWithPadding(bitmap: Bitmap, rect: Rect, padding: Int): Bitmap {
        val l=maxOf(0,rect.left-padding); val t=maxOf(0,rect.top-padding)
        val r=minOf(bitmap.width,rect.right+padding); val b=minOf(bitmap.height,rect.bottom+padding)
        val w=r-l; val h=b-t
        if (w<=0||h<=0) return bitmap
        return Bitmap.createBitmap(bitmap,l,t,w,h)
    }

    private fun buildExpression(labels: List<String>) = labels.joinToString("") { labelToToken(it) }
    private fun labelToToken(label: String) = when(label){"plus"->"+"; "minus"->"-"; "x"->"*"; "slash"->"/"; "dot"->"."; else->label}

    private data class ComponentBox(val rect: Rect, val area: Int)
    companion object { private const val TAG = "ExpressionRecognizer" }
}

data class RecognitionOutput(
    val labels: List<String>,
    val expression: String,
    val predictions: List<PredictionResult>,
    val detectedSymbolCount: Int
)