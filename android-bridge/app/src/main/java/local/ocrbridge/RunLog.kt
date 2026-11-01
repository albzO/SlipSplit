package local.ocrbridge

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "RunLog"
private const val PHOTO_QUALITY = 92

/**
 * Keeps each photo with its raw OCR output in the app's external files folder, where
 * tools/pull_runs.py collects them to test post-processing without running the model again.
 */
fun saveRun(
    context: Context,
    photo: Bitmap,
    lines: List<OcrLine>,
    tier: String,
    language: ReceiptLanguage,
    elapsedMs: Long,
) {
    val root = context.getExternalFilesDir("runs") ?: return
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    val folder = File(root, stamp).apply { mkdirs() }
    val rawLines = JSONArray()
    for (line in lines) {
        rawLines.put(
            JSONObject()
                .put("text", line.text)
                .put("score", line.score.toDouble())
                .put("box", JSONArray(line.box.toList())),
        )
    }
    val run = JSONObject()
        .put("engine", tier)
        .put("language", language.name.lowercase())
        .put("infer_ms", elapsedMs)
        .put("lines", rawLines)
    try {
        File(folder, "photo.jpg").outputStream().use {
            photo.compress(Bitmap.CompressFormat.JPEG, PHOTO_QUALITY, it)
        }
        File(folder, "ocr.json").writeText(run.toString())
    } catch (error: IOException) {
        Log.w(TAG, "could not save run", error)
    }
}
