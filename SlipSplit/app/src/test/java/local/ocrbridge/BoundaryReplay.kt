package local.ocrbridge

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Prints the corners found for each folder under BOUNDARY_RUNS holding the model's 320×320
 * `mask.f32` and `size.txt` with the photo's width and height.
 */
class BoundaryReplay {
    @Test
    fun replay() {
        val root = System.getenv("BOUNDARY_RUNS")
        assumeTrue(root != null)
        val report = StringBuilder()
        for (folder in File(root!!).listFiles()!!.filter { File(it, "mask.f32").exists() }.sorted()) {
            val (width, height) = File(folder, "size.txt").readText().trim().split(" ").map(String::toInt)
            val buffer = ByteBuffer.wrap(File(folder, "mask.f32").readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val mask = FloatArray(buffer.remaining()).also { buffer.get(it) }
            val corners = PaperBoundary.quad(mask, width, height)
            report.append("${folder.name}|${corners?.joinToString(" ") { "%.0f".format(it) } ?: "none"}\n")
        }
        File(root, "boundary.txt").writeText(report.toString())
    }
}
