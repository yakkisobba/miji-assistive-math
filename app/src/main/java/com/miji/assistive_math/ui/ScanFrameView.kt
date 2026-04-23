package com.miji.assistive_math.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.miji.assistive_math.R

class ScanFrameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density      = context.resources.displayMetrics.density
    private val cornerLength = 28f * density
    private val strokeWidth  = 3f  * density
    private val cornerRadius = 10f * density
    private val inset        = 2f  * density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style            = Paint.Style.STROKE
        this.strokeWidth = strokeWidth
        strokeCap        = Paint.Cap.SQUARE
        color            = context.getColor(R.color.accent)
    }

    private val frameRect = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        frameRect.set(inset, inset, w - inset, h - inset)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val l = frameRect.left;  val t = frameRect.top
        val r = frameRect.right; val b = frameRect.bottom
        val cl = cornerLength;   val cr = cornerRadius

        canvas.drawLine(l + cr, t,      l + cl, t,      paint)
        canvas.drawLine(l,      t + cr, l,      t + cl, paint)
        canvas.drawArc(l, t, l + cr*2, t + cr*2, 180f, 90f, false, paint)

        canvas.drawLine(r - cl, t,      r - cr, t,      paint)
        canvas.drawLine(r,      t + cr, r,      t + cl, paint)
        canvas.drawArc(r - cr*2, t, r, t + cr*2, 270f, 90f, false, paint)

        canvas.drawLine(l + cr, b,      l + cl, b,      paint)
        canvas.drawLine(l,      b - cl, l,      b - cr, paint)
        canvas.drawArc(l, b - cr*2, l + cr*2, b, 90f, 90f, false, paint)

        canvas.drawLine(r - cl, b,      r - cr, b,      paint)
        canvas.drawLine(r,      b - cl, r,      b - cr, paint)
        canvas.drawArc(r - cr*2, b - cr*2, r, b, 0f, 90f, false, paint)
    }
}