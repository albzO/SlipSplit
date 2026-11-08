package local.ocrbridge

import android.app.Application
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

private const val PREVIEW_SIDE = 1200
private const val RECENT_LINES = 4
private const val ORGANIZE_MS = 700L
private const val REGION_MARGIN = 6
const val MAX_PEOPLE = 6
const val GROUP_TOTALS = -1
const val GROUP_NONE = -2
const val GROUP_CORRECTED = -3

/** A price the printed total showed was misread, for the user to check before splitting. */
data class Correction(val name: String, val readAs: Long, val fixed: Long)

enum class Stage { FLATTEN, DETECT, READ, LEVEL, ORGANIZE, DONE, FAILED }

/** What the recognition screen shows; boxes are in the photo's pixels. */
data class Recognition(
    val stage: Stage = Stage.FLATTEN,
    val pass: Int = 1,
    val twoPasses: Boolean = false,
    val photo: Bitmap? = null,
    val boxes: List<FloatArray> = emptyList(),
    val read: Int = 0,
    val recent: List<String> = emptyList(),
    /** Per box once organized: the item it belongs to, [GROUP_TOTALS] or [GROUP_NONE]. */
    val groups: List<Int> = emptyList(),
    val itemCount: Int = 0,
    val matches: Boolean = false,
    /** Prices fixed against the total; the screen waits for the user to check them. */
    val corrections: List<Correction> = emptyList(),
    val error: String? = null,
)

data class Person(val id: Int, val name: String)

/** An item being typed in; [itemId] is null for one the user is adding. */
data class Draft(val itemId: Long?, val name: String, val price: String)

private val MONEY = Regex("^[\$¥￥€£]?\\s*-?[0-9]+(?:[.,][0-9]{1,2})?$")

/** Holds one receipt from photo to split; OCR runs on a single worker so models load once. */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val settings = AppSettings(application)
    private val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var ocr: PpOcr? = null
    private var ocrTier: String? = null

    var tier by mutableStateOf(settings.tier)
        private set
    var language by mutableStateOf(settings.language)
        private set
    var saveRuns by mutableStateOf(settings.saveRuns)
        private set
    var uiLanguage by mutableStateOf(settings.uiLanguage)
        private set

    private var source: File? = null
    var preview by mutableStateOf<Bitmap?>(null)
        private set
    var corners by mutableStateOf<FloatArray?>(null)
        private set
    var detecting by mutableStateOf(false)
        private set
    var edgesFound by mutableStateOf(true)
        private set
    private var cornersTouched = false

    private val recognitionState = MutableStateFlow(Recognition())
    val recognition: StateFlow<Recognition> = recognitionState.asStateFlow()
    private var recognizing: Job? = null
    private var run = 0

    internal var reading by mutableStateOf<Reading?>(null)
        private set
    var store by mutableStateOf("")
        private set
    var printedAt by mutableStateOf("")
        private set
    val items = mutableStateListOf<BillItem>()
    val people = mutableStateListOf<Person>()
    var active by mutableIntStateOf(0)
    var adjustment by mutableDoubleStateOf(0.0)
        private set
    var printedTotal by mutableStateOf<Long?>(null)
        private set
    var draft by mutableStateOf<Draft?>(null)
        private set
    private var nextItemId = 0L
    private var nextPersonId = 0

    private val historyStore = HistoryStore(application)
    val history = mutableStateListOf<SavedBill>()
    /** The saved bill being edited, so finishing updates it instead of adding another. */
    private var historyId: String? = null

    init {
        resetPeople()
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { historyStore.load() }
            history.clear()
            history.addAll(saved)
        }
        // Load native code while the user is still taking the picture.
        viewModelScope.launch(worker) { DocumentBoundaryDetector.prepare() }
    }

    fun chooseTier(value: String) {
        tier = value
        settings.tier = value
    }

    fun chooseLanguage(value: ReceiptLanguage) {
        language = value
        settings.language = value
    }

    fun chooseUiLanguage(value: String) {
        uiLanguage = value
        settings.uiLanguage = value
    }

    fun chooseSaveRuns(value: Boolean) {
        saveRuns = value
        settings.saveRuns = value
    }

    /** Copies the photo in, since a picker's grant may not outlive this screen, then finds the paper. */
    fun importPhoto(uri: Uri) {
        preview = null
        corners = null
        cornersTouched = false
        detecting = true
        viewModelScope.launch {
            try {
                val (file, photo) = withContext(worker) {
                    val folder = File(app.cacheDir, "photos").apply { mkdirs() }
                    val file = File(folder, "source.image")
                    requireNotNull(app.contentResolver.openInputStream(uri)).use { input ->
                        file.outputStream().use { input.copyTo(it) }
                    }
                    file to loadReceiptPhoto(app, Uri.fromFile(file), PREVIEW_SIDE)
                }
                source = file
                preview = photo
                val found = withContext(Dispatchers.Default) {
                    try {
                        PaperBoundary.detect(app, photo)
                    } catch (error: Exception) {
                        Log.w("Crop", "edge detection failed", error)
                        null
                    } catch (error: LinkageError) {
                        Log.w("Crop", "OpenCV load failed", error)
                        null
                    }
                }
                edgesFound = found != null
                // Never move the handles under a finger that has already started adjusting.
                if (!cornersTouched) corners = found?.corners ?: Perspective.fullImage(photo.width, photo.height)
            } catch (error: Exception) {
                Log.w("Crop", "could not import photo", error)
                edgesFound = false
            } finally {
                detecting = false
            }
        }
    }

    fun touchCorners() {
        cornersTouched = true
    }

    /** Straightens and flattens the paper, reads it with progress, then sets up the bill. */
    fun recognize(chosen: FloatArray) {
        val file = source ?: return
        val small = preview ?: return
        corners = chosen.copyOf()
        recognizing?.cancel()
        val id = ++run
        val tier = tier
        val language = language
        recognitionState.value = Recognition()
        fun post(change: (Recognition) -> Recognition) {
            if (id == run) recognitionState.update(change)
        }
        recognizing = viewModelScope.launch {
            try {
                class Outcome(val original: Bitmap, val reading: Reading, val flattenMs: Long, val readMs: Long)
                val outcome = withContext(worker) {
                    val started = System.nanoTime()
                    val original = loadReceiptPhoto(app, Uri.fromFile(file))
                    val full = FloatArray(8) { i ->
                        if (i % 2 == 0) chosen[i] * (original.width - 1f) / (small.width - 1f)
                        else chosen[i] * (original.height - 1f) / (small.height - 1f)
                    }
                    val rectified = Perspective.rectifyPadded(original, full)
                    post { it.copy(photo = rectified) }
                    val flat = Dewarper(app).use { it.flatten(rectified) }
                    val flattenMs = (System.nanoTime() - started) / 1_000_000
                    post { it.copy(stage = Stage.DETECT, photo = flat) }
                    val engine = ocrFor(tier)
                    val readStart = System.nanoTime()
                    val reading = readLevelled(engine, flat, language) { event -> post { advance(it, event) } }
                    Outcome(original, reading, flattenMs, (System.nanoTime() - readStart) / 1_000_000)
                }
                if (id != run) return@launch
                val reading = outcome.reading
                val owner = HashMap<OcrLine, Int>()
                reading.receipt.fieldLines.forEach { owner[it] = GROUP_TOTALS }
                reading.receipt.items.forEachIndexed { index, item -> item.lines.forEach { owner[it] = index } }
                val corrected = reading.receipt.items.filter { it.readAs != null }
                for (item in corrected) {
                    val printed = String.format(Locale.US, "%.2f", item.readAs)
                    item.lines.lastOrNull { printed in it.text }?.let { owner[it] = GROUP_CORRECTED }
                }
                post {
                    it.copy(
                        stage = Stage.ORGANIZE,
                        photo = reading.photo,
                        boxes = reading.lines.map { line -> line.corners ?: boxCorners(line.box) },
                        groups = reading.lines.map { line -> owner[line] ?: GROUP_NONE },
                        itemCount = reading.receipt.items.size,
                        matches = sumMatches(reading.receipt),
                        corrections = corrected.map { item ->
                            Correction(item.name, toCents(item.readAs ?: item.price), toCents(item.price))
                        },
                    )
                }
                loadBill(reading)
                delay(ORGANIZE_MS)
                post { it.copy(stage = Stage.DONE) }
                if (saveRuns) {
                    viewModelScope.launch(worker) {
                        try {
                            saveRun(
                                app, reading.photo, reading.lines, tier, language, outcome.readMs,
                                outcome.flattenMs, "uvdoc", outcome.original, reading.levelled,
                                chosen, small.width, small.height,
                            )
                        } catch (error: Throwable) {
                            Log.w("RunLog", "could not save run", error)
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w("Recognize", "recognition failed", error)
                post { it.copy(stage = Stage.FAILED, error = error.message ?: error.toString()) }
            }
        }
    }

    private fun advance(state: Recognition, event: ReadEvent) = when (event) {
        is ReadEvent.Found -> state.copy(
            stage = Stage.READ, pass = event.pass, photo = event.photo,
            boxes = event.boxes, read = 0, recent = emptyList(),
        )
        is ReadEvent.Line -> state.copy(
            read = event.index + 1,
            recent = if (event.text.isBlank()) state.recent else (state.recent + event.text).takeLast(RECENT_LINES),
        )
        ReadEvent.Levelling -> state.copy(stage = Stage.LEVEL, twoPasses = true)
    }

    private fun boxCorners(box: IntArray) = floatArrayOf(
        box[0].toFloat(), box[1].toFloat(), box[2].toFloat(), box[1].toFloat(),
        box[2].toFloat(), box[3].toFloat(), box[0].toFloat(), box[3].toFloat(),
    )

    fun cancelRecognition() {
        run++
        recognizing?.cancel()
    }

    /** Keeps one model in memory; Medium alone needs most of a gigabyte. */
    private fun ocrFor(tier: String): PpOcr {
        ocr?.takeIf { ocrTier == tier }?.let { return it }
        ocr?.close()
        ocr = null
        return PpOcr(app, tier).also {
            ocr = it
            ocrTier = tier
        }
    }

    private fun loadBill(reading: Reading) {
        val receipt = reading.receipt
        this.reading = reading
        historyId = null
        store = receipt.company
        printedAt = listOf(receipt.date, receipt.time).filter { it.isNotEmpty() }.joinToString(" ")
        items.clear()
        for (item in receipt.items) {
            val name = if (item.qty > 1) "${item.name} ×${item.qty}" else item.name
            items.add(BillItem(nextItemId++, name, toCents(item.price), item.spec))
        }
        adjustment = billAdjustment(receipt)
        printedTotal = receipt.total?.let(::toCents)
        resetPeople()
    }

    private fun resetPeople() {
        people.clear()
        nextPersonId = 0
        repeat(2) { addPerson() }
        active = people.first().id
    }

    fun toggle(itemId: Long) {
        val index = items.indexOfFirst { it.id == itemId }
        if (index < 0) return
        val item = items[index]
        val pickers = if (active in item.pickers) item.pickers - active else item.pickers + active
        items[index] = item.copy(pickers = pickers)
    }

    fun addPerson() {
        if (people.size >= MAX_PEOPLE) return
        val used = people.map { it.name }.toSet()
        val name = ('A'..'Z').map(Char::toString).first { it !in used }
        people.add(Person(nextPersonId++, name))
    }

    fun removePerson(id: Int) {
        if (people.size <= 2) return
        people.removeAll { it.id == id }
        for (index in items.indices) {
            val item = items[index]
            if (id in item.pickers) items[index] = item.copy(pickers = item.pickers - id)
        }
        if (active == id) active = people.first().id
    }

    fun editItem(itemId: Long) {
        val item = items.firstOrNull { it.id == itemId } ?: return
        draft = Draft(item.id, item.name, "%.2f".format(Locale.US, item.cents / 100.0))
    }

    fun addItem() {
        draft = Draft(null, "", "")
    }

    fun updateDraft(name: String? = null, price: String? = null) {
        val current = draft ?: return
        draft = current.copy(name = name ?: current.name, price = price ?: current.price)
    }

    /** Puts text picked from the photo into the field it looks like. */
    fun fillDraft(text: String) {
        val clean = text.trim()
        if (MONEY.matches(clean)) {
            updateDraft(price = clean.trimStart('$', '¥', '￥', '€', '£').trim().replace(',', '.'))
        } else {
            updateDraft(name = clean)
        }
    }

    /** Saves the draft; false when the price is not a number. */
    fun saveDraft(): Boolean {
        val current = draft ?: return false
        val price = current.price.trim().trimStart('$').replace(',', '.').toDoubleOrNull() ?: return false
        val name = current.name.trim()
        val index = items.indexOfFirst { it.id == current.itemId }
        if (index >= 0) {
            items[index] = items[index].copy(name = name, cents = toCents(price))
        } else {
            items.add(BillItem(nextItemId++, name, toCents(price)))
        }
        draft = null
        return true
    }

    fun deleteDraftItem() {
        val current = draft ?: return
        items.removeAll { it.id == current.itemId }
        draft = null
    }

    fun closeDraft() {
        draft = null
    }

    /** Reads one part of the receipt photo again, for text the first read got wrong. */
    suspend fun readRegion(region: RectF): String {
        val photo = reading?.photo ?: return ""
        val tier = tier
        val language = language
        return withContext(worker) {
            val left = max(0, region.left.toInt() - REGION_MARGIN)
            val top = max(0, region.top.toInt() - REGION_MARGIN)
            val right = min(photo.width, region.right.toInt() + REGION_MARGIN)
            val bottom = min(photo.height, region.bottom.toInt() + REGION_MARGIN)
            if (right - left < 4 || bottom - top < 4) return@withContext ""
            val crop = Bitmap.createBitmap(photo, left, top, right - left, bottom - top)
            ocrFor(tier).recognize(crop, language).joinToString(" ") { it.text }
        }
    }

    /** Zips the run log for sharing, or null when there is nothing to share. */
    suspend fun packRuns(): File? = withContext(worker) {
        val runs = app.getExternalFilesDir("runs") ?: return@withContext null
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        RunExport.pack(runs, File(app.cacheDir, "exports"), stamp)
    }

    /** Keeps the finished split on the phone, replacing the record it was opened from. */
    fun saveToHistory() {
        if (items.isEmpty()) return
        val existing = history.firstOrNull { it.id == historyId }
        val bill = SavedBill(
            id = existing?.id ?: UUID.randomUUID().toString(),
            savedAt = existing?.savedAt ?: System.currentTimeMillis(),
            store = store,
            printedAt = printedAt,
            adjustment = adjustment,
            printedTotal = printedTotal,
            people = people.toList(),
            items = items.toList(),
        )
        history.removeAll { it.id == bill.id }
        history.add(0, bill)
        history.sortByDescending { it.savedAt }
        // A bill opened from history already has its photo saved.
        val photo = if (existing == null) reading?.photo else null
        val lines = reading?.lines.orEmpty()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                historyStore.save(bill, photo, lines)
            } catch (error: Exception) {
                Log.w("History", "could not save bill", error)
            }
        }
    }

    /** Opens a saved split for editing; its photo loads in the background for picking text. */
    fun openHistory(bill: SavedBill) {
        finish()
        historyId = bill.id
        store = bill.store
        printedAt = bill.printedAt
        adjustment = bill.adjustment
        printedTotal = bill.printedTotal
        items.addAll(bill.items)
        people.clear()
        people.addAll(bill.people)
        active = people.first().id
        nextItemId = (bill.items.maxOfOrNull { it.id } ?: -1) + 1
        nextPersonId = (bill.people.maxOfOrNull { it.id } ?: -1) + 1
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { historyStore.photo(bill.id) } ?: return@launch
            if (historyId == bill.id) reading = Reading(saved.first, saved.second, Receipt(), false)
        }
    }

    fun deleteHistory(id: String) {
        history.removeAll { it.id == id }
        viewModelScope.launch(Dispatchers.IO) { historyStore.delete(id) }
    }

    /** Forgets the receipt once the split is done. */
    fun finish() {
        historyId = null
        cancelRecognition()
        recognitionState.value = Recognition()
        reading = null
        preview = null
        corners = null
        items.clear()
        draft = null
        resetPeople()
    }

    override fun onCleared() {
        val engine = ocr
        ocr = null
        worker.executor.execute { engine?.close() }
        worker.close()
        super.onCleared()
    }
}
