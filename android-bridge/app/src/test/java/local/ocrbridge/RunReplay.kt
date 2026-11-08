package local.ocrbridge

import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Prints the parse of runs pulled from the phone; set REPLAY_RUNS to their folder. */
class RunReplay {
    @Test
    fun replay() {
        val root = System.getenv("REPLAY_RUNS")
        assumeTrue(root != null)
        val out = StringBuilder()
        for (folder in File(root!!).listFiles()!!.filter { File(it, "ocr.json").exists() }.sortedDescending()) {
            val run = JSONObject(File(folder, "ocr.json").readText())
            val raw = run.getJSONArray("lines")
            val lines = (0 until raw.length()).map {
                val line = raw.getJSONObject(it)
                val box = line.getJSONArray("box")
                val poly = line.optJSONArray("poly")
                val corners = poly?.let { p -> FloatArray(8) { i -> p.getJSONArray(i / 2).getInt(i % 2).toFloat() } }
                val raw = line.optJSONArray("alternatives")
                val alternatives = (0 until (raw?.length() ?: 0)).map { i ->
                    val choice = raw!!.getJSONObject(i)
                    Alternative(
                        choice.getInt("position"), choice.getString("digit")[0],
                        choice.getDouble("probability").toFloat(), choice.getDouble("chose").toFloat(),
                    )
                }
                OcrLine(line.getString("text"), IntArray(4) { i -> box.getInt(i) }, 0f, corners, alternatives)
            }
            val receipt = parseReceipt(lines)
            out.append("=== ${folder.name}  company=${receipt.company} date=${receipt.date} ${receipt.time}\n")
            for (item in receipt.items) {
                val off = (if (item.discount > 0) " (-${item.discount})" else "") +
                    (item.readAs?.let { " (read as $it)" } ?: "")
                out.append("  ${item.name} | ${item.spec} | x${item.qty} | ${item.price}$off\n")
            }
            for (item in receipt.dropped) out.append("  [dropped] ${item.name} | x${item.qty} | ${item.price}\n")
            out.append("  subtotal=${receipt.subtotal} discount=${receipt.discount} tax=${receipt.tax} total=${receipt.total}\n")
        }
        File(root, "replay.txt").writeText(out.toString())
    }
}
