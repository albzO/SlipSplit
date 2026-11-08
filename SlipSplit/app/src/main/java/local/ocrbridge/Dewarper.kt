package local.ocrbridge

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.nio.FloatBuffer

private const val NUM_THREADS = 4
private const val OPAQUE = 0xFF shl 24
private const val CHANNEL_MAX = 255f

/** Flattens curled paper with UVDoc; output is the same size, so OCR boxes match it directly. */
class Dewarper(context: Context) : java.io.Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(NUM_THREADS) }
        val model = context.assets.open("ppocr/uvdoc/det.ort").use { it.readBytes() }
        session = env.createSession(model, options)
    }

    fun flatten(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val data = FloatArray(3 * w * h)
        for (index in pixels.indices) {
            val argb = pixels[index]
            // The model was trained on BGR input scaled to 0..1.
            data[index] = (argb and 0xFF) / CHANNEL_MAX
            data[w * h + index] = ((argb shr 8) and 0xFF) / CHANNEL_MAX
            data[2 * w * h + index] = ((argb shr 16) and 0xFF) / CHANNEL_MAX
        }
        val shape = longArrayOf(1, 3, h.toLong(), w.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { input ->
            session.run(mapOf("image" to input)).use { result ->
                val output = (result[0] as OnnxTensor).floatBuffer
                for (index in pixels.indices) {
                    val blue = channel(output.get(index))
                    val green = channel(output.get(w * h + index))
                    val red = channel(output.get(2 * w * h + index))
                    pixels[index] = OPAQUE or (red shl 16) or (green shl 8) or blue
                }
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun channel(value: Float) = (value * CHANNEL_MAX).toInt().coerceIn(0, 255)

    override fun close() = session.close()
}
