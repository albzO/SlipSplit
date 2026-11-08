package local.ocrbridge

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Runs corners, flattening, OCR and parsing on every original photo kept in the app's run log. */
class BoundarySmoke : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    private fun outline(canvas: Canvas, corners: FloatArray?, color: Int) {
        corners ?: return
        val path = Path()
        path.moveTo(corners[0], corners[1])
        for (i in 1..3) path.lineTo(corners[i * 2], corners[i * 2 + 1])
        path.close()
        canvas.drawPath(path, Paint().apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 7f
        })
    }

    override fun onStart() {
        val result = Bundle()
        try {
            // HyperOS freezes a background test process; keep the app in front without waiting on it.
            targetContext.startActivity(android.content.Intent(targetContext, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            val root = requireNotNull(targetContext.getExternalFilesDir(null))
            val out = File(root, "boundary").apply { mkdirs() }
            val reports = JSONArray()
            val runs = File(root, "runs").listFiles().orEmpty().filter { File(it, "original.jpg").exists() }.sortedBy { it.name }
            val segmenter = PaperSegmenter(targetContext)
            for (run in runs) {
                val photo = loadReceiptPhoto(targetContext, Uri.fromFile(File(run, "original.jpg")), maxSide = 1200)
                val start = System.nanoTime()
                val mask = segmenter.mask(photo)
                val masked = PaperBoundary.quad(mask, photo.width, photo.height)
                val maskMs = (System.nanoTime() - start) / 1_000_000
                val picture = photo.copy(Bitmap.Config.ARGB_8888, true)
                outline(Canvas(picture), masked, Color.GREEN)
                File(out, "${run.name}.jpg").outputStream().use { picture.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                val pixels = IntArray(mask.size) { Color.rgb((mask[it] * 255).toInt(), (mask[it] * 255).toInt(), (mask[it] * 255).toInt()) }
                val maskPicture = Bitmap.createBitmap(pixels, 320, 320, Bitmap.Config.ARGB_8888)
                File(out, "${run.name}-mask.png").outputStream().use { maskPicture.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val report = JSONObject().put("run", run.name).put("mask_ms", maskMs)
                    .put("masked", masked != null)
                if (masked != null) {
                    val full = loadReceiptPhoto(targetContext, Uri.fromFile(File(run, "original.jpg")))
                    val corners = FloatArray(8) { i ->
                        if (i % 2 == 0) masked[i] * (full.width - 1f) / (photo.width - 1f)
                        else masked[i] * (full.height - 1f) / (photo.height - 1f)
                    }
                    val rectified = Perspective.rectifyPadded(full, corners)
                    val flat = Dewarper(targetContext).use { it.flatten(rectified) }
                    val readStart = System.nanoTime()
                    // The app also keeps one large model loaded at a time.
                    val ocr = PpOcr(targetContext, "small")
                    val read = readLevelled(ocr, flat, ReceiptLanguage.AUTO)
                    ocr.close()
                    report.put("read_ms", (System.nanoTime() - readStart) / 1_000_000)
                        .put("levelled", read.levelled).put("total", read.receipt.total)
                        .put("sum", sumMatches(read.receipt))
                        .put("items", JSONArray(read.receipt.items.map { "${it.name} | ${it.spec} | ${it.price}" }))
                    File(out, "${run.name}-final.jpg").outputStream().use { read.photo.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    full.recycle(); rectified.recycle(); flat.recycle()
                }
                reports.put(report)
                picture.recycle(); maskPicture.recycle(); photo.recycle()
            }
            segmenter.close()
            File(out, "report.json").writeText(reports.toString(2))
            result.putString("comparison", reports.toString())
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("error", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
