package local.ocrbridge

import android.app.Instrumentation
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.content.Intent
import android.widget.CheckBox
import android.widget.RadioGroup
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Real device comparison. Push one photo to files/smoke.jpg before running. */
class DewarpSmoke : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            // HyperOS may suspend a test-only background process. Keep the app visible.
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            runOnMainSync {
                check(activity.findViewById<CheckBox>(R.id.flatten).isChecked)
                check(activity.findViewById<RadioGroup>(R.id.flattenModel).checkedRadioButtonId == R.id.uvdoc)
            }
            val root = requireNotNull(targetContext.getExternalFilesDir(null))
            val source = requireNotNull(BitmapFactory.decodeFile(File(root, "smoke.jpg").path))
            val scale = minOf(1f, 1600f / maxOf(source.width, source.height))
            val original = Bitmap.createScaledBitmap(source, (source.width * scale).toInt(), (source.height * scale).toInt(), true)
            val reports = JSONArray()
            for (backend in listOf("none", "uvdoc", "dewarpnet")) {
                val start = System.nanoTime()
                val image = when (backend) {
                    "uvdoc" -> Dewarper(targetContext).use { it.flatten(original) }
                    "dewarpnet" -> DewarpNet(targetContext).use { it.flatten(original) }
                    else -> original
                }
                val flattenMs = (System.nanoTime() - start) / 1_000_000
                File(root, "smoke-$backend.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val ocr = PpOcr(targetContext, "small")
                try {
                    val ocrStart = System.nanoTime()
                    val lines = ocr.recognize(image, ReceiptLanguage.AUTO)
                    val elapsed = (System.nanoTime() - ocrStart) / 1_000_000
                    val receipt = parseReceipt(lines)
                    saveRun(targetContext, image, lines, "small", ReceiptLanguage.AUTO, elapsed, flattenMs, backend, original)
                    reports.put(JSONObject().put("backend", backend).put("flatten_ms", flattenMs)
                        .put("ocr_ms", elapsed).put("lines", lines.size).put("total", receipt.total)
                        .put("items", receipt.items.size).put("sum_matches", sumMatches(receipt)))
                } finally {
                    ocr.close()
                }
                if (image !== original) image.recycle()
            }
            File(root, "smoke-report.json").writeText(reports.toString(2))
            result.putString("comparison", reports.toString())
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("error", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
