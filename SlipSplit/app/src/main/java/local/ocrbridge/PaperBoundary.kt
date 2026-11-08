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

/** Finds the paper's corners from the object mask; when it fails the user frames the paper. */
internal object PaperBoundary {
    const val VERSION = "u2netp-v2"

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
        val points = clockwise(quads.map(::clockwise).maxBy { Outline.overlap(it, region) })
        if (area / Outline.area(points) < MIN_FILL) return null
        return FloatArray(8) { index ->
            val point = points[index / 2]
            if (index % 2 == 0) (point.x * photoWidth / width).toFloat().coerceIn(0f, photoWidth - 1f)
            else (point.y * photoHeight / height).toFloat().coerceIn(0f, photoHeight - 1f)
        }.takeIf(Perspective::valid)
    }

    private fun clockwise(raw: List<Pt>): List<Pt> {
        val cx = raw.map { it.x }.average()
        val cy = raw.map { it.y }.average()
        val ordered = raw.sortedBy { atan2(it.y - cy, it.x - cx) }
        val first = ordered.indices.minBy { ordered[it].x + ordered[it].y }
        return List(4) { ordered[(first + it) % 4] }
    }
}
