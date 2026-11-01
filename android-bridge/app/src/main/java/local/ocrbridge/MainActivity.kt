package local.ocrbridge

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToLong

private const val REQUEST_CAMERA = 1
private const val REQUEST_GALLERY = 2
private const val STATE_PHOTO = "photo"
private const val MAX_SIDE = 2400
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
        findViewById<View>(R.id.rawToggle).setOnClickListener {
            raw.visibility = if (raw.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        photoUri?.let { outState.putString(STATE_PHOTO, it.toString()) }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
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
        if (resultCode != RESULT_OK) return
        val uri = if (requestCode == REQUEST_CAMERA) photoUri else data?.data
        uri?.let(::recognize)
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
        for (group in listOf(model, language)) {
            for (index in 0 until group.childCount) group.getChildAt(index).isEnabled = !busy
        }
    }

    private fun recognize(uri: Uri) {
        val tier = selectedTier()
        val receiptLanguage = selectedLanguage()
        setBusy(true)
        result.visibility = View.GONE
        status.text = getString(R.string.status_reading)
        worker.execute {
            try {
                val bitmap = loadUpright(uri)
                main.post { preview.show(bitmap, emptyList()) }
                val ocr = ocrFor(tier)
                val start = System.nanoTime()
                val lines = ocr.recognize(bitmap, receiptLanguage)
                val elapsed = (System.nanoTime() - start) / 1_000_000
                val receipt = parseReceipt(lines)
                saveRun(this, bitmap, lines, tier, receiptLanguage, elapsed)
                main.post { showResult(bitmap, lines, receipt, elapsed) }
            } catch (error: Throwable) {
                main.post { showError(error) }
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

    /** Decodes the photo at most MAX_SIDE long and applies its EXIF rotation. */
    private fun loadUpright(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = contentResolver.openInputStream(uri)
            .use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("not an image")
        val rotation = contentResolver.openInputStream(uri)
            ?.use { ExifInterface(it).rotationDegrees }
            ?: 0
        val scale = minOf(1f, MAX_SIDE.toFloat() / max(decoded.width, decoded.height))
        if (rotation == 0 && scale == 1f) return decoded
        val matrix = android.graphics.Matrix().apply {
            postScale(scale, scale)
            postRotate(rotation.toFloat())
        }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
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

    private fun showResult(bitmap: Bitmap, lines: List<OcrLine>, receipt: Receipt, elapsed: Long) {
        setBusy(false)
        preview.show(bitmap, colorBoxes(lines, receipt))
        status.text = getString(R.string.status_done, elapsed / 1000.0, lines.size)
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
        status.text = getString(R.string.status_error, error.message ?: error.toString())
    }
}
