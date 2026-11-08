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
 * they can be pulled with adb to test post-processing without running the model again.
 */
fun saveRun(
    context: Context,
    photo: Bitmap,
    lines: List<OcrLine>,
    tier: String,
    language: ReceiptLanguage,
    elapsedMs: Long,
    flattenMs: Long,
    flattenBackend: String = "unknown",
    original: Bitmap? = null,
    levelled: Boolean = false,
    corners: FloatArray? = null,
    pointWidth: Int = 0,
    pointHeight: Int = 0,
) {
    val root = context.getExternalFilesDir("runs") ?: return
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
    val folder = File(root, stamp).apply { mkdirs() }
    val rawLines = JSONArray()
    for (line in lines) {
        val entry = JSONObject()
            .put("text", line.text)
            .put("score", line.score.toDouble())
            .put("box", JSONArray(line.box.toList()))
        line.corners?.let { corners ->
            val poly = (0 until 4).map { JSONArray(listOf(corners[it * 2].toInt(), corners[it * 2 + 1].toInt())) }
            entry.put("poly", JSONArray(poly))
        }
        if (line.alternatives.isNotEmpty()) {
            entry.put("alternatives", JSONArray(line.alternatives.map {
                JSONObject().put("position", it.position).put("digit", it.digit.toString())
                    .put("probability", it.probability.toDouble()).put("chose", it.chose.toDouble())
            }))
        }
        rawLines.put(entry)
    }
    val run = JSONObject()
        .put("engine", tier)
        .put("language", language.name.lowercase())
        .put("infer_ms", elapsedMs)
        .put("flatten_ms", flattenMs)
        .put("flatten_backend", flattenBackend)
        .put("levelled", levelled)
        .put("point_width", pointWidth)
        .put("point_height", pointHeight)
        .put("lines", rawLines)
    corners?.let { run.put("corners", JSONArray(it.map(Float::toDouble))) }
    try {
        original?.let { source ->
            File(folder, "original.jpg").outputStream().use {
                source.compress(Bitmap.CompressFormat.JPEG, PHOTO_QUALITY, it)
            }
        }
        File(folder, "photo.jpg").outputStream().use {
            photo.compress(Bitmap.CompressFormat.JPEG, PHOTO_QUALITY, it)
        }
        File(folder, "ocr.json").writeText(run.toString())
    } catch (error: IOException) {
        Log.w(TAG, "could not save run", error)
    }
}
