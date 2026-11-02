package local.ocrbridge

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
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
private const val MARGIN = 0.015
private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
private val POLYGON_TOLERANCES = doubleArrayOf(0.01, 0.02, 0.03, 0.05, 0.08, 0.12)

/** U²-Netp marks the main object in a photo, so paper is found without needing clean edges. */
internal class PaperSegmenter(context: Context) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(NUM_THREADS) }
        val model = context.assets.open("u2netp/u2netp.onnx").use { it.readBytes() }
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

/** Finds the paper's corners from the object mask, then from edges if the mask is unusable. */
internal object PaperBoundary {
    const val VERSION = "u2netp-v1"

    class Found(val corners: FloatArray, val method: String)

    fun detect(context: Context, photo: Bitmap): Found? {
        check(DocumentBoundaryDetector.prepare()) { "OpenCV could not be initialized" }
        val masked = try {
            PaperSegmenter(context).use { quad(it.mask(photo), photo.width, photo.height) }
        } catch (error: Exception) {
            Log.w("PaperBoundary", "Mask detection failed", error)
            null
        }
        if (masked != null) return Found(masked, "u2netp")
        return DocumentBoundaryDetector.detect(photo)?.let { Found(it, "opencv") }
    }

    fun quad(mask: FloatArray, photoWidth: Int, photoHeight: Int): FloatArray? {
        val scale = WORK_SIDE / maxOf(photoWidth, photoHeight)
        val width = (photoWidth * scale).toInt().coerceAtLeast(2)
        val height = (photoHeight * scale).toInt().coerceAtLeast(2)
        val square = Mat(MASK_SIDE, MASK_SIDE, CvType.CV_32F)
        val sized = Mat()
        val binary = Mat()
        val bytes = Mat()
        val hierarchy = Mat()
        val contours = ArrayList<MatOfPoint>()
        try {
            square.put(0, 0, mask)
            Imgproc.resize(square, sized, Size(width.toDouble(), height.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            Imgproc.threshold(sized, binary, MASK_THRESHOLD.toDouble(), 255.0, Imgproc.THRESH_BINARY)
            binary.convertTo(bytes, CvType.CV_8U)
            Imgproc.findContours(bytes, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            val contour = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return null
            val area = Imgproc.contourArea(contour)
            if (area / (width * height) !in MIN_AREA..MAX_AREA) return null
            val points = fourCorners(contour) ?: return null
            if (area / polygonArea(points) < MIN_FILL) return null
            val padded = DocumentBoundaryDetector.expand(points, minOf(width, height) * MARGIN)
            return FloatArray(8) { index ->
                val point = padded[index / 2]
                if (index % 2 == 0) (point.x * photoWidth / width).toFloat().coerceIn(0f, photoWidth - 1f)
                else (point.y * photoHeight / height).toFloat().coerceIn(0f, photoHeight - 1f)
            }.takeIf(Perspective::valid)
        } finally {
            contours.forEach { it.release() }
            for (mat in listOf(square, sized, binary, bytes, hierarchy)) mat.release()
        }
    }

    /** The convex outline simplified to four points; the tightest rectangle if it never gets there. */
    private fun fourCorners(contour: MatOfPoint): List<Point>? {
        val hullIndices = MatOfInt()
        val hull = MatOfPoint2f()
        val polygon = MatOfPoint2f()
        val curve = MatOfPoint2f(*contour.toArray())
        try {
            Imgproc.convexHull(contour, hullIndices)
            val outline = contour.toArray()
            hull.fromArray(*hullIndices.toArray().map { outline[it] }.toTypedArray())
            val perimeter = Imgproc.arcLength(hull, true)
            for (tolerance in POLYGON_TOLERANCES) {
                Imgproc.approxPolyDP(hull, polygon, perimeter * tolerance, true)
                if (polygon.total() == 4L) return clockwise(polygon.toArray())
            }
            val corners = arrayOfNulls<Point>(4)
            Imgproc.minAreaRect(curve).points(corners)
            return clockwise(corners.requireNoNulls())
        } finally {
            hullIndices.release()
            hull.release()
            polygon.release()
            curve.release()
        }
    }

    private fun clockwise(raw: Array<Point>): List<Point> {
        val cx = raw.map { it.x }.average()
        val cy = raw.map { it.y }.average()
        val ordered = raw.sortedBy { atan2(it.y - cy, it.x - cx) }
        val first = ordered.indices.minBy { ordered[it].x + ordered[it].y }
        return List(4) { ordered[(first + it) % 4] }
    }

    private fun polygonArea(points: List<Point>): Double {
        var twice = 0.0
        for (i in 0..3) {
            val a = points[i]; val b = points[(i + 1) % 4]
            twice += a.x * b.y - a.y * b.x
        }
        return kotlin.math.abs(twice) / 2
    }
}
