package local.ocrbridge

import android.graphics.Bitmap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private const val GRID_X = 7
private const val GRID_Y = 13
private const val NODES = GRID_X * GRID_Y
private const val SMOOTHNESS = 0.005
private const val FIT_ROUNDS = 6
private const val OUTLIER_SCALE = 1.5 * 1.48
private const val MIN_SLOPE_NOISE = 0.01
private const val MIN_BOXES = 6
private const val LONG_BOX_RATIO = 2f
private const val TRACE_STEP = 8.0
private const val MAP_STEP = 8
private const val MIN_ROW_SPACING = 0.25
private const val MIN_BEND_HEIGHTS = 0.5
private const val MIN_TILT_DEGREES = 2.0
private const val BEND_COLUMNS = 9
private const val BEND_ROWS = 15

/** Row slope over the page, bilinear on a coarse grid, so a crease can show as a kink. */
internal class SlopeField(
    private val width: Int,
    private val height: Int,
    private val nodes: DoubleArray,
    val referenceX: Double,
) {
    fun slope(x: Double, y: Double): Double {
        val gx = (x / width).coerceIn(0.0, 1.0) * (GRID_X - 1)
        val gy = (y / height).coerceIn(0.0, 1.0) * (GRID_Y - 1)
        val ix = min(gx.toInt(), GRID_X - 2)
        val iy = min(gy.toInt(), GRID_Y - 2)
        val fx = gx - ix
        val fy = gy - iy
        val top = nodes[iy * GRID_X + ix] * (1 - fx) + nodes[iy * GRID_X + ix + 1] * fx
        val bottom = nodes[(iy + 1) * GRID_X + ix] * (1 - fx) + nodes[(iy + 1) * GRID_X + ix + 1] * fx
        return top * (1 - fy) + bottom * fy
    }

    /** Follows the row through (x, y) sideways, returning its y at targetX. */
    fun trace(x: Double, y: Double, targetX: Double): Double {
        val steps = max(ceil(abs(targetX - x) / TRACE_STEP).toInt(), 1)
        val step = (targetX - x) / steps
        var px = x
        var py = y
        repeat(steps) {
            val middle = py + slope(px, py) * step / 2
            py += slope(px + step / 2, middle) * step
            px += step
        }
        return py
    }
}

/** A fitted page: how far rows bend, in text heights, and how far they sit from level. */
internal class RowFit(val field: SlopeField, val bend: Double, val tilt: Double) {
    val needsLevelling get() = bend >= MIN_BEND_HEIGHTS || tilt >= MIN_TILT_DEGREES
}

private class SlopeSample(val x: Double, val y: Double, val slope: Double, val width: Double)

/** Straightens printed rows using only OCR boxes: each long box gives its row's local slope. */
internal object TextLevel {
    fun fit(polygons: List<FloatArray>, width: Int, height: Int): RowFit? {
        val samples = ArrayList<SlopeSample>()
        val heights = ArrayList<Double>()
        for (p in polygons) {
            val run = (p[2] - p[0]) + (p[4] - p[6])
            val wide = run / 2
            val tall = (hypot(p[6] - p[0], p[7] - p[1]) + hypot(p[4] - p[2], p[5] - p[3])) / 2
            if (tall > 0) heights.add(tall.toDouble())
            if (run > 0 && wide >= LONG_BOX_RATIO * tall) {
                val slope = ((p[3] - p[1]) + (p[5] - p[7])) / run
                samples.add(SlopeSample(
                    (p[0] + p[2] + p[4] + p[6]) / 4.0,
                    (p[1] + p[3] + p[5] + p[7]) / 4.0,
                    slope.toDouble(),
                    wide.toDouble(),
                ))
            }
        }
        val textHeight = if (heights.isEmpty()) 0.0 else middle(heights)
        if (samples.size < MIN_BOXES || textHeight <= 0) return null
        val field = solve(samples, width, height)
        val degrees = samples.map { abs(atan(it.slope)) * 180 / PI }
        return RowFit(field, bend(field, samples) / textHeight, quantile(degrees, 0.9))
    }

    private fun solve(samples: List<SlopeSample>, width: Int, height: Int): SlopeField {
        val weights = DoubleArray(samples.size)
        val medianWidth = middle(samples.map { it.width })
        for (i in samples.indices) weights[i] = samples[i].width / medianWidth
        val cells = Array(samples.size) { i -> cell(samples[i], width, height) }
        val penalty = roughness()
        val robust = DoubleArray(samples.size) { 1.0 }
        var nodes = DoubleArray(NODES)
        repeat(FIT_ROUNDS) {
            val normal = Array(NODES) { DoubleArray(NODES) }
            val target = DoubleArray(NODES)
            var total = 0.0
            for (i in samples.indices) {
                val w = weights[i] * robust[i]
                total += w
                for ((a, va) in cells[i]) {
                    target[a] += w * va * samples[i].slope
                    for ((b, vb) in cells[i]) normal[a][b] += w * va * vb
                }
            }
            for (a in 0 until NODES) {
                for (b in 0 until NODES) normal[a][b] += SMOOTHNESS * total * penalty[a][b]
                // Grid corners with no text and no curvature are otherwise undetermined.
                normal[a][a] += 1e-6
            }
            nodes = gauss(normal, target)
            val misses = samples.indices.map { i ->
                abs(cells[i].sumOf { (node, value) -> nodes[node] * value } - samples[i].slope)
            }
            val limit = max(OUTLIER_SCALE * middle(misses), MIN_SLOPE_NOISE)
            for (i in samples.indices) robust[i] = min(1.0, limit / max(misses[i], 1e-9))
        }
        return SlopeField(width, height, nodes, middle(samples.map { it.x }))
    }

    private fun cell(sample: SlopeSample, width: Int, height: Int): List<Pair<Int, Double>> {
        val gx = (sample.x / width).coerceIn(0.0, 1.0) * (GRID_X - 1)
        val gy = (sample.y / height).coerceIn(0.0, 1.0) * (GRID_Y - 1)
        val ix = min(gx.toInt(), GRID_X - 2)
        val iy = min(gy.toInt(), GRID_Y - 2)
        val fx = gx - ix
        val fy = gy - iy
        return listOf(
            iy * GRID_X + ix to (1 - fx) * (1 - fy),
            iy * GRID_X + ix + 1 to fx * (1 - fy),
            (iy + 1) * GRID_X + ix to (1 - fx) * fy,
            (iy + 1) * GRID_X + ix + 1 to fx * fy,
        )
    }

    /** Sum of squared second differences along both grid directions. */
    private fun roughness(): Array<DoubleArray> {
        val penalty = Array(NODES) { DoubleArray(NODES) }
        fun add(a: Int, b: Int, c: Int) {
            val index = intArrayOf(a, b, c)
            val value = doubleArrayOf(1.0, -2.0, 1.0)
            for (i in 0..2) for (j in 0..2) penalty[index[i]][index[j]] += value[i] * value[j]
        }
        for (y in 0 until GRID_Y) for (x in 0 until GRID_X - 2) {
            add(y * GRID_X + x, y * GRID_X + x + 1, y * GRID_X + x + 2)
        }
        for (x in 0 until GRID_X) for (y in 0 until GRID_Y - 2) {
            add(y * GRID_X + x, (y + 1) * GRID_X + x, (y + 2) * GRID_X + x)
        }
        return penalty
    }

    private fun gauss(matrix: Array<DoubleArray>, target: DoubleArray): DoubleArray {
        val size = target.size
        for (column in 0 until size) {
            val pivot = (column until size).maxBy { abs(matrix[it][column]) }
            val row = matrix[pivot]; matrix[pivot] = matrix[column]; matrix[column] = row
            val value = target[pivot]; target[pivot] = target[column]; target[column] = value
            for (below in column + 1 until size) {
                val factor = matrix[below][column] / matrix[column][column]
                if (factor == 0.0) continue
                for (i in column until size) matrix[below][i] -= factor * matrix[column][i]
                target[below] -= factor * target[column]
            }
        }
        val answer = DoubleArray(size)
        for (row in size - 1 downTo 0) {
            var sum = target[row]
            for (i in row + 1 until size) sum -= matrix[row][i] * answer[i]
            answer[row] = sum / matrix[row][row]
        }
        return answer
    }

    /** Largest distance a row departs from the straight line through its two text ends. */
    private fun bend(field: SlopeField, samples: List<SlopeSample>): Double {
        val left = samples.minOf { it.x }
        val right = samples.maxOf { it.x }
        if (right <= left) return 0.0
        val ys = samples.map { it.y }
        var worst = 0.0
        for (row in 0 until BEND_ROWS) {
            val start = field.trace(left, quantile(ys, 0.05 + 0.9 * row / (BEND_ROWS - 1)), field.referenceX)
            val first = field.trace(field.referenceX, start, left)
            val last = field.trace(field.referenceX, start, right)
            for (column in 0 until BEND_COLUMNS) {
                val share = column.toDouble() / (BEND_COLUMNS - 1)
                val y = field.trace(field.referenceX, start, left + share * (right - left))
                worst = max(worst, abs(y - first * (1 - share) - last * share))
            }
        }
        return worst
    }

    /** Remaps pixels so rows are level; null when rows would cross and the fit can't be used. */
    fun flatten(pixels: IntArray, width: Int, height: Int, field: SlopeField): IntArray? {
        val columns = width / MAP_STEP + 2
        val rows = height / MAP_STEP + 2
        val source = Array(columns) { DoubleArray(rows) }
        for (column in 0 until columns) {
            val x = (column * MAP_STEP).toDouble()
            for (row in 0 until rows) source[column][row] = field.trace(field.referenceX, (row * MAP_STEP).toDouble(), x)
            for (row in 1 until rows) {
                if (source[column][row] - source[column][row - 1] < MIN_ROW_SPACING * MAP_STEP) return null
            }
        }
        val output = IntArray(pixels.size)
        for (y in 0 until height) {
            val gy = y.toDouble() / MAP_STEP
            val iy = min(gy.toInt(), rows - 2)
            val fy = gy - iy
            for (x in 0 until width) {
                val gx = x.toDouble() / MAP_STEP
                val ix = min(gx.toInt(), columns - 2)
                val fx = gx - ix
                val top = source[ix][iy] * (1 - fx) + source[ix + 1][iy] * fx
                val bottom = source[ix][iy + 1] * (1 - fx) + source[ix + 1][iy + 1] * fx
                val position = (top * (1 - fy) + bottom * fy).coerceIn(0.0, height - 1.0)
                val above = floor(position).toInt()
                val below = min(above + 1, height - 1)
                output[y * width + x] = mix(pixels[above * width + x], pixels[below * width + x], position - above)
            }
        }
        return output
    }

    private fun mix(a: Int, b: Int, share: Double): Int {
        var color = 0xFF shl 24
        for (shift in 16 downTo 0 step 8) {
            val value = ((a ushr shift) and 255) * (1 - share) + ((b ushr shift) and 255) * share
            color = color or ((value + 0.5).toInt().coerceIn(0, 255) shl shift)
        }
        return color
    }

    private fun middle(values: List<Double>) = quantile(values, 0.5)

    private fun quantile(values: List<Double>, share: Double): Double {
        val sorted = values.sorted()
        val position = share * (sorted.size - 1)
        val low = position.toInt()
        val high = min(low + 1, sorted.size - 1)
        return sorted[low] * (1 - (position - low)) + sorted[high] * (position - low)
    }
}

internal class Reading(val photo: Bitmap, val lines: List<OcrLine>, val receipt: Receipt, val levelled: Boolean)

/** Reads again on levelled pixels when rows stray, unless that loses a total the first read matched. */
internal fun readLevelled(ocr: PpOcr, bitmap: Bitmap, language: ReceiptLanguage): Reading {
    val lines = ocr.recognize(bitmap, language)
    val first = Reading(bitmap, lines, parseReceipt(lines), false)
    val fit = TextLevel.fit(lines.mapNotNull { it.corners }, bitmap.width, bitmap.height)
    if (fit == null || !fit.needsLevelling) return first
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val flat = TextLevel.flatten(pixels, bitmap.width, bitmap.height, fit.field) ?: return first
    val level = Bitmap.createBitmap(flat, bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    val levelLines = ocr.recognize(level, language)
    val second = Reading(level, levelLines, parseReceipt(levelLines), true)
    return if (sumMatches(first.receipt) && !sumMatches(second.receipt)) first else second
}
