package local.ocrbridge

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val DET_LIMIT_SIDE = 736
private const val DET_MAX_SIDE = 4000
private const val DET_THRESH = 0.3f
private const val BOX_THRESH = 0.6f
private const val UNCLIP_RATIO = 1.5f
private const val MIN_BOX_SIDE = 3f
private const val REC_HEIGHT = 48
private const val REC_MIN_WIDTH = 320
private const val REC_MAX_WIDTH = 3200
private const val NUM_THREADS = 4

private val DET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
private val DET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
private val REC_MEAN = floatArrayOf(0.5f, 0.5f, 0.5f)
private val REC_STD = floatArrayOf(0.5f, 0.5f, 0.5f)

private class Point(val x: Int, val y: Int)

/** A rotated text box; corners run top-left, top-right, bottom-right, bottom-left. */
private class TextBox(val corners: FloatArray, val width: Float, val height: Float)

/** A line read twice: with every character allowed, and restricted to English. */
private class LineReading(val box: TextBox, val free: Pair<String, Float>, val english: Pair<String, Float>)

/** PP-OCR detection and recognition on ONNX Runtime, without OpenCV. */
class PpOcr(context: Context, tier: String) {
    private val env = OrtEnvironment.getEnvironment()
    private val det: OrtSession
    private val rec: OrtSession
    private val charset: List<String>
    private val englishOnly: BooleanArray

    init {
        val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(NUM_THREADS) }
        val assets = context.assets
        det = env.createSession(assets.open("ppocr/$tier/det.onnx").use { it.readBytes() }, options)
        rec = env.createSession(assets.open("ppocr/$tier/rec.onnx").use { it.readBytes() }, options)
        charset = assets.open("ppocr/$tier/dict.txt").bufferedReader().readLines() + " "
        // Output index 0 is the CTC blank, so characters start at index 1.
        englishOnly = BooleanArray(charset.size + 1) { it == 0 || isEnglishCharacter(charset[it - 1]) }
    }

    fun close() {
        det.close()
        rec.close()
    }

    fun recognize(bitmap: Bitmap, language: ReceiptLanguage): List<OcrLine> {
        val readings = detect(bitmap)
            .sortedWith(compareBy({ it.corners[1] }, { it.corners[0] }))
            .map { readLine(crop(bitmap, it), it) }
        val english = when (language) {
            ReceiptLanguage.ENGLISH -> true
            ReceiptLanguage.CHINESE -> false
            ReceiptLanguage.AUTO -> looksEnglish(readings.map { it.free.first })
        }
        val lines = ArrayList<OcrLine>()
        for (reading in readings) {
            val (text, score) = if (english) reading.english else reading.free
            if (text.isBlank()) continue
            val box = reading.box
            val xs = (0 until 4).map { box.corners[it * 2] }
            val ys = (0 until 4).map { box.corners[it * 2 + 1] }
            val bounds = intArrayOf(
                xs.min().toInt(),
                ys.min().toInt(),
                xs.max().toInt(),
                ys.max().toInt(),
            )
            lines.add(OcrLine(text, bounds, score))
        }
        return lines
    }

    private fun tensorOf(bitmap: Bitmap, width: Int, mean: FloatArray, std: FloatArray): OnnxTensor {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val data = FloatArray(3 * h * width)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val argb = pixels[y * w + x]
                // The models were trained on BGR input.
                val bgr = intArrayOf(argb and 0xFF, (argb shr 8) and 0xFF, (argb shr 16) and 0xFF)
                for (c in 0 until 3) {
                    data[(c * h + y) * width + x] = (bgr[c] / 255f - mean[c]) / std[c]
                }
            }
        }
        val shape = longArrayOf(1, 3, h.toLong(), width.toLong())
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)
    }

    private fun detect(bitmap: Bitmap): List<TextBox> {
        val (w, h) = detSize(bitmap.width, bitmap.height)
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val input = tensorOf(scaled, w, DET_MEAN, DET_STD)
        @Suppress("UNCHECKED_CAST")
        val map = input.use { tensor ->
            det.run(mapOf("x" to tensor)).use { (it[0].value as Array<Array<Array<FloatArray>>>)[0][0] }
        }
        return findBoxes(map, bitmap.width.toFloat() / w, bitmap.height.toFloat() / h)
    }

    private fun detSize(width: Int, height: Int): Pair<Int, Int> {
        val small = min(width, height)
        var ratio = if (small < DET_LIMIT_SIDE) DET_LIMIT_SIDE.toFloat() / small else 1f
        val large = max(width, height) * ratio
        if (large > DET_MAX_SIDE) ratio *= DET_MAX_SIDE / large
        fun snap(side: Int) = max((side * ratio / 32).roundToInt() * 32, 32)
        return snap(width) to snap(height)
    }

    /** Flood-fills the probability map and fits a rotated box to each text region. */
    private fun findBoxes(map: Array<FloatArray>, scaleX: Float, scaleY: Float): List<TextBox> {
        val h = map.size
        val w = map[0].size
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        val boxes = ArrayList<TextBox>()
        fun on(x: Int, y: Int) = x in 0 until w && y in 0 until h && map[y][x] > DET_THRESH
        for (start in 0 until w * h) {
            if (seen[start] || !on(start % w, start / w)) continue
            var top = 0
            stack[top++] = start
            seen[start] = true
            var sum = 0f
            var count = 0
            val edge = ArrayList<Point>()
            while (top > 0) {
                val index = stack[--top]
                val x = index % w
                val y = index / w
                sum += map[y][x]
                count++
                if (!(on(x - 1, y) && on(x + 1, y) && on(x, y - 1) && on(x, y + 1))) {
                    edge.add(Point(x, y))
                }
                for ((nx, ny) in listOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1)) {
                    if (on(nx, ny) && !seen[ny * w + nx]) {
                        seen[ny * w + nx] = true
                        stack[top++] = ny * w + nx
                    }
                }
            }
            if (sum / count < BOX_THRESH) continue
            minAreaBox(edge, scaleX, scaleY)?.let { boxes.add(it) }
        }
        return boxes
    }

    private fun convexHull(points: List<Point>): List<Point> {
        val sorted = points.sortedWith(compareBy({ it.x }, { it.y }))
        fun cross(o: Point, a: Point, b: Point) =
            (a.x - o.x).toLong() * (b.y - o.y) - (a.y - o.y).toLong() * (b.x - o.x)
        fun half(input: List<Point>): List<Point> {
            val hull = ArrayList<Point>()
            for (point in input) {
                while (hull.size >= 2 && cross(hull[hull.size - 2], hull.last(), point) <= 0) {
                    hull.removeAt(hull.size - 1)
                }
                hull.add(point)
            }
            return hull.dropLast(1)
        }
        return half(sorted) + half(sorted.reversed())
    }

    /** Tries each hull edge as a side, keeps the smallest rectangle and unclips it. */
    private fun minAreaBox(edge: List<Point>, scaleX: Float, scaleY: Float): TextBox? {
        val hull = convexHull(edge)
        if (hull.size < 3) return null
        var best: FloatArray? = null
        var bestArea = Float.MAX_VALUE
        for (i in hull.indices) {
            val next = hull[(i + 1) % hull.size]
            val dx = (next.x - hull[i].x) * scaleX
            val dy = (next.y - hull[i].y) * scaleY
            val length = hypot(dx, dy)
            if (length == 0f) continue
            var ux = dx / length
            var uy = dy / length
            if (abs(ux) < abs(uy)) {
                val swap = ux
                ux = -uy
                uy = swap
            }
            if (ux < 0) {
                ux = -ux
                uy = -uy
            }
            val us = hull.map { it.x * scaleX * ux + it.y * scaleY * uy }
            val vs = hull.map { -it.x * scaleX * uy + it.y * scaleY * ux }
            val area = (us.max() - us.min()) * (vs.max() - vs.min())
            if (area < bestArea) {
                bestArea = area
                best = floatArrayOf(ux, uy, us.min(), us.max(), vs.min(), vs.max())
            }
        }
        val (ux, uy, u0, u1) = best ?: return null
        val v0 = best[4]
        val v1 = best[5]
        val rawWidth = u1 - u0
        val rawHeight = v1 - v0
        if (rawWidth + rawHeight == 0f) return null
        val grow = bestArea * UNCLIP_RATIO / (2 * (rawWidth + rawHeight))
        val left = u0 - grow
        val right = u1 + grow
        val upper = v0 - grow
        val lower = v1 + grow
        if (right - left < MIN_BOX_SIDE || lower - upper < MIN_BOX_SIDE) return null
        fun corner(u: Float, v: Float) = floatArrayOf(u * ux - v * uy, u * uy + v * ux)
        val corners = corner(left, upper) + corner(right, upper) +
            corner(right, lower) + corner(left, lower)
        return TextBox(corners, right - left, lower - upper)
    }

    private fun crop(bitmap: Bitmap, box: TextBox): Bitmap {
        val w = max(ceil(box.width).toInt(), 1)
        val h = max(ceil(box.height).toInt(), 1)
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val target = floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat())
        val matrix = Matrix().apply { setPolyToPoly(box.corners, 0, target, 0, 4) }
        Canvas(output).drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return output
    }

    private fun readLine(line: Bitmap, box: TextBox): LineReading {
        val ratio = line.width.toFloat() / line.height
        val width = min(max((REC_HEIGHT * ratio).toInt(), REC_MIN_WIDTH), REC_MAX_WIDTH)
        val resized = min(ceil(REC_HEIGHT * ratio).toInt().coerceAtLeast(1), width)
        val scaled = Bitmap.createScaledBitmap(line, resized, REC_HEIGHT, true)
        val input = tensorOf(scaled, width, REC_MEAN, REC_STD)
        @Suppress("UNCHECKED_CAST")
        val steps = input.use { tensor ->
            rec.run(mapOf("x" to tensor)).use { (it[0].value as Array<Array<FloatArray>>)[0] }
        }
        return LineReading(box, decode(steps, null), decode(steps, englishOnly))
    }

    /** CTC greedy decode; a step whose best guess is disallowed takes the best allowed character. */
    private fun decode(steps: Array<FloatArray>, allowed: BooleanArray?): Pair<String, Float> {
        val text = StringBuilder()
        var scoreSum = 0f
        var scoreCount = 0
        var previous = 0
        for (step in steps) {
            var index = 0
            for (i in step.indices) if (step[i] > step[index]) index = i
            if (allowed != null && !allowed[index]) {
                index = 1
                for (i in 1 until step.size) {
                    if (allowed[i] && (!allowed[index] || step[i] > step[index])) index = i
                }
            }
            // Index 0 is the CTC blank; repeated indices are one character.
            if (index != 0 && index != previous) {
                text.append(charset[index - 1])
                scoreSum += step[index]
                scoreCount++
            }
            previous = index
        }
        return text.toString() to if (scoreCount > 0) scoreSum / scoreCount else 0f
    }
}
