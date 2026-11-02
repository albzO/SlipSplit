package local.ocrbridge

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.io.FileInputStream

/** Pinned DewarpNet community export: BGR NCHW -> normalized planar backward map. */
class DewarpNet(context: Context) : Closeable {
    private val interpreter: Interpreter = context.assets.openFd("dewarpnet/dewarp.tflite").use { asset ->
        FileInputStream(asset.fileDescriptor).use { stream ->
            val mapped = stream.channel.map(FileChannel.MapMode.READ_ONLY, asset.startOffset, asset.declaredLength)
            Interpreter(mapped, Interpreter.Options().setNumThreads(4))
        }
    }

    init {
        require(interpreter.getInputTensor(0).shape().contentEquals(intArrayOf(1, 3, 256, 256)))
        require(interpreter.getOutputTensor(0).shape().contentEquals(intArrayOf(1, 2, 128, 128)))
    }

    fun flatten(bitmap: Bitmap): Bitmap {
        val resized = Bitmap.createScaledBitmap(bitmap, 256, 256, true)
        val small = IntArray(256 * 256)
        resized.getPixels(small, 0, 256, 0, 0, 256, 256)
        if (resized !== bitmap) resized.recycle()
        val input = ByteBuffer.allocateDirect(3 * small.size * 4).order(ByteOrder.nativeOrder())
        // B, G, R planes; do not use Android's ARGB memory layout as model input.
        for (shift in intArrayOf(0, 8, 16)) for (pixel in small) {
            input.putFloat(((pixel ushr shift) and 255) / 255f)
        }
        input.rewind()
        val output = ByteBuffer.allocateDirect(2 * 128 * 128 * 4).order(ByteOrder.nativeOrder())
        interpreter.run(input, output)
        output.rewind()
        val grid = FloatArray(2 * 128 * 128)
        output.asFloatBuffer().get(grid)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val flat = BackwardMap.remap(pixels, bitmap.width, bitmap.height, grid, 128)
        return Bitmap.createBitmap(flat, bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    }

    override fun close() = interpreter.close()
}
