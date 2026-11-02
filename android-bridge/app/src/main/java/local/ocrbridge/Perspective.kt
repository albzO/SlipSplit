package local.ocrbridge

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Clockwise corners: top-left, top-right, bottom-right, bottom-left, in image pixels. */
internal object Perspective {
    fun fullImage(width: Int, height: Int) = floatArrayOf(
        0f, 0f, width - 1f, 0f, width - 1f, height - 1f, 0f, height - 1f,
    )

    fun valid(points: FloatArray): Boolean {
        if (points.size != 8 || points.any { !it.isFinite() }) return false
        for (i in 0..3) {
            val a = i * 2; val b = ((i + 1) % 4) * 2; val c = ((i + 2) % 4) * 2
            val ux = points[b] - points[a]; val uy = points[b + 1] - points[a + 1]
            val vx = points[c] - points[b]; val vy = points[c + 1] - points[b + 1]
            if (ux * vy - uy * vx <= 4f || hypot(ux, uy) < 8f) return false
        }
        return true
    }

    fun rectify(source: Bitmap, corners: FloatArray): Bitmap {
        require(valid(corners)) { "请调整四角，避免边框交叉或过小" }
        fun edge(a: Int, b: Int) = hypot(corners[a * 2] - corners[b * 2], corners[a * 2 + 1] - corners[b * 2 + 1])
        val w = maxOf(edge(0, 1), edge(3, 2)) + 1f
        val h = maxOf(edge(0, 3), edge(1, 2)) + 1f
        val scale = minOf(1f, 2400f / maxOf(w, h))
        val width = (w * scale).roundToInt().coerceAtLeast(2)
        val height = (h * scale).roundToInt().coerceAtLeast(2)
        val matrix = Matrix()
        require(matrix.setPolyToPoly(corners, 0, fullImage(width, height), 0, 4)) { "无法矫正该区域" }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawBitmap(source, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
    }
}
