package local.ocrbridge

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.CheckBox
import android.widget.RadioGroup
import android.widget.TextView
import java.io.File

/** Exercises the actual gallery-result -> review -> confirmed OCR route on an emulator. */
class PhotoReviewSmoke : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 60_000
        while (SystemClock.uptimeMillis() < deadline) {
            var okay = false
            runOnMainSync { okay = condition() }
            if (okay) return
            Thread.sleep(100)
        }
        error("Timed out: $message")
    }

    private fun touch(view: View, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(0, SystemClock.uptimeMillis(), action, x, y, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun capture(name: String) {
        val screenshot = uiAutomation.takeScreenshot()
        File(targetContext.getExternalFilesDir(null), name).outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }

    override fun onStart() {
        val report = Bundle()
        try {
            geometryChecks()
            val file = File(targetContext.getExternalFilesDir(null), "smoke.jpg")
            check(file.exists()) { "Push a receipt to files/smoke.jpg first" }
            val main = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            // Deliver the same callback the system picker uses, with a real local photo URI.
            val callback = MainActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Intent::class.java).apply { isAccessible = true }
            val monitor = addMonitor(PhotoReviewActivity::class.java.name, null, false)
            runOnMainSync { callback.invoke(main, 2, Activity.RESULT_OK, Intent().setData(Uri.fromFile(file))) }
            val review = waitForMonitorWithTimeout(monitor, 10_000) as? PhotoReviewActivity ?: error("Review did not open")
            removeMonitor(monitor)
            val next = review.findViewById<View>(R.id.reviewContinue)
            val crop = review.findViewById<QuadCropView>(R.id.reviewImage)
            await("photo import") { next.isEnabled }
            var fingerX = 0f
            var fingerY = 0f
            runOnMainSync {
                check(main.findViewById<View>(R.id.result).visibility != View.VISIBLE) { "OCR ran before approval" }
                // Drag a real corner by dispatching touch events in the crop view.
                val bitmap = loadReceiptPhoto(targetContext, Uri.fromFile(file), maxSide = 1200)
                val scale = minOf((crop.width - 48f * review.resources.displayMetrics.density) / bitmap.width,
                    (crop.height - 48f * review.resources.displayMetrics.density) / bitmap.height)
                val originalX = crop.corners[0]
                val originalY = crop.corners[1]
                val x = (crop.width - bitmap.width * scale) / 2 + originalX * scale
                val y = (crop.height - bitmap.height * scale) / 2 + originalY * scale
                touch(crop, MotionEvent.ACTION_DOWN, x + 20, y + 20)
                touch(crop, MotionEvent.ACTION_MOVE, x + 28, y + 28)
                fingerX = x + 28; fingerY = y + 28
                check(crop.corners[0] > 0f && crop.corners[1] > 0f)
                check(kotlin.math.abs((crop.corners[0] - originalX) * scale - 8f) < 0.1f) { "Corner jumped to the finger" }
                bitmap.recycle()
            }
            waitForIdleSync()
            capture("review-magnifier.png")
            runOnMainSync {
                val field = QuadCropView::class.java.getDeclaredField("lensBounds").apply { isAccessible = true }
                val lens = field.get(crop) as RectF
                check(lens.width() > 0 && lens.left >= 0 && lens.top >= 0 && lens.right <= crop.width && lens.bottom <= crop.height)
                check(!lens.contains(fingerX, fingerY)) { "Magnifier covers the finger" }
                touch(crop, MotionEvent.ACTION_UP, fingerX, fingerY)
                next.performClick()
                check(!next.isEnabled) { "Stale preview can be approved" }
            }
            await("perspective displayed before curve finishes") {
                review.findViewById<TextView>(R.id.reviewStatus).text == review.getString(R.string.review_curving) && !next.isEnabled
            }
            // At this stage the view must own a perspective-only image and no approvable output.
            runOnMainSync {
                val field = PhotoReviewActivity::class.java.getDeclaredField("output").apply { isAccessible = true }
                check(field.get(review) == null)
            }
            capture("review-perspective.png")
            await("UVDoc preview") { next.isEnabled }
            capture("review-preview.png")
            runOnMainSync { check(review.findViewById<View>(R.id.reviewStatus).visibility == View.GONE) }
            runOnMainSync {
                review.findViewById<RadioGroup>(R.id.reviewBackend).check(R.id.reviewDewarpNet)
                check(!next.isEnabled)
            }
            await("DewarpNet preview") { next.isEnabled }
            runOnMainSync { review.findViewById<CheckBox>(R.id.reviewFlatten).isChecked = false }
            await("perspective only") { next.isEnabled }
            runOnMainSync { review.findViewById<View>(R.id.reviewEdit).performClick() }
            runOnMainSync {
                check(crop.corners[0] > 0) { "Corners lost when returning to crop" }
                review.findViewById<View>(R.id.reviewEdit).performClick() // reset
                check(crop.corners[0] == 0f)
                next.performClick()
            }
            await("preview after reset") { next.isEnabled }
            runOnMainSync {
                review.findViewById<RadioGroup>(R.id.reviewBackend).check(R.id.reviewDewarpNet)
                review.findViewById<CheckBox>(R.id.reviewFlatten).isChecked = true
            }
            await("DewarpNet before approval") { next.isEnabled }
            // Recreate while reviewing; source and corners must survive without the picker grant.
            val recreateMonitor = addMonitor(PhotoReviewActivity::class.java.name, null, false)
            runOnMainSync { review.recreate() }
            val recreated = waitForMonitorWithTimeout(recreateMonitor, 10_000) as? PhotoReviewActivity
                ?: error("Review recreation failed")
            removeMonitor(recreateMonitor)
            await("preview restoration") { recreated.findViewById<View>(R.id.reviewContinue).isEnabled }
            val approvedField = PhotoReviewActivity::class.java.getDeclaredField("output").apply { isAccessible = true }
            val approved = approvedField.get(recreated) as Bitmap
            runOnMainSync { recreated.findViewById<View>(R.id.reviewContinue).performClick() }
            await("confirmed OCR") { main.findViewById<View>(R.id.result).visibility == View.VISIBLE }
            runOnMainSync {
                val raw = main.findViewById<TextView>(R.id.raw).text.toString()
                check(raw.isNotBlank())
                val field = BoxImageView::class.java.getDeclaredField("bitmap").apply { isAccessible = true }
                val displayed = field.get(main.findViewById<BoxImageView>(R.id.preview)) as Bitmap
                check(displayed.width == approved.width && displayed.height == approved.height)
                for (y in 0 until approved.height step 97) for (x in 0 until approved.width step 71) {
                    check(displayed.getPixel(x, y) == approved.getPixel(x, y)) { "Approved image was processed again" }
                }
                check(main.findViewById<CheckBox>(R.id.flatten).isChecked)
                check(main.findViewById<RadioGroup>(R.id.flattenModel).checkedRadioButtonId == R.id.dewarpnet)
                report.putString("ocr_status", main.findViewById<TextView>(R.id.status).text.toString())
            }
            // Retry route must reopen the system picker instead of submitting the old photo.
            val retryMonitor = addMonitor(PhotoReviewActivity::class.java.name, null, false)
            runOnMainSync { callback.invoke(main, 2, Activity.RESULT_OK, Intent().setData(Uri.fromFile(file))) }
            val retry = waitForMonitorWithTimeout(retryMonitor, 10_000) as PhotoReviewActivity
            removeMonitor(retryMonitor)
            val picker = addMonitor(android.content.IntentFilter(Intent.ACTION_GET_CONTENT).apply {
                addDataType("image/*")
            }, ActivityResult(Activity.RESULT_CANCELED, null), true)
            runOnMainSync { retry.findViewById<View>(R.id.reviewPickAgain).performClick() }
            await("reselect requested") { picker.hits > 0 }
            removeMonitor(picker)
            // Retake is intercepted so no camera is operated during the test.
            val retakeMonitor = addMonitor(PhotoReviewActivity::class.java.name, null, false)
            runOnMainSync { callback.invoke(main, 2, Activity.RESULT_OK, Intent().setData(Uri.fromFile(file))) }
            val retake = waitForMonitorWithTimeout(retakeMonitor, 10_000) as PhotoReviewActivity
            removeMonitor(retakeMonitor)
            val camera = addMonitor(android.content.IntentFilter(android.provider.MediaStore.ACTION_IMAGE_CAPTURE), ActivityResult(Activity.RESULT_CANCELED, null), true)
            runOnMainSync { retake.findViewById<View>(R.id.reviewRetake).performClick() }
            await("retake requested") { camera.hits > 0 }
            removeMonitor(camera)
            report.putString("checks", "perspective geometry, offset magnifier, perspective displayed before curve, both models, curve off, reset, recreation, approved pixels preserved through OCR, reselect, retake")
            finish(Activity.RESULT_OK, report)
        } catch (error: Throwable) {
            report.putString("error", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, report)
        }
    }

    private fun geometryChecks() {
        val pixels = IntArray(40 * 60) { Color.rgb((it % 40) * 5, (it / 40) * 3, 40) }
        val bitmap = Bitmap.createBitmap(pixels, 40, 60, Bitmap.Config.ARGB_8888)
        val flat = Perspective.rectify(bitmap, Perspective.fullImage(40, 60))
        check(flat.width == 40 && flat.height == 60)
        check(flat.getPixel(0, 0) == bitmap.getPixel(0, 0))
        val quad = floatArrayOf(4f, 3f, 35f, 8f, 39f, 57f, 0f, 50f)
        check(Perspective.valid(quad))
        check(!Perspective.valid(floatArrayOf(0f, 0f, 39f, 59f, 39f, 0f, 0f, 59f)))
        val corrected = Perspective.rectify(bitmap, quad)
        check(corrected.width > 0 && corrected.height > 0)
        corrected.recycle(); flat.recycle(); bitmap.recycle()
    }
}
