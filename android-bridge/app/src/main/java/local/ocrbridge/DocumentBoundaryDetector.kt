package local.ocrbridge

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfInt
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Offline initial corner estimate; uncertain images are left for manual adjustment. */
internal object DocumentBoundaryDetector {
    const val VERSION = "paper-color-v3-diag1"
    private val loaded by lazy {
        OpenCVLoader.initLocal().also { if (it) Core.setNumThreads(2) }
    }

    fun prepare(): Boolean = try {
        loaded
    } catch (error: Exception) {
        Log.w("DocumentEdges", "OpenCV initialization failed", error)
        false
    } catch (error: LinkageError) {
        Log.w("DocumentEdges", "OpenCV native library unavailable", error)
        false
    }

    fun detect(photo: Bitmap): FloatArray? {
        check(prepare()) { "OpenCV could not be initialized" }
        if (minOf(photo.width, photo.height) < 32) return null
        val scale = minOf(1.0, 960.0 / maxOf(photo.width, photo.height))
        val width = (photo.width * scale).roundToInt().coerceAtLeast(2)
        val height = (photo.height * scale).roundToInt().coerceAtLeast(2)
        val rgba = Mat()
        val reduced = Mat()
        val gray = Mat()
        val blurred = Mat()
        val edges = Mat()
        val inverseEdges = Mat()
        val distances = Mat()
        val mask = Mat()
        val closed = Mat()
        val rgb = Mat()
        val hsv = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        try {
            Utils.bitmapToMat(photo, rgba)
            Imgproc.resize(rgba, reduced, Size(width.toDouble(), height.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.cvtColor(reduced, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.cvtColor(reduced, rgb, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
            val colorPixels = ByteArray(width * height * 3)
            hsv.get(0, 0, colorPixels)
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(blurred, edges, 20.0, 60.0)
            Core.bitwise_not(edges, inverseEdges)
            Imgproc.distanceTransform(inverseEdges, distances, Imgproc.DIST_L2, Imgproc.DIST_MASK_3)
            val distancePixels = FloatArray(width * height)
            distances.get(0, 0, distancePixels)
            var best: Candidate? = null

            fun consider(input: Mat) {
                val contours = ArrayList<MatOfPoint>()
                val hierarchy = Mat()
                try {
                    Imgproc.findContours(input, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
                    // Limit work in dense text/table images; small glyphs cannot be a page.
                    // Cache native area measurements instead of calling JNI on every sort comparison.
                    val ranked = contours.map { it to Imgproc.contourArea(it) }
                        .filter { it.second >= width * height * 0.025 }
                        .sortedByDescending { it.second }.take(24)
                    for ((contour, area) in ranked) {
                        val curve = MatOfPoint2f(*contour.toArray())
                        val polygon = MatOfPoint2f()
                        val hullIndices = MatOfInt()
                        val hull = MatOfPoint2f()
                        try {
                            // Folded/curled paper often has more than four outline vertices.
                            // Approximate its convex envelope as well as its raw outline.
                            Imgproc.convexHull(contour, hullIndices)
                            val contourPoints = contour.toArray()
                            hull.fromArray(*hullIndices.toArray().map { contourPoints[it] }.toTypedArray())
                            for (outline in listOf(curve, hull)) {
                                val perimeter = Imgproc.arcLength(outline, true)
                                for (epsilon in doubleArrayOf(0.015, 0.03, 0.055)) {
                                    Imgproc.approxPolyDP(outline, polygon, perimeter * epsilon, true)
                                    if (polygon.total() != 4L) continue
                                    val candidate = score(polygon.toArray(), area, width, height, distancePixels, colorPixels) ?: continue
                                    if (best == null || candidate.score > best!!.score) best = candidate
                                }
                            }
                        } finally {
                            curve.release()
                            polygon.release()
                            hullIndices.release()
                            hull.release()
                        }
                    }
                } finally {
                    contours.forEach { it.release() }
                    hierarchy.release()
                }
            }

            // Edge closure handles moderate shadows; threshold contours handle low contrast.
            Imgproc.morphologyEx(edges, closed, Imgproc.MORPH_CLOSE, kernel)
            consider(closed)
            Imgproc.Canny(blurred, edges, 60.0, 180.0)
            Imgproc.morphologyEx(edges, closed, Imgproc.MORPH_CLOSE, kernel)
            consider(closed)
            Imgproc.threshold(blurred, mask, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
            consider(mask)
            Imgproc.threshold(blurred, mask, 0.0, 255.0, Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)
            consider(mask)
            // Light paper against colored wood/cloth may have little luminance contrast.
            for (saturation in intArrayOf(30, 50, 70)) {
                Core.inRange(hsv, Scalar(0.0, 0.0, 35.0), Scalar(180.0, saturation.toDouble(), 255.0), mask)
                Imgproc.morphologyEx(mask, closed, Imgproc.MORPH_CLOSE, kernel)
                consider(closed)
            }

            val found = best ?: return null
            val padded = expand(found.points, minOf(width, height) * 0.03)
            return FloatArray(8) { index ->
                val point = padded[index / 2]
                if (index % 2 == 0) (point.x * photo.width / width).toFloat().coerceIn(0f, photo.width - 1f)
                else (point.y * photo.height / height).toFloat().coerceIn(0f, photo.height - 1f)
            }.takeIf(Perspective::valid)
        } finally {
            for (mat in listOf(rgba, reduced, gray, blurred, edges, inverseEdges, distances, mask, closed, rgb, hsv, kernel)) mat.release()
        }
    }

    private data class Candidate(val points: List<Point>, val score: Double)

    /** A small outward margin protects characters near irregular/folded paper edges. */
    fun expand(points: List<Point>, margin: Double): List<Point> = List(4) { i ->
        val a = points[(i + 3) % 4]; val b = points[i]; val c = points[(i + 1) % 4]
        val previousLength = hypot(b.x - a.x, b.y - a.y)
        val nextLength = hypot(c.x - b.x, c.y - b.y)
        val nx1 = -(b.y - a.y) / previousLength; val ny1 = (b.x - a.x) / previousLength
        val nx2 = -(c.y - b.y) / nextLength; val ny2 = (c.x - b.x) / nextLength
        val determinant = nx1 * ny2 - ny1 * nx2
        if (abs(determinant) < 0.01) b
        else Point(b.x + margin * (ny1 - ny2) / determinant, b.y + margin * (nx2 - nx1) / determinant)
    }

    private fun score(raw: Array<Point>, contourArea: Double, width: Int, height: Int, distances: FloatArray,
                      colors: ByteArray): Candidate? {
        val cx = raw.map { it.x }.average()
        val cy = raw.map { it.y }.average()
        val ordered = raw.sortedBy { atan2(it.y - cy, it.x - cx) }
        val first = ordered.indices.minByOrNull { ordered[it].x + ordered[it].y } ?: return null
        val points = List(4) { ordered[(first + it) % 4] }
        var twiceArea = 0.0
        var angleQuality = 0.0
        var supportTotal = 0.0
        var supportedSides = 0
        var physicalSides = 0
        var contrastSides = 0
        var contrastTotal = 0.0
        fun saturation(x: Int, y: Int) = colors[(y * width + x) * 3 + 1].toInt() and 255
        fun value(x: Int, y: Int) = colors[(y * width + x) * 3 + 2].toInt() and 255
        for (i in 0..3) {
            val a = points[i]; val b = points[(i + 1) % 4]; val c = points[(i + 2) % 4]
            val ux = b.x - a.x; val uy = b.y - a.y
            val vx = c.x - b.x; val vy = c.y - b.y
            val edge = hypot(ux, uy)
            val nextEdge = hypot(vx, vy)
            if (ux * vy - uy * vx <= 0 || minOf(edge, nextEdge) < minOf(width, height) * 0.05) return null
            val cosine = abs((ux * vx + uy * vy) / (edge * nextEdge))
            if (cosine > 0.85) return null
            angleQuality += 1.0 - cosine
            twiceArea += a.x * b.y - a.y * b.x
            var support = 0
            var clipped = 0
            var saturationContrast = 0.0
            var valueContrast = 0.0
            var contrastSamples = 0
            val inset = (minOf(width, height) * 0.012).coerceIn(3.0, 10.0)
            val nx = -uy / edge * inset; val ny = ux / edge * inset
            for (sample in 1..32) {
                val t = sample / 33.0
                val x = (a.x + t * ux).roundToInt().coerceIn(0, width - 1)
                val y = (a.y + t * uy).roundToInt().coerceIn(0, height - 1)
                if (distances[y * width + x] <= 7f) support++
                if (x <= 2 || y <= 2 || x >= width - 3 || y >= height - 3) clipped++
                val ix = (a.x + t * ux + nx).roundToInt(); val iy = (a.y + t * uy + ny).roundToInt()
                val ox = (a.x + t * ux - nx).roundToInt(); val oy = (a.y + t * uy - ny).roundToInt()
                if (ix in 0 until width && ox in 0 until width && iy in 0 until height && oy in 0 until height) {
                    saturationContrast += saturation(ox, oy) - saturation(ix, iy)
                    valueContrast += value(ix, iy) - value(ox, oy)
                    contrastSamples++
                }
            }
            val physical = support / 32.0
            if (physical >= 0.20) physicalSides++
            // A receipt may continue past the photo boundary. Its clipped edge
            // is valid even though Canny cannot observe paper outside the image.
            val fraction = maxOf(physical, if (clipped >= 26) 0.45 else 0.0)
            supportTotal += fraction
            if (fraction >= 0.20) supportedSides++
            if (contrastSamples >= 8) {
                val contrast = maxOf(saturationContrast / contrastSamples / 80,
                    valueContrast / contrastSamples / 100).coerceIn(0.0, 1.0)
                contrastTotal += contrast
                if (contrast >= 0.10) contrastSides++
            }
        }
        val area = twiceArea / 2
        val fraction = area / (width * height)
        if (fraction !in 0.025..1.001 || contourArea / area < 0.5) return null
        val support = supportTotal / 4
        if (support < 0.30 || supportedSides < 3 || physicalSides < 2) return null
        // A mask's artificial image border must not win over the actual paper.
        val onFrame = points.count { it.x < 3 || it.y < 3 || it.x > width - 4 || it.y > height - 4 }
        if (onFrame == 4 && fraction > 0.97 && physicalSides < 3) return null
        // Bright wood is also bright: inspect the whole region, not just its outline.
        // A grid is cheap and avoids allocating a full-size mask for every candidate.
        var paperSamples = 0
        for (row in 1..24) {
            val t = row / 25.0
            val lx = points[0].x * (1 - t) + points[3].x * t
            val ly = points[0].y * (1 - t) + points[3].y * t
            val rx = points[1].x * (1 - t) + points[2].x * t
            val ry = points[1].y * (1 - t) + points[2].y * t
            for (column in 1..16) {
                val s = column / 17.0
                val x = (lx * (1 - s) + rx * s).roundToInt().coerceIn(0, width - 1)
                val y = (ly * (1 - s) + ry * s).roundToInt().coerceIn(0, height - 1)
                if (saturation(x, y) <= 70 && value(x, y) >= 35) paperSamples++
            }
        }
        val paper = paperSamples / (24.0 * 16)
        if (paper < 0.65 || contrastSides < 2) return null
        return Candidate(points, paper * 0.45 + contrastTotal / 4 * 0.25 + support * 0.15 +
            angleQuality / 4 * 0.10 + kotlin.math.sqrt(fraction) * 0.05)
    }
}
