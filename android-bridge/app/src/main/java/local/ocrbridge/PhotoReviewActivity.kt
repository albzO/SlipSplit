package local.ocrbridge

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioGroup
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Owns the imported photo until the user approves a fully processed preview. No OCR here. */
class PhotoReviewActivity : Activity() {
    companion object {
        const val BACKEND = "flatten_backend"
        const val FLATTEN_MS = "flatten_ms"
        const val ORIGINAL = "original"
        const val ACTION = "review_action"
        const val RETAKE = "retake"
        const val PICK = "pick"
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val edgeWorker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var image: QuadCropView
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var next: Button
    private lateinit var edit: Button
    private lateinit var autoDetect: Button
    private lateinit var flatten: CheckBox
    private lateinit var backend: RadioGroup
    private lateinit var folder: File
    private lateinit var sourceFile: File
    private var source: Bitmap? = null
    private var fullSource: Bitmap? = null
    private var cornerRevision = 0
    private var manualCorners = false
    private var sourceFingerprint = "pending"
    private var savedPointWidth = 0
    private var savedPointHeight = 0
    private var output: Bitmap? = null
    private var points: FloatArray? = null
    private var previewing = false
    private var returningPhoto = false
    @Volatile private var generation = 0
    private var flattenMs = 0L
    private var processedBackend = "none"
    private var pending: Runnable? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_photo_review)
        image = findViewById(R.id.reviewImage)
        title = findViewById(R.id.reviewTitle)
        status = findViewById(R.id.reviewStatus)
        showStatus(R.string.review_loading)
        next = findViewById(R.id.reviewContinue)
        edit = findViewById(R.id.reviewEdit)
        autoDetect = findViewById(R.id.reviewAutoDetect)
        flatten = findViewById(R.id.reviewFlatten)
        backend = findViewById(R.id.reviewBackend)
        val initial = state?.getString(BACKEND) ?: intent.getStringExtra(BACKEND) ?: "dewarpnet"
        flatten.isChecked = initial != "none"
        backend.check(when (initial) {
            "uvdoc" -> R.id.reviewUvdoc
            else -> R.id.reviewDewarpNet
        })
        folder = state?.getString("folder")?.let(::File)
            ?: File(cacheDir, "photos/review-${UUID.randomUUID()}")
        // Accept already-imported photos when restoring an older review session.
        sourceFile = File(folder, "original.png").takeIf { it.exists() } ?: File(folder, "source.image")
        points = state?.getFloatArray("corners")
        manualCorners = state?.getBoolean("manualCorners") ?: false
        savedPointWidth = state?.getInt("pointWidth") ?: 0
        savedPointHeight = state?.getInt("pointHeight") ?: 0
        val restorePreview = state?.getBoolean("preview") ?: false
        flatten.setOnCheckedChangeListener { _, _ -> if (previewing) updatePreview() }
        backend.setOnCheckedChangeListener { _, _ -> if (previewing) updatePreview() }
        next.setOnClickListener { if (previewing) approve() else beginPreview() }
        edit.setOnClickListener {
            if (previewing) showCrop() else source?.let {
                cornerRevision++
                manualCorners = true
                points = Perspective.fullImage(it.width, it.height)
                image.show(it, true, points)
                status.visibility = View.GONE
            }
        }
        image.onCornerInteraction = {
            cornerRevision++
            manualCorners = true
            if (!previewing) status.visibility = View.GONE
        }
        autoDetect.setOnClickListener { detectCorners() }
        findViewById<View>(R.id.reviewRetake).setOnClickListener { retry(RETAKE) }
        findViewById<View>(R.id.reviewPickAgain).setOnClickListener { retry(PICK) }
        next.isEnabled = false; edit.isEnabled = false; autoDetect.isEnabled = false
        worker.execute {
            try {
                val start = System.nanoTime()
                folder.mkdirs()
                // Open the system provider once. Preserve its bytes without PNG recompression.
                if (!sourceFile.exists()) {
                    val partial = File.createTempFile("source-", ".import", folder)
                    try {
                        requireNotNull(contentResolver.openInputStream(requireNotNull(intent.data)))
                            .use { input -> partial.outputStream().use { input.copyTo(it) } }
                        check(partial.renameTo(sourceFile) || sourceFile.exists()) { "无法导入照片" }
                    } finally {
                        partial.delete()
                    }
                }
                val copied = System.nanoTime()
                val photo = loadReceiptPhoto(this, Uri.fromFile(sourceFile), maxSide = 1200)
                val decoded = System.nanoTime()
                if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                    val digest = MessageDigest.getInstance("SHA-256")
                    sourceFile.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                        }
                    }
                    sourceFingerprint = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                } else sourceFingerprint = "release"
                val colorSpace = if (Build.VERSION.SDK_INT >= 26) photo.colorSpace?.name else "legacy"
                Log.d("PhotoReview", "edge_version=${DocumentBoundaryDetector.VERSION}, input_sha256=$sourceFingerprint, preview=${photo.width}x${photo.height}, colors=$colorSpace")
                // Older sessions stored points against the full normalized PNG.
                val oldBounds = if (savedPointWidth == 0 && points != null && sourceFile.name == "original.png") {
                    BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                        BitmapFactory.decodeFile(sourceFile.path, this)
                    }
                } else null
                val priorWidth = savedPointWidth.takeIf { it > 0 } ?: oldBounds?.outWidth ?: photo.width
                val priorHeight = savedPointHeight.takeIf { it > 0 } ?: oldBounds?.outHeight ?: photo.height
                Log.d("PhotoReview", "import_ms=${(copied - start) / 1_000_000}, preview_ms=${(decoded - copied) / 1_000_000}, fingerprint_ms=${(System.nanoTime() - decoded) / 1_000_000}")
                main.post {
                    if (isFinishing || isDestroyed) return@post
                    source = photo
                    val restored = points?.takeIf(Perspective::valid)?.let { saved ->
                        FloatArray(8) { i ->
                            if (i % 2 == 0) saved[i] * (photo.width - 1f) / (priorWidth - 1f)
                            else saved[i] * (photo.height - 1f) / (priorHeight - 1f)
                        }
                    }
                    points = restored
                    image.show(photo, true, restored)
                    image.isEnabled = true
                    next.isEnabled = true; edit.isEnabled = true
                    if (restored == null || !manualCorners) {
                        // Recompute automatic corners on every fresh Activity. Restoring an
                        // old auto-frame must not look like a new successful detection.
                        detectCorners(restorePreview)
                    } else {
                        Log.d("PhotoReview", "edge_mode=restore_manual, input_sha256=$sourceFingerprint, corners=${restored.contentToString()}")
                        status.visibility = View.GONE
                        next.isEnabled = true; edit.isEnabled = true; autoDetect.isEnabled = true
                        if (restorePreview) beginPreview()
                    }
                }
            } catch (error: Exception) {
                previewFailed(generation, error)
            }
        }
    }

    /** Runs on a reduced image; never overwrites saved/manual corners on recreation. */
    private fun detectCorners(previewAfter: Boolean = false) {
        val photo = source ?: return
        val revision = ++generation
        val manualRevision = cornerRevision
        manualCorners = false
        autoDetect.isEnabled = false
        showStatus(R.string.review_detecting)
        edgeWorker.execute {
            val start = System.nanoTime()
            var unavailable = false
            val detected = try {
                PaperBoundary.detect(this, photo)
            } catch (error: Exception) {
                Log.w("PhotoReview", "Edge detection failed", error)
                unavailable = true
                null
            } catch (error: LinkageError) {
                Log.w("PhotoReview", "OpenCV load failed", error)
                unavailable = true
                null
            }
            Log.d("PhotoReview", "edge_version=${PaperBoundary.VERSION}, edge_mode=auto, input_sha256=$sourceFingerprint, edges_ms=${(System.nanoTime() - start) / 1_000_000}, method=${detected?.method}, corners=${detected?.corners?.contentToString()}")
            main.post {
                if (!isCurrent(revision)) return@post
                autoDetect.isEnabled = true
                if (cornerRevision != manualRevision) {
                    // The user has started adjusting: never move the handles under their finger.
                    status.visibility = View.GONE
                    return@post
                }
                points = detected?.corners ?: Perspective.fullImage(photo.width, photo.height)
                image.show(photo, true, points)
                image.isEnabled = true
                next.isEnabled = true; edit.isEnabled = true; autoDetect.isEnabled = true
                if (detected == null) {
                    showStatus(if (unavailable) R.string.review_edges_unavailable else R.string.review_no_edges)
                } else status.visibility = View.GONE
                if (previewAfter) beginPreview()
            }
        }
    }

    private fun selectedBackend() = if (!flatten.isChecked) "none"
        else when (backend.checkedRadioButtonId) {
            R.id.reviewUvdoc -> "uvdoc"
            else -> "dewarpnet"
        }

    private fun beginPreview() {
        if (source == null) return
        points = image.corners.copyOf()
        previewing = true
        title.setText(R.string.review_preview_title)
        edit.setText(R.string.review_adjust)
        next.setText(R.string.review_accept)
        findViewById<View>(R.id.reviewOptions).visibility = View.VISIBLE
        autoDetect.visibility = View.GONE
        updatePreview()
    }

    private fun showCrop() {
        generation++
        pending?.let(main::removeCallbacks)
        previewing = false
        next.isEnabled = true
        title.setText(R.string.review_crop_title)
        edit.setText(R.string.review_reset)
        next.setText(R.string.review_preview)
        findViewById<View>(R.id.reviewOptions).visibility = View.GONE
        autoDetect.visibility = View.VISIBLE
        autoDetect.isEnabled = true
        image.show(requireNotNull(source), true, points)
        image.isEnabled = true
        status.visibility = View.GONE
    }

    /** Debounce control changes; only the latest result can be approved. */
    private fun updatePreview() {
        val revision = ++generation
        val photo = source ?: return
        val corners = points?.copyOf() ?: return
        val mode = selectedBackend()
        pending?.let(main::removeCallbacks)
        next.isEnabled = false
        output = null
        image.isEnabled = false
        showStatus(R.string.review_processing)
        pending = Runnable {
            worker.execute {
                if (revision != generation || isDestroyed) return@execute
                try {
                    val start = System.nanoTime()
                    val original = fullSource ?: loadReceiptPhoto(this, Uri.fromFile(sourceFile)).also { fullSource = it }
                    val fullCorners = FloatArray(8) { index ->
                        if (index % 2 == 0) corners[index] * (original.width - 1f) / (photo.width - 1f)
                        else corners[index] * (original.height - 1f) / (photo.height - 1f)
                    }
                    val rectified = Perspective.rectify(original, fullCorners)
                    main.post {
                        if (!isCurrent(revision)) {
                            rectified.recycle()
                            return@post
                        }
                        // Render perspective correction first. The draw callback schedules
                        // curve inference after this frame, rather than hiding both behind a wait.
                        image.show(rectified, false, onDisplayed = {
                            if (isCurrent(revision)) {
                                if (mode == "none") {
                                    completePreview(revision, mode, rectified, start)
                                } else {
                                    showStatus(R.string.review_curving)
                                    worker.execute {
                                        if (isCurrent(revision)) {
                                            try {
                                                val flat = when (mode) {
                                                    "dewarpnet" -> DewarpNet(this).use { it.flatten(rectified) }
                                                    "uvdoc" -> Dewarper(this).use { it.flatten(rectified) }
                                                    else -> error("未知曲面矫正方案")
                                                }
                                                // The view owns rectified until the final image is shown.
                                                // Let GC reclaim it; recycling here would race drawing.
                                                main.post { completePreview(revision, mode, flat, start) }
                                            } catch (error: Exception) {
                                                previewFailed(revision, error)
                                            }
                                        }
                                    }
                                }
                            }
                        })
                        image.isEnabled = true
                    }
                } catch (error: Exception) {
                    previewFailed(revision, error)
                }
            }
        }.also { main.postDelayed(it, 180) }
    }

    private fun isCurrent(revision: Int) = revision == generation && !isFinishing && !isDestroyed

    private fun completePreview(revision: Int, mode: String, bitmap: Bitmap, start: Long) {
        if (!isCurrent(revision)) return
        output = bitmap
        processedBackend = mode
        flattenMs = (System.nanoTime() - start) / 1_000_000
        image.show(bitmap, false)
        status.visibility = View.GONE
        next.isEnabled = true
    }

    private fun showStatus(message: Int) {
        status.setText(message)
        status.visibility = View.VISIBLE
    }

    private fun previewFailed(revision: Int, error: Exception) {
        main.post {
            if (isCurrent(revision)) {
                status.text = getString(R.string.status_error, error.message)
                status.visibility = View.VISIBLE
                image.isEnabled = true
                next.isEnabled = false
            }
        }
    }

    private fun approve() {
        if (returningPhoto) return
        val approved = output ?: return
        val previewSource = source ?: return
        val acceptedCorners = points?.copyOf() ?: return
        val original = FileProvider.getUriForFile(this, "$packageName.files", sourceFile)
        returningPhoto = true
        next.isEnabled = false
        setResult(RESULT_OK, Intent().setData(original)
            .putExtra(ApprovedPhotoHandoff.TOKEN, ApprovedPhotoHandoff.put(approved))
            .putExtra(ApprovedPhotoHandoff.CORNERS, acceptedCorners)
            .putExtra(ApprovedPhotoHandoff.POINT_WIDTH, previewSource.width)
            .putExtra(ApprovedPhotoHandoff.POINT_HEIGHT, previewSource.height)
            .putExtra(ORIGINAL, original.toString())
            .putExtra(BACKEND, processedBackend).putExtra(FLATTEN_MS, flattenMs))
        finish()
    }

    private fun retry(action: String) {
        setResult(RESULT_FIRST_USER, Intent().putExtra(ACTION, action))
        finish()
    }

    @Deprecated("Uses the legacy Activity back callback")
    override fun onBackPressed() {
        if (returningPhoto) return
        if (previewing) showCrop() else super.onBackPressed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("folder", folder.path)
        outState.putFloatArray("corners", if (previewing || source == null) points else image.corners)
        outState.putBoolean("manualCorners", manualCorners)
        outState.putInt("pointWidth", source?.width ?: savedPointWidth)
        outState.putInt("pointHeight", source?.height ?: savedPointHeight)
        outState.putBoolean("preview", previewing)
        outState.putString(BACKEND, selectedBackend())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        generation++
        pending?.let(main::removeCallbacks)
        worker.shutdown()
        edgeWorker.shutdown()
        super.onDestroy()
    }
}
