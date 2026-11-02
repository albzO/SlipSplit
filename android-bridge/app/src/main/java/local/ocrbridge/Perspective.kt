package local.ocrbridge

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.hypot
import kotlin.math.roundToInt

private const val PADDING = 0.04f

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

    /** Moves each side outward, so dewarping sees the paper's edge and nothing printed is cut. */
    fun padded(corners: FloatArray, width: Int, height: Int): FloatArray {
        fun x(i: Int) = corners[(i + 4) % 4 * 2]
        fun y(i: Int) = corners[(i + 4) % 4 * 2 + 1]
        val shortest = (0..3).minOf { hypot(x(it + 1) - x(it), y(it + 1) - y(it)) }
        val margin = shortest * PADDING
        val moved = FloatArray(8)
        for (i in 0..3) {
            val before = hypot(x(i) - x(i - 1), y(i) - y(i - 1))
            val after = hypot(x(i + 1) - x(i), y(i + 1) - y(i))
            val ax = (y(i) - y(i - 1)) / before; val ay = -(x(i) - x(i - 1)) / before
            val bx = (y(i + 1) - y(i)) / after; val by = -(x(i + 1) - x(i)) / after
            // Where the two moved sides meet; nearly parallel sides would shoot off the page.
            val scale = margin / (1 + ax * bx + ay * by).coerceAtLeast(0.2f)
            moved[i * 2] = (x(i) + (ax + bx) * scale).coerceIn(0f, width - 1f)
            moved[i * 2 + 1] = (y(i) + (ay + by) * scale).coerceIn(0f, height - 1f)
        }
        return moved.takeIf(::valid) ?: corners
    }

    fun rectifyPadded(source: Bitmap, corners: FloatArray) =
        rectify(source, padded(corners, source.width, source.height))

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
