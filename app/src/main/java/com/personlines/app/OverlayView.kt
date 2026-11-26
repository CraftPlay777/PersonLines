package com.personlines.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    @Volatile
    private var det: Detection? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun show(d: Detection?) {
        det = d
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val d = det ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        val sx = vw / d.w
        val sy = vh / d.h
        val s = if (d.fit) minOf(sx, sy) else maxOf(sx, sy)
        val dx = (vw - d.w * s) / 2f
        val dy = (vh - d.h * s) / 2f
        val den = resources.displayMetrics.density

        fun mx(x: Float): Float = dx + (if (d.mirror) 1f - x else x) * d.w * s
        fun my(y: Float): Float = dy + y * d.h * s

        paint.strokeWidth = 5f * den
        for (g in d.segs) {
            paint.color = g.color
            canvas.drawLine(mx(g.x1), my(g.y1), mx(g.x2), my(g.y2), paint)
        }

        val h = d.hair
        if (h != null && h.isNotEmpty()) {
            val m = FloatArray(h.size)
            var i = 0
            while (i + 1 < h.size) {
                m[i] = mx(h[i])
                m[i + 1] = my(h[i + 1])
                i += 2
            }
            paint.color = d.hairColor
            paint.strokeWidth = 3.5f * den
            canvas.drawPoints(m, paint)
        }
    }
}
