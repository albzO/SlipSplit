package local.ocrbridge

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale
import java.util.Date
import java.text.SimpleDateFormat
import java.util.concurrent.Executors
import kotlin.math.roundToLong

private const val REQUEST_CAMERA = 1
private const val REQUEST_GALLERY = 2
private const val REQUEST_REVIEW = 4
private const val STATE_PHOTO = "photo"
private const val WHOLE_AMOUNT_FROM = 1000.0

/** Takes or picks a receipt photo, recognizes it on the device and shows what it read. */
class MainActivity : Activity() {
    private var loadedOcr: PpOcr? = null
    private var loadedTier: String? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var photoUri: Uri? = null

    private lateinit var camera: Button
    private lateinit var gallery: Button
    private lateinit var model: RadioGroup
    private lateinit var language: RadioGroup
    private lateinit var flatten: CheckBox
    private lateinit var flattenModel: RadioGroup
    private lateinit var exportData: Button
    private lateinit var status: TextView
    private lateinit var preview: BoxImageView
    private lateinit var result: View
    private lateinit var check: TextView
    private lateinit var company: TextView
    private lateinit var printedAt: TextView
    private lateinit var items: LinearLayout
    private lateinit var totals: LinearLayout
    private lateinit var droppedTitle: View
    private lateinit var dropped: LinearLayout
    private lateinit var raw: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        camera = findViewById(R.id.camera)
        gallery = findViewById(R.id.gallery)
        model = findViewById(R.id.model)
        language = findViewById(R.id.language)
        flatten = findViewById(R.id.flatten)
        flattenModel = findViewById(R.id.flattenModel)
        status = findViewById(R.id.status)
        preview = findViewById(R.id.preview)
        result = findViewById(R.id.result)
        check = findViewById(R.id.check)
        company = findViewById(R.id.company)
        printedAt = findViewById(R.id.printedAt)
        items = findViewById(R.id.items)
        totals = findViewById(R.id.totals)
        droppedTitle = findViewById(R.id.droppedTitle)
        dropped = findViewById(R.id.dropped)
        raw = findViewById(R.id.raw)
        photoUri = savedInstanceState?.getString(STATE_PHOTO)?.let(Uri::parse)
        camera.setOnClickListener { takePhoto() }
        gallery.setOnClickListener { pickPhoto() }
        exportData = findViewById(R.id.exportData)
        exportData.setOnClickListener { shareRuns() }
        findViewById<View>(R.id.rawToggle).setOnClickListener {
            raw.visibility = if (raw.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        // Load native code while the user is still choosing/taking a picture.
        worker.execute { DocumentBoundaryDetector.prepare() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        photoUri?.let { outState.putString(STATE_PHOTO, it.toString()) }
    }

    override fun onDestroy() {
        worker.execute {
            loadedOcr?.close()
        }
        worker.shutdown()
        super.onDestroy()
    }

    /** Zips the run log off the main thread, then lets the user pick where it goes. */
    private fun shareRuns() {
        val runs = getExternalFilesDir("runs") ?: return
        setBusy(true)
        status.text = getString(R.string.export_packing)
        status.visibility = View.VISIBLE
        worker.execute {
            try {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val archive = RunExport.pack(runs, File(cacheDir, "exports"), stamp)
                main.post {
                    if (isDestroyed) return@post
                    setBusy(false)
                    if (archive == null) {
                        status.text = getString(R.string.export_empty)
                        return@post
                    }
                    status.visibility = View.GONE
                    val uri = FileProvider.getUriForFile(this, "$packageName.files", archive)
                    val send = Intent(Intent.ACTION_SEND).setType("application/zip")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    send.clipData = ClipData.newRawUri(archive.name, uri)
                    startActivity(Intent.createChooser(send, getString(R.string.export_title)))
                }
            } catch (error: Exception) {
                main.post { if (!isDestroyed) showError(error) }
            }
        }
    }

    private fun takePhoto() {
        val file = File(cacheDir, "photos/receipt.jpg").apply { parentFile?.mkdirs() }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        photoUri = uri
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        startActivityForResult(intent, REQUEST_CAMERA)
    }

    private fun pickPhoto() {
        startActivityForResult(
            Intent(Intent.ACTION_GET_CONTENT).setType("image/*"),
            REQUEST_GALLERY,
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_REVIEW) {
            if (resultCode == RESULT_FIRST_USER) {
                when (data?.getStringExtra(PhotoReviewActivity.ACTION)) {
                    PhotoReviewActivity.RETAKE -> takePhoto()
                    PhotoReviewActivity.PICK -> pickPhoto()
                }
            } else if (resultCode == RESULT_OK) {
                data?.let(::recognize)
            }
            return
        }
        if (resultCode != RESULT_OK) return
        val uri = when (requestCode) {
            REQUEST_CAMERA -> photoUri
            REQUEST_GALLERY -> data?.data
            else -> null
        }
        uri?.let(::reviewPhoto)
    }

    private fun reviewPhoto(uri: Uri) {
        val backend = if (!flatten.isChecked) "none"
            else when (flattenModel.checkedRadioButtonId) {
                R.id.dewarpnet -> "dewarpnet"
                else -> "uvdoc"
            }
        // Release the last OCR session before the review screen loads its dewarping model.
        worker.execute {
            loadedOcr?.close(); loadedOcr = null; loadedTier = null
        }
        startActivityForResult(Intent(this, PhotoReviewActivity::class.java).setData(uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra(PhotoReviewActivity.BACKEND, backend), REQUEST_REVIEW)
    }

    private fun selectedTier() = when (model.checkedRadioButtonId) {
        R.id.tiny -> "tiny"
        R.id.medium -> "medium"
        else -> "small"
    }

    private fun selectedLanguage() = when (language.checkedRadioButtonId) {
        R.id.english -> ReceiptLanguage.ENGLISH
        R.id.chinese -> ReceiptLanguage.CHINESE
        else -> ReceiptLanguage.AUTO
    }

    private fun setBusy(busy: Boolean) {
        camera.isEnabled = !busy
        gallery.isEnabled = !busy
        exportData.isEnabled = !busy
        flatten.isEnabled = !busy
        for (group in listOf(model, language, flattenModel)) {
            for (index in 0 until group.childCount) group.getChildAt(index).isEnabled = !busy
        }
    }

    private fun recognize(approved: Intent) {
        val tier = selectedTier()
        val receiptLanguage = selectedLanguage()
        val backend = approved.getStringExtra(PhotoReviewActivity.BACKEND) ?: "none"
        val flattenMs = approved.getLongExtra(PhotoReviewActivity.FLATTEN_MS, 0)
        val originalUri = approved.getStringExtra(PhotoReviewActivity.ORIGINAL)?.let(Uri::parse)
        flatten.isChecked = backend != "none"
        flattenModel.check(when (backend) {
            "dewarpnet" -> R.id.dewarpnet
            else -> R.id.uvdoc
        })
        setBusy(true)
        result.visibility = View.GONE
        status.text = getString(R.string.status_reading)
        status.visibility = View.VISIBLE
        val inMemory = ApprovedPhotoHandoff.take(approved.getStringExtra(ApprovedPhotoHandoff.TOKEN))
        inMemory?.let { preview.show(it, emptyList()) }
        worker.execute {
            try {
                // Review already applied both corrections. OCR must use those exact pixels.
                val bitmap = inMemory ?: ApprovedPhotoHandoff.restore(this, approved)
                main.post { if (!isDestroyed) preview.show(bitmap, emptyList()) }
                val ocr = ocrFor(tier)
                val start = System.nanoTime()
                val read = readLevelled(ocr, bitmap, receiptLanguage)
                val elapsed = (System.nanoTime() - start) / 1_000_000
                val lines = read.lines
                val photo = read.photo
                val receipt = read.receipt
                main.post { if (!isDestroyed) showResult(photo, lines, receipt, elapsed, flattenMs) }
                // Diagnostic persistence must not delay recognition or its visible result.
                try {
                    val original = originalUri?.let { loadReceiptPhoto(this, it) }
                    try {
                        saveRun(this, photo, lines, tier, receiptLanguage, elapsed, flattenMs, backend, original,
                            read.levelled, approved.getFloatArrayExtra(ApprovedPhotoHandoff.CORNERS),
                            approved.getIntExtra(ApprovedPhotoHandoff.POINT_WIDTH, 0),
                            approved.getIntExtra(ApprovedPhotoHandoff.POINT_HEIGHT, 0))
                    } finally {
                        original?.recycle()
                    }
                } catch (error: Throwable) {
                    Log.w("RunLog", "could not save diagnostic images", error)
                }
            } catch (error: Throwable) {
                main.post { if (!isDestroyed) showError(error) }
            }
        }
    }

    /** Keeps one model in memory; Medium alone needs most of a gigabyte. */
    private fun ocrFor(tier: String): PpOcr {
        loadedOcr?.takeIf { loadedTier == tier }?.let { return it }
        loadedOcr?.close()
        loadedOcr = null
        return PpOcr(this, tier).also {
            loadedOcr = it
            loadedTier = tier
        }
    }

    private fun money(value: Double): String {
        // Whole amounts this large are currencies without cents, such as rupiah.
        val whole = value >= WHOLE_AMOUNT_FROM && value == value.roundToLong().toDouble()
        return if (whole) "%,d".format(value.roundToLong()) else "%,.2f".format(value)
    }

    private fun addRow(
        parent: LinearLayout,
        name: String,
        amount: String,
        bold: Boolean = false,
        item: ReceiptItem? = null,
        color: Int? = null,
    ) {
        val row = layoutInflater.inflate(R.layout.row_amount, parent, false)
        val left = row.findViewById<TextView>(R.id.name)
        val right = row.findViewById<TextView>(R.id.amount)
        left.text = name
        right.text = amount
        if (bold) {
            left.setTypeface(left.typeface, android.graphics.Typeface.BOLD)
            right.setTypeface(right.typeface, android.graphics.Typeface.BOLD)
        }
        color?.let { row.findViewById<View>(R.id.swatch).setBackgroundColor(it) }
        showIfSet(row.findViewById(R.id.sku), item?.sku.orEmpty())
        showIfSet(row.findViewById(R.id.spec), item?.spec.orEmpty())
        parent.addView(row)
    }

    private fun showIfSet(view: TextView, text: String) {
        view.text = text
        view.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Colors each item's boxes alike; totals share one color and unused text stays gray. */
    private fun colorBoxes(lines: List<OcrLine>, receipt: Receipt): List<ColoredBox> {
        val colors = HashMap<OcrLine, Int>()
        receipt.fieldLines.forEach { colors[it] = getColor(R.color.accent) }
        receipt.items.forEachIndexed { index, item ->
            item.lines.forEach { colors[it] = itemColor(index) }
        }
        return lines.map { ColoredBox(it.box, colors[it] ?: getColor(R.color.unused_box)) }
    }

    private fun itemColor(index: Int): Int {
        val palette = resources.getIntArray(R.array.item_colors)
        return palette[index % palette.size]
    }

    private fun showResult(
        bitmap: Bitmap,
        lines: List<OcrLine>,
        receipt: Receipt,
        elapsed: Long,
        flattenMs: Long,
    ) {
        setBusy(false)
        preview.show(bitmap, colorBoxes(lines, receipt))
        status.text = getString(R.string.status_done, flattenMs / 1000.0, elapsed / 1000.0, lines.size)
        showIfSet(company, receipt.company)
        showIfSet(printedAt, listOf(receipt.date, receipt.time).filter { it.isNotEmpty() }.joinToString(" "))
        val matches = sumMatches(receipt)
        check.setText(if (matches) R.string.check_ok else R.string.check_review)
        check.setBackgroundResource(if (matches) R.drawable.badge_ok else R.drawable.badge_review)
        items.removeAllViews()
        totals.removeAllViews()
        dropped.removeAllViews()
        receipt.dropped.forEach { addRow(dropped, it.name, money(it.price), item = it) }
        val hasDropped = if (receipt.dropped.isEmpty()) View.GONE else View.VISIBLE
        droppedTitle.visibility = hasDropped
        dropped.visibility = hasDropped
        receipt.items.forEachIndexed { index, item ->
            val name = if (item.qty > 1 && item.spec.isEmpty()) "${item.name} ×${item.qty}" else item.name
            addRow(items, name, money(item.price), item = item, color = itemColor(index))
        }
        if (receipt.items.isEmpty()) addRow(items, getString(R.string.no_items), "")
        receipt.subtotal?.let { addRow(totals, getString(R.string.subtotal), money(it)) }
        receipt.tax?.let { addRow(totals, getString(R.string.tax), money(it)) }
        receipt.service?.let { addRow(totals, getString(R.string.service), money(it)) }
        receipt.discount?.let { addRow(totals, getString(R.string.discount), "-" + money(it)) }
        addRow(
            totals,
            getString(R.string.total),
            receipt.total?.let(::money) ?: getString(R.string.unknown),
            bold = true,
        )
        raw.text = groupRows(lines).joinToString("\n") { it.text }
        raw.visibility = View.GONE
        result.visibility = View.VISIBLE
    }

    private fun showError(error: Throwable) {
        setBusy(false)
        status.visibility = View.VISIBLE
        status.text = getString(R.string.status_error, error.message ?: error.toString())
    }
}
