package local.ocrbridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val PHOTO_QUALITY = 90

/** A finished split, kept on the phone so it can be opened and edited again. */
class SavedBill(
    val id: String,
    val savedAt: Long,
    val store: String,
    val printedAt: String,
    val adjustment: Adjustment,
    val printedTotal: Long?,
    val people: List<Person>,
    val items: List<BillItem>,
) {
    val split: BillSplit get() = splitBill(items, people.map { it.id }, adjustment)
}

/**
 * Keeps every split as one folder: `bill.json` with the items and who took them, and the
 * receipt photo with its OCR lines so picking text from the photo still works when editing.
 */
class HistoryStore(context: Context) {
    private val root = File(context.filesDir, "history")

    fun load(): List<SavedBill> = (root.listFiles() ?: emptyArray())
        .mapNotNull { folder ->
            try {
                read(JSONObject(File(folder, "bill.json").readText()))
            } catch (error: Exception) {
                null
            }
        }
        .sortedByDescending { it.savedAt }

    fun save(bill: SavedBill, photo: Bitmap?, lines: List<OcrLine>) {
        val folder = File(root, bill.id).apply { mkdirs() }
        if (photo != null) {
            File(folder, "photo.jpg").outputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, PHOTO_QUALITY, it) }
            File(folder, "lines.json").writeText(writeLines(lines).toString())
        }
        // Write the record last, so a half-saved folder is never listed.
        File(folder, "bill.json").writeText(write(bill).toString())
    }

    fun delete(id: String) {
        File(root, id).deleteRecursively()
    }

    /** The photo and lines saved with a bill, or null when it has none. */
    fun photo(id: String): Pair<Bitmap, List<OcrLine>>? {
        val folder = File(root, id)
        val image = File(folder, "photo.jpg").takeIf { it.exists() } ?: return null
        val bitmap = BitmapFactory.decodeFile(image.path) ?: return null
        val lines = try {
            readLines(JSONArray(File(folder, "lines.json").readText()))
        } catch (error: Exception) {
            emptyList()
        }
        return bitmap to lines
    }

    private fun write(bill: SavedBill) = JSONObject()
        .put("id", bill.id)
        .put("savedAt", bill.savedAt)
        .put("store", bill.store)
        .put("printedAt", bill.printedAt)
        .put("adjustmentCents", bill.adjustment.cents)
        .put("proportional", bill.adjustment.proportional)
        .put("printedTotal", bill.printedTotal ?: JSONObject.NULL)
        .put("people", JSONArray(bill.people.map { JSONObject().put("id", it.id).put("name", it.name) }))
        .put("items", JSONArray(bill.items.map { item ->
            JSONObject().put("id", item.id).put("name", item.name).put("cents", item.cents)
                .put("note", item.note).put("pickers", JSONArray(item.pickers))
        }))

    private fun read(json: JSONObject): SavedBill {
        val people = json.getJSONArray("people")
        val items = json.getJSONArray("items")
        val billItems = (0 until items.length()).map {
            val item = items.getJSONObject(it)
            val pickers = item.getJSONArray("pickers")
            BillItem(
                item.getLong("id"), item.getString("name"), item.getLong("cents"), item.optString("note"),
                (0 until pickers.length()).map(pickers::getInt),
            )
        }
        // Bills saved before 1.3 kept the adjustment as a share of the items.
        val adjustment = if (json.has("adjustmentCents")) {
            Adjustment(json.getLong("adjustmentCents"), json.optBoolean("proportional"))
        } else {
            val share = json.optDouble("adjustment", 0.0)
            Adjustment(Math.round(billItems.sumOf { it.cents } * share), proportional = true)
        }
        return SavedBill(
            id = json.getString("id"),
            savedAt = json.getLong("savedAt"),
            store = json.optString("store"),
            printedAt = json.optString("printedAt"),
            adjustment = adjustment,
            printedTotal = if (json.isNull("printedTotal")) null else json.getLong("printedTotal"),
            people = (0 until people.length()).map {
                val person = people.getJSONObject(it)
                Person(person.getInt("id"), person.getString("name"))
            },
            items = billItems,
        )
    }

    private fun writeLines(lines: List<OcrLine>) = JSONArray(lines.map { line ->
        JSONObject().put("text", line.text)
            .put("box", JSONArray(line.box.toList()))
            .put("corners", line.corners?.let { corners -> JSONArray(corners.map(Float::toDouble)) } ?: JSONObject.NULL)
    })

    private fun readLines(json: JSONArray) = (0 until json.length()).map { index ->
        val line = json.getJSONObject(index)
        val box = line.getJSONArray("box")
        val corners = line.optJSONArray("corners")
        OcrLine(
            line.getString("text"),
            IntArray(4) { box.getInt(it) },
            0f,
            corners?.let { FloatArray(8) { i -> it.getDouble(i).toFloat() } },
        )
    }
}
