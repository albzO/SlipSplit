package local.ocrbridge

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.Closeable
import java.nio.FloatBuffer
import kotlin.math.atan2

private const val MASK_SIDE = 320
private const val NUM_THREADS = 4
private const val WORK_SIDE = 640.0
private const val MASK_THRESHOLD = 0.5f
private const val MIN_AREA = 0.03
private const val MAX_AREA = 0.97
private const val MIN_FILL = 0.7
private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
private val POLYGON_TOLERANCES = doubleArrayOf(0.01, 0.02, 0.03, 0.05, 0.08, 0.12)
private val CANDIDATE_TOLERANCES = doubleArrayOf(0.003, 0.005, 0.01, 0.02)
private const val MAX_CANDIDATES = 24
// Each side is refitted from the outline along its middle, away from curled or torn corners.
private const val SIDE_MIDDLE = 0.6
// Outline cells this far from a side, as a share of the longer work side, belong to another side.
private const val SIDE_REACH = 0.04
// The refitted side moves out to cover this share of its outline, so bulges are not cut.
private const val SIDE_COVER = 0.97
private const val MIN_SIDE_POINTS = 8
// A side is refitted only when straight outline runs along this share of its middle.
private const val MIN_SIDE_SHARE = 0.3
private const val SIDE_FITS = 3
// Straight lines in the outline: angle steps, the votes a line needs as a share of the shorter
// work side, how many lines are kept, and how close two kept lines may be.
private const val HOUGH_ANGLES = 180
private const val MIN_LINE_VOTES = 0.06
private const val MAX_LINES = 20
private const val LINE_SEPARATION_ANGLE = 6
private const val LINE_SEPARATION_DISTANCE = 12
// Work cells per distance step, so a slightly bent edge still votes for one line.
private const val HOUGH_STEP = 3.0
// Opposite sides lie within this many degrees of each other; adjacent sides further apart than this.
private const val MAX_PARALLEL_TURN = 25.0
private const val MIN_CORNER_TURN = 45.0
// An outline cell this close, in work cells, supports a quad side.
private const val SUPPORT_REACH = 2
// A quad narrower than this share of the shorter work side, or smaller than this share of the
// mask, is a sliver of outline, not the paper.
private const val MIN_QUAD_SIDE = 0.1
private const val MIN_QUAD_AREA = 0.15
// A side counts as running along the outline only for this share of the shorter work side or more.
private const val MIN_SUPPORT_RUN = 0.03
private const val TRIM_ROUNDS = 4
// Points further from the fit than this many median distances, and at least a cell, are dropped.
private const val TRIM_RATIO = 2.5
private const val TRIM_FLOOR = 1.0
// A refitted corner may move this far, as a share of the quad's diagonal, before the fit is distrusted.
private const val MAX_CORNER_SHIFT = 0.15
// A wavy edge, such as a crumpled top, fits at a slant. Opposite sides of paper stay close to
// parallel in a photo, so a fit turned further than this many radians from the opposite side
// keeps the side's own direction.
private const val MAX_SIDE_TURN = 0.15

/** U²-Netp marks the main object in a photo, so paper is found without needing clean edges. */
internal class PaperSegmenter(context: Context) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(NUM_THREADS) }
        val model = context.assets.open("u2netp/u2netp.ort").use { it.readBytes() }
        session = env.createSession(model, options)
    }

    /** A square map of 0..1 values, MASK_SIDE on each side, stretched over the whole photo. */
    fun mask(photo: Bitmap): FloatArray {
        val small = Bitmap.createScaledBitmap(photo, MASK_SIDE, MASK_SIDE, true)
        val pixels = IntArray(MASK_SIDE * MASK_SIDE)
        small.getPixels(pixels, 0, MASK_SIDE, 0, 0, MASK_SIDE, MASK_SIDE)
        if (small !== photo) small.recycle()
        val brightest = pixels.maxOf { maxOf((it shr 16) and 255, (it shr 8) and 255, it and 255) }
            .coerceAtLeast(1).toFloat()
        val data = FloatArray(3 * pixels.size)
        for (index in pixels.indices) {
            val argb = pixels[index]
            data[index] = (((argb shr 16) and 255) / brightest - MEAN[0]) / STD[0]
            data[pixels.size + index] = (((argb shr 8) and 255) / brightest - MEAN[1]) / STD[1]
            data[2 * pixels.size + index] = ((argb and 255) / brightest - MEAN[2]) / STD[2]
        }
        val shape = longArrayOf(1, 3, MASK_SIDE.toLong(), MASK_SIDE.toLong())
        val name = session.inputNames.first()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { input ->
            session.run(mapOf(name to input)).use { result ->
                val output = (result[0] as OnnxTensor).floatBuffer
                val mask = FloatArray(pixels.size) { output.get(it) }
                val low = mask.min()
                val range = (mask.max() - low).coerceAtLeast(1e-6f)
                return FloatArray(mask.size) { (mask[it] - low) / range }
            }
        }
    }

    override fun close() = session.close()
}

/** Finds the paper's corners from the object mask; when it fails the user frames the paper. */
internal object PaperBoundary {
    const val VERSION = "u2netp-v3"

    class Found(val corners: FloatArray, val method: String)

    fun detect(context: Context, photo: Bitmap): Found? {
        val masked = try {
            PaperSegmenter(context).use { quad(it.mask(photo), photo.width, photo.height) }
        } catch (error: Exception) {
            Log.w("PaperBoundary", "Mask detection failed", error)
            null
        }
        return masked?.let { Found(it, "u2netp") }
    }

    /**
     * Four corners from the mask: stretch it back to the photo's shape, keep its largest region,
     * and simplify that region's convex outline to four points.
     */
    fun quad(mask: FloatArray, photoWidth: Int, photoHeight: Int): FloatArray? {
        val scale = WORK_SIDE / maxOf(photoWidth, photoHeight)
        val width = (photoWidth * scale).toInt().coerceAtLeast(2)
        val height = (photoHeight * scale).toInt().coerceAtLeast(2)
        val cells = BooleanArray(width * height)
        // Bilinear sampling with pixel centres aligned, as an image resize does.
        val stepX = MASK_SIDE.toDouble() / width
        val stepY = MASK_SIDE.toDouble() / height
        for (y in 0 until height) {
            val sy = ((y + 0.5) * stepY - 0.5).coerceIn(0.0, MASK_SIDE - 1.0)
            val y0 = sy.toInt().coerceAtMost(MASK_SIDE - 2)
            val fy = sy - y0
            for (x in 0 until width) {
                val sx = ((x + 0.5) * stepX - 0.5).coerceIn(0.0, MASK_SIDE - 1.0)
                val x0 = sx.toInt().coerceAtMost(MASK_SIDE - 2)
                val fx = sx - x0
                val top = mask[y0 * MASK_SIDE + x0] * (1 - fx) + mask[y0 * MASK_SIDE + x0 + 1] * fx
                val bottom = mask[(y0 + 1) * MASK_SIDE + x0] * (1 - fx) + mask[(y0 + 1) * MASK_SIDE + x0 + 1] * fx
                cells[y * width + x] = top * (1 - fy) + bottom * fy > MASK_THRESHOLD
            }
        }
        val region = Outline.largestRegion(cells, width, height) ?: return null
        // Text leaves holes in the mask; only the outer outline is the paper's edge.
        val outline = Outline.largestRegion(fillHoles(region.cells, width, height), width, height)?.edge ?: region.edge
        val area = region.count
        if (area.toDouble() / (width * height) !in MIN_AREA..MAX_AREA) return null
        val hull = Outline.convexHull(region.edge)
        if (hull.size < 3) return null
        // Several ways to reduce the outline to four corners; a stray flap of mask, such as a bag
        // or a second slip, pulls each one differently, so keep the quad that best covers the mask.
        val perimeter = Outline.perimeter(hull)
        val quads = POLYGON_TOLERANCES.map { Outline.simplify(hull, perimeter * it) }.filter { it.size == 4 } +
            listOfNotNull(
                CANDIDATE_TOLERANCES.asSequence().map { Outline.simplify(hull, perimeter * it) }
                    .firstOrNull { it.size <= MAX_CANDIDATES }?.let(Outline::largestQuad),
                Outline.minAreaRect(hull),
            )
        // A hand holding the paper often joins its mask and bends the outline wherever it is.
        // Paper sides are long straight runs of the outline, so the quad whose sides run along
        // the outline furthest, and cross the fewest gaps, is the paper.
        val support = supportMap(outline, width, height)
        val shortest = MIN_QUAD_SIDE * minOf(width, height)
        val candidates = (quads + lineQuads(outline, width, height)).map(::clockwise).filter { quad ->
            (0..3).all { kotlin.math.hypot(quad[(it + 1) % 4].x - quad[it].x, quad[(it + 1) % 4].y - quad[it].y) >= shortest } &&
                Outline.area(quad) >= MIN_QUAD_AREA * area
        }.ifEmpty { quads.map(::clockwise) }
        val chosen = clockwise(candidates.maxBy { supportScore(it, support, width, height) })
        // Each fit moves the corners off the curl, so the next fit sees more of the straight sides.
        var points = chosen
        repeat(SIDE_FITS) { points = straighten(points, outline, maxOf(width, height) * SIDE_REACH) ?: points }
        if (area / Outline.area(points) < MIN_FILL) return null
        return FloatArray(8) { index ->
            val point = points[index / 2]
            if (index % 2 == 0) (point.x * photoWidth / width).toFloat().coerceIn(0f, photoWidth - 1f)
            else (point.y * photoHeight / height).toFloat().coerceIn(0f, photoHeight - 1f)
        }.takeIf(Perspective::valid)
    }

    private fun onFrame(p: Pt, width: Int, height: Int) =
        p.x <= 0 || p.y <= 0 || p.x >= width - 1 || p.y >= height - 1

    /** Every set cell, and every cell within [SUPPORT_REACH] of one, of the outline. */
    private fun supportMap(outline: List<Pt>, width: Int, height: Int): BooleanArray {
        val near = BooleanArray(width * height)
        for (p in outline) {
            val px = p.x.toInt()
            val py = p.y.toInt()
            for (y in maxOf(0, py - SUPPORT_REACH)..minOf(height - 1, py + SUPPORT_REACH)) {
                for (x in maxOf(0, px - SUPPORT_REACH)..minOf(width - 1, px + SUPPORT_REACH)) near[y * width + x] = true
            }
        }
        return near
    }

    /**
     * Length of the quad's sides along the outline, less the length that crosses where no outline
     * is. Past the photo's edge a side neither gains nor loses.
     * Only runs along the outline count: a side that merely crosses a finger touches it briefly.
     */
    private fun supportScore(quad: List<Pt>, support: BooleanArray, width: Int, height: Int): Double {
        val minRun = (MIN_SUPPORT_RUN * minOf(width, height)).toInt()
        var score = 0.0
        for (side in 0..3) {
            val a = quad[side]
            val b = quad[(side + 1) % 4]
            val steps = kotlin.math.hypot(b.x - a.x, b.y - a.y).toInt().coerceAtLeast(1)
            var run = 0
            fun endRun() {
                score += if (run >= minRun) run.toDouble() else -run.toDouble()
                run = 0
            }
            for (step in 0..steps) {
                val x = (a.x + (b.x - a.x) * step / steps).toInt()
                val y = (a.y + (b.y - a.y) * step / steps).toInt()
                when {
                    x !in 0 until width || y !in 0 until height -> endRun()
                    support[y * width + x] -> run++
                    else -> {
                        endRun()
                        score -= 1.0
                    }
                }
            }
            endRun()
        }
        return score
    }

    /** The strongest straight lines through the outline, by a Hough vote over angle and distance. */
    private fun outlineLines(outline: List<Pt>, width: Int, height: Int): List<Line> {
        val reach = (kotlin.math.hypot(width.toDouble(), height.toDouble()) / HOUGH_STEP).toInt() + 1
        val rows = 2 * reach + 1
        val votes = IntArray(HOUGH_ANGLES * rows)
        val cos = DoubleArray(HOUGH_ANGLES) { kotlin.math.cos(Math.PI * it / HOUGH_ANGLES) }
        val sin = DoubleArray(HOUGH_ANGLES) { kotlin.math.sin(Math.PI * it / HOUGH_ANGLES) }
        // Where the outline runs along the photo's edge the frame cut it, so it is not a side.
        for (p in outline.filterNot { onFrame(it, width, height) }) {
            for (angle in 0 until HOUGH_ANGLES) {
                val distance = Math.round((p.x * cos[angle] + p.y * sin[angle]) / HOUGH_STEP).toInt()
                votes[angle * rows + distance + reach]++
            }
        }
        val needed = MIN_LINE_VOTES * minOf(width, height)
        val order = votes.indices.filter { votes[it] >= needed }.sortedByDescending { votes[it] }
        val kept = ArrayList<Int>()
        for (cell in order) {
            if (kept.size >= MAX_LINES) break
            val angle = cell / rows
            val distance = cell % rows
            val close = kept.any { other ->
                val turn = kotlin.math.abs(other / rows - angle)
                minOf(turn, HOUGH_ANGLES - turn) <= LINE_SEPARATION_ANGLE &&
                    kotlin.math.abs(other % rows - distance) * HOUGH_STEP <= LINE_SEPARATION_DISTANCE
            }
            if (!close) kept.add(cell)
        }
        return kept.map { cell ->
            val angle = cell / rows
            val distance = (cell % rows - reach) * HOUGH_STEP
            // Normal (cos, sin) at this distance from the origin; the line runs perpendicular to it.
            Line(Pt(distance * cos[angle], distance * sin[angle]), -sin[angle], cos[angle])
        }
    }

    private fun degrees(line: Line) = Math.toDegrees(atan2(line.dy, line.dx)).let { if (it < 0) it + 180 else it }

    private fun turn(a: Line, b: Line): Double {
        val difference = kotlin.math.abs(degrees(a) - degrees(b))
        return minOf(difference, 180 - difference)
    }

    /** Quads made of two pairs of near-parallel outline lines, with corners near the photo. */
    private fun lineQuads(outline: List<Pt>, width: Int, height: Int): List<List<Pt>> {
        val lines = outlineLines(outline, width, height)
        val pairs = ArrayList<Pair<Line, Line>>()
        for (i in lines.indices) for (j in i + 1 until lines.size) {
            if (turn(lines[i], lines[j]) <= MAX_PARALLEL_TURN) pairs.add(lines[i] to lines[j])
        }
        val quads = ArrayList<List<Pt>>()
        val margin = 0.1 * maxOf(width, height)
        for (i in pairs.indices) for (j in i + 1 until pairs.size) {
            val (a, c) = pairs[i]
            val (b, d) = pairs[j]
            if (listOf(a to b, a to d, c to b, c to d).any { (x, y) -> turn(x, y) < MIN_CORNER_TURN }) continue
            val corners = listOf(meet(a, b), meet(b, c), meet(c, d), meet(d, a))
            if (corners.any { it == null }) continue
            val points = corners.filterNotNull()
            if (points.any { it.x < -margin || it.y < -margin || it.x > width + margin || it.y > height + margin }) continue
            quads.add(points)
        }
        return quads
    }

    /** Sets every cell the outside cannot reach, such as the text printed on the paper. */
    private fun fillHoles(cells: BooleanArray, width: Int, height: Int): BooleanArray {
        val outside = BooleanArray(cells.size)
        val stack = IntArray(cells.size)
        var top = 0
        fun visit(index: Int) {
            if (!cells[index] && !outside[index]) {
                outside[index] = true
                stack[top++] = index
            }
        }
        for (x in 0 until width) { visit(x); visit((height - 1) * width + x) }
        for (y in 0 until height) { visit(y * width); visit(y * width + width - 1) }
        while (top > 0) {
            val index = stack[--top]
            val x = index % width
            val y = index / width
            if (x > 0) visit(index - 1)
            if (x < width - 1) visit(index + 1)
            if (y > 0) visit(index - width)
            if (y < height - 1) visit(index + width)
        }
        return BooleanArray(cells.size) { !outside[it] }
    }

    /** A line through [point] along the unit [direction]. */
    private class Line(val point: Pt, val dx: Double, val dy: Double)

    /** Total least squares: the line runs along the points' main axis, through their mean. */
    private fun fitLine(points: List<Pt>): Line {
        val mx = points.sumOf { it.x } / points.size
        val my = points.sumOf { it.y } / points.size
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0
        for (p in points) {
            sxx += (p.x - mx) * (p.x - mx); sxy += (p.x - mx) * (p.y - my); syy += (p.y - my) * (p.y - my)
        }
        val angle = 0.5 * atan2(2 * sxy, sxx - syy)
        return Line(Pt(mx, my), kotlin.math.cos(angle), kotlin.math.sin(angle))
    }

    private fun meet(a: Line, b: Line): Pt? {
        val det = a.dx * b.dy - a.dy * b.dx
        if (kotlin.math.abs(det) < 1e-9) return null
        val t = ((b.point.x - a.point.x) * b.dy - (b.point.y - a.point.y) * b.dx) / det
        return Pt(a.point.x + t * a.dx, a.point.y + t * a.dy)
    }

    /**
     * Paper sides are straight but its corners curl, tear or hide under a thumb, so the outline's
     * corners cut into the paper. Fits each side to the outline along its middle, moves it out
     * over the paper's edge there, and puts each corner where two fitted sides meet. Null when a
     * side has too little outline to fit or a corner would move implausibly far.
     */
    private fun straighten(quad: List<Pt>, edge: List<Pt>, reach: Double): List<Pt>? {
        val centreX = quad.sumOf { it.x } / 4
        val centreY = quad.sumOf { it.y } / 4
        val lines = (0..3).map { side ->
            val a = quad[side]
            val b = quad[(side + 1) % 4]
            val length = kotlin.math.hypot(b.x - a.x, b.y - a.y)
            val ux = (b.x - a.x) / length
            val uy = (b.y - a.y) / length
            val near = edge.filter { p ->
                val along = ((p.x - a.x) * ux + (p.y - a.y) * uy) / length
                val across = kotlin.math.abs((p.x - a.x) * uy - (p.y - a.y) * ux)
                kotlin.math.abs(along - 0.5) <= SIDE_MIDDLE / 2 && across <= reach
            }
            // A side the hand hides has too little outline to refit; it stays as it is.
            val keep = Line(Pt(a.x, a.y), ux, uy)
            if (near.size < MIN_SIDE_POINTS) return@map keep
            // A curl or a thumb bends part of the outline away; refit to the points that stay straight.
            var used = near
            var fit = fitLine(used)
            repeat(TRIM_ROUNDS) {
                val distances = used.map { kotlin.math.abs((it.x - fit.point.x) * fit.dy - (it.y - fit.point.y) * fit.dx) }
                val limit = maxOf(TRIM_FLOOR, TRIM_RATIO * distances.sorted()[distances.size / 2])
                val kept = used.filterIndexed { index, _ -> distances[index] <= limit }
                if (kept.size >= MIN_SIDE_POINTS && kept.size < used.size) {
                    used = kept
                    fit = fitLine(used)
                }
            }
            if (used.size < MIN_SIDE_SHARE * SIDE_MIDDLE * length) return@map keep
            val mx = fit.point.x
            val my = fit.point.y
            var dx = fit.dx
            var dy = fit.dy
            if (dx * ux + dy * uy < 0) { dx = -dx; dy = -dy }
            val c = quad[(side + 2) % 4]
            val d = quad[(side + 3) % 4]
            val opposite = kotlin.math.hypot(d.x - c.x, d.y - c.y)
            val ox = (d.x - c.x) / opposite
            val oy = (d.y - c.y) / opposite
            if (kotlin.math.abs(dx * oy - dy * ox) > kotlin.math.sin(MAX_SIDE_TURN)) { dx = ux; dy = uy }
            // The normal pointing away from the paper.
            var nx = dy
            var ny = -dx
            if ((mx - centreX) * nx + (my - centreY) * ny < 0) { nx = -nx; ny = -ny }
            val offsets = used.map { (it.x - mx) * nx + (it.y - my) * ny }.sorted()
            val out = offsets[((offsets.size - 1) * SIDE_COVER).toInt()].coerceAtLeast(0.0)
            Line(Pt(mx + nx * out, my + ny * out), dx, dy)
        }
        val corners = (0..3).map { meet(lines[(it + 3) % 4], lines[it]) ?: return null }
        val diagonal = kotlin.math.hypot(quad[2].x - quad[0].x, quad[2].y - quad[0].y)
        val moved = corners.indices.maxOf { kotlin.math.hypot(corners[it].x - quad[it].x, corners[it].y - quad[it].y) }
        return corners.takeIf { moved <= MAX_CORNER_SHIFT * diagonal }
    }

    private fun clockwise(raw: List<Pt>): List<Pt> {
        val cx = raw.map { it.x }.average()
        val cy = raw.map { it.y }.average()
        val ordered = raw.sortedBy { atan2(it.y - cy, it.x - cx) }
        val first = ordered.indices.minBy { ordered[it].x + ordered[it].y }
        return List(4) { ordered[(first + it) % 4] }
    }
}
