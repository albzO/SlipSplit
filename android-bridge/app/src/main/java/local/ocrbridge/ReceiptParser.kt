package local.ocrbridge

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/** Reads a receipt from OCR lines; ReceiptParserTest guards the sample receipts against regressions. */
class OcrLine(
    val text: String,
    val box: IntArray,
    val score: Float = 0f,
    val corners: FloatArray? = null,
) {
    /** The box in levelled coordinates, which is what the parser reads. */
    var aligned: IntArray = box
}

data class ReceiptItem(
    val name: String,
    val qty: Int,
    val price: Double,
    val sku: String = "",
    var spec: String = "",
) {
    /** The OCR lines this item was read from. */
    var lines: List<OcrLine> = emptyList()
}

class Receipt(
    var items: MutableList<ReceiptItem> = ArrayList(),
    var subtotal: Double? = null,
    var tax: Double? = null,
    var service: Double? = null,
    var discount: Double? = null,
    var total: Double? = null,
    var company: String = "",
    var date: String = "",
    var time: String = "",
) {
    /** The OCR lines the subtotal, tax, discount and total were read from. */
    val fieldLines = ArrayList<OcrLine>()

    /** Rows read as items that the printed total shows cannot be items. */
    var dropped: List<ReceiptItem> = emptyList()
}

// JVM and Android disagree on \w, \b and \s; these spell out Python's Unicode behaviour.
private const val WORD = "\\p{L}\\p{N}_"
private const val START = "(?<![$WORD])"
private const val END = "(?![$WORD])"
private const val NON = "[^$WORD]"
private const val W = "[$WORD]"
private const val S = "[\\s\\u3000]"

private const val MAX_AMOUNT_DIGITS = 8
private const val COLUMN_TOLERANCE = 0.1
private const val EXACT_TOLERANCE = 0.011
private const val MAX_DROPPED_ROWS = 3
private const val MAX_ITEMS_TO_SEARCH = 30
private const val MIN_ROWS_ABOVE_SUBTOTAL = 2
private const val COMPANY_HEIGHT_RATIO = 0.75
private const val LONG_LINE_RATIO = 3.0
private const val FIT_ROUNDS = 3
private const val FIT_OUTLIER_RATIO = 3.0
private const val FIT_MIN_GAIN = 0.7
private const val MIN_LONG_LINES = 3
private const val WRAP_RATIO = 0.75
private const val AMOUNT =
    "-?[0-9]{1,3}(?:[.,][0-9]{3})+(?:[.,][0-9]{1,2})?|-?[0-9]+(?:[.,][0-9]{1,2})?"

private fun regex(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

private val TRAILING_AMOUNT = regex(
    "(?:^|[\\s\\u3000:：xX×*@\$¥￥€£]|Rp|RM)($AMOUNT)$S*(?:[-元#*^]|$START[A-Z]$END)?$S*$",
)
private val LEADING_QTY = Regex("^$S*([0-9]{1,2})(?:$S*[xX×*]$S*|$S+)(?=[^\\s\\u3000])")
private val MODIFIER = Regex("^$S*(?:-+$S|\\[\\*\\]$S*|\\+$S)")
private val COUNT_START = Regex("^$S*[0-9]+$S*[xX×*]")
private val ITEM_INDEX = Regex("^$S*[0-9]{1,3}$S*[.、)）]$S*(?=[^0-9\\s\\u3000])")
private val TRAILING_CODE = Regex("$S+([0-9]{6,})$S*$")
private val AMOUNT_HEADER = regex("^$S*(?:金额|金額|amount|amt\\.?|total)$S*:?$S*$")
private val QUANTITY_HEADER = regex("数量|數量|单价|單價|${START}qty$END|${START}quantity$END|${START}price$END|${START}unit$END|${START}rate$END")
private val NUMBER_TOKEN = Regex("^[*xX×@\$¥￥]?(?:$AMOUNT)$")
private val CENTS = Regex("[.,][0-9]{1,2}$")
private val PAID_AMOUNT = regex("(?:实[付收]|實[付收]|${START}paid$END)$S*[:：]?$S*[\$¥￥]?($AMOUNT)(?![0-9.,%])")
private val SAVED_AMOUNT = regex("(?:优惠|優惠|折扣|${START}discount$END|${START}disc$END|${START}saved$END|${START}savings$END)$S*[:：]?$S*[\$¥￥]?($AMOUNT)(?![0-9.,%折])")
private val TRAILING_QTY = Regex("$S+[xX×*]$S*([0-9]{1,2})$S*$")
private val DATE_OR_TIME = Regex("[0-9]{1,4}[-/.:][0-9]{1,2}[-/.:][0-9]{1,4}|[0-9]{1,2}:[0-9]{2}")
private val QTY_IN_ROW = regex("(?:^|$S)(?:qty$S*)?([0-9]{1,2})$S*[xX×*@]")
private val MEASURE_START = regex("^$S*(?:[0-9]+\\.[0-9]+|[0-9]+[A-Za-z]|qty$END)")
private val HAS_WORD = Regex("\\p{L}{3,}|[一-鿿]")
private val ITEM_MARKER = Regex("^[#^*~>\\s\\u3000]+")
private val SKU_PREFIX = Regex("^([0-9]{5,})$S+(?=[^\\s\\u3000])")
private val AT_PRICE = Regex("$S*@$S*[\$¥￥€£]?$S*(?:$AMOUNT)$S*$")
private val HAS_NAME = Regex("\\p{L}{2,}|[一-鿿]")

private val DATE = regex(
    "(?<![0-9])(?:([0-9]{4})[/.-]([0-9]{1,2})[/.-]([0-9]{1,2})|" +
        "([0-9]{1,2})[/.-]([0-9]{1,2})[/.-]([0-9]{4}|[0-9]{2})|" +
        "[0-9]{1,2}$S?(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)['’\\s]?[0-9]{2,4}|" +
        "[0-9]{4}年[0-9]{1,2}月[0-9]{1,2}日)",
)
private val TIME = regex("(?<![0-9])[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?(?:$S?[ap]m)?")
private val CENTS_AT_END = Regex("[.,][0-9]{2}$S*$")
private val LEGAL_NAME = regex(
    "sdn\\.?$S*bhd|pty\\.?$S*ltd|${START}p/l$END|${START}limited$END|${START}ltd$END|" +
        "${START}inc$END|${START}llc$END|${START}gmbh$END|有限公司|株式会社",
)

private val SUBTOTAL = regex("sub$S*-?$S*total|小$S*[计計]|소$S*계")
private val TOTAL = regex(
    "grand$S*total|${START}t[o0]tal$END|${START}ttl$END|amount$S*due|balance$S*due|" +
        "合$S*[计計]|总$S*计|總$S*計|应[付收]|應[付收]|实[付收]|實[付收]|합$S*계|총$S*액|받을$S*금액",
)
private val NOT_MONEY = regex(
    "total$S*(?:item|qty|quantity)|item\\(?s\\)?$S*:|qty\\(?s\\)?$S*:|tax$S*invoice|" +
        "${START}abn$END|${START}acn$END|数量|件数",
)
private val SHOP_HEADER = regex("tax$S*invoice|${START}abn$END|${START}acn$END")
private val TOTAL_WITH_GST = regex("total$NON+(?:$W+$NON+)?incl$W*$NON+(?:of$NON+)?gst$NON*@")
private val GST_INCLUDED = regex(
    "incl$W*$NON+(?:$W+$NON+)?gst|gst$NON+(?:$W+$NON+)?incl|total$NON+gst|gst$NON+total|" +
        "tax$NON+incl|tax$NON+in$NON+t(?:o?ta?l)$END",
)
private val TAX = regex(
    "${START}tax$END|${START}vat$END|${START}gst$END|${START}hst$END|${START}pb-?$S*1$END|${START}ppn$END|税|稅",
)
private val SERVICE = regex(
    "service|surcharge|${START}svc$END|${START}s/c$END|${START}tip$END|gratuity|服务费|服務費|小费|小費",
)
private val DISCOUNT = regex("discount|${START}disc$END|voucher|coupon|优惠|優惠|折扣|减免")
private val PAYMENT = regex(
    "${START}cash$END|change|kembali|tunai|${START}card$END|visa|master|debit|credit|eftpos|" +
        "${START}amex$END|paypass|paywave|tendered|rounding|you$S*saved|savings|" +
        "现金|現金|找零|刷卡|支付宝|微信|お?預り?|お?釣|현금|카드|거스름",
)
private val SKIP = regex(
    "${START}description$END|${START}amount$END|${START}covers?$END|${START}abn$END|${START}acn$END|tax$S*invoice|${START}terminal$END|${START}store$END|rewards|flybuys|" +
        "${START}points$END|${START}tel$END|${START}phone$END|${START}ph$END|${START}table$END|" +
        "${START}cashier$END|${START}guest$END|${START}bill$END|${START}order$END|电话|電話|桌号|收银",
)

private const val PRICE_TRIM = " :：-@xX×*$¥￥€£"

private enum class Field { SUBTOTAL, TOTAL, TAX, SERVICE, DISCOUNT }

private fun Receipt.get(field: Field) = when (field) {
    Field.SUBTOTAL -> subtotal
    Field.TOTAL -> total
    Field.TAX -> tax
    Field.SERVICE -> service
    Field.DISCOUNT -> discount
}

private fun Receipt.setOnce(field: Field, value: Double) {
    if (get(field) != null) return
    when (field) {
        Field.SUBTOTAL -> subtotal = value
        Field.TOTAL -> total = value
        Field.TAX -> tax = value
        Field.SERVICE -> service = value
        Field.DISCOUNT -> discount = value
    }
}

/** Reads an amount, treating 12.500 and 1,250 as thousands and 12,50 as decimals. */
fun toNumber(raw: String): Double? {
    val token = raw.replace(Regex("[^0-9.,-]"), "")
    val match = Regex("(-?[0-9]{1,3}(?:[.,][0-9]{3})+)([.,][0-9]{1,2})?").matchEntire(token)
    if (match != null) {
        val whole = match.groupValues[1].replace(Regex("[.,]"), "")
        return (whole + match.groupValues[2].replace(",", ".")).toDoubleOrNull()
    }
    return token.replace(",", ".").toDoubleOrNull()
}

private fun median(values: List<Int>): Double {
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle].toDouble()
    else (sorted[middle - 1] + sorted[middle]) / 2.0
}

private fun medianOf(values: List<Double>): Double {
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
}

private class RowLine(val intercept: Double, val slope: Double)

/** Fits slope = tilt + spread * intercept; printed rows meet at one vanishing point in a photo. */
private fun fitRowLines(found: List<RowLine>): Pair<Double, Double> {
    val flatTilt = medianOf(found.map { it.slope })
    var spread = 0.0
    var tilt = flatTilt
    var kept = found
    for (round in 0 until FIT_ROUNDS) {
        val meanC = kept.sumOf { it.intercept } / kept.size
        val meanS = kept.sumOf { it.slope } / kept.size
        val variance = kept.sumOf { (it.intercept - meanC) * (it.intercept - meanC) }
        if (variance == 0.0) break
        spread = kept.sumOf { (it.intercept - meanC) * (it.slope - meanS) } / variance
        tilt = meanS - spread * meanC
        val misses = found.map { abs(it.slope - tilt - spread * it.intercept) }
        val limit = FIT_OUTLIER_RATIO * medianOf(misses)
        kept = found.filterIndexed { index, _ -> misses[index] <= limit }.ifEmpty { found }
    }
    // Paper held at odd angles fits nothing; keep perspective only when it clearly explains more.
    val fitted = medianOf(found.map { abs(it.slope - tilt - spread * it.intercept) })
    val flat = medianOf(found.map { abs(it.slope - flatTilt) })
    return if (fitted > FIT_MIN_GAIN * flat) 0.0 to flatTilt else spread to tilt
}

/**
 * Puts every box in row coordinates, so photos need not be straight or square-on. Straight
 * printed rows stay straight in a photo and share a vanishing point, so one fit covers tilt
 * and perspective however far an amount sits from its text.
 */
private fun deskew(lines: List<OcrLine>) {
    val polygons = lines.mapNotNull { line -> line.corners?.map { it.toDouble() } }
    if (polygons.isEmpty()) return
    val centerX = polygons.sumOf { p -> p[0] + p[2] + p[4] + p[6] } / (4 * polygons.size)
    val centerY = polygons.sumOf { p -> p[1] + p[3] + p[5] + p[7] } / (4 * polygons.size)
    val found = polygons.mapNotNull { p ->
        val run = (p[2] - p[0]) + (p[4] - p[6])
        val width = hypot(p[2] - p[0], p[3] - p[1])
        val height = hypot(p[6] - p[0], p[7] - p[1])
        if (run > 0 && width >= LONG_LINE_RATIO * height) {
            val slope = ((p[3] - p[1]) + (p[5] - p[7])) / run
            val middleX = (p[0] + p[2] + p[4] + p[6]) / 4 - centerX
            val middleY = (p[1] + p[3] + p[5] + p[7]) / 4 - centerY
            RowLine(middleY - slope * middleX, slope)
        } else {
            null
        }
    }
    if (found.size < MIN_LONG_LINES) return
    val (spread, tilt) = fitRowLines(found)
    for (line in lines) {
        val p = line.corners?.map { it.toDouble() } ?: continue
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        for (corner in 0 until 4) {
            val dx = p[corner * 2] - centerX
            val dy = p[corner * 2 + 1] - centerY
            val row = (dy - tilt * dx) / (1 + spread * dx)
            val slope = tilt + spread * row
            xs.add((dx + dy * slope) / sqrt(1 + slope * slope))
            ys.add(row)
        }
        line.aligned = intArrayOf(xs.min().toInt(), ys.min().toInt(), xs.max().toInt(), ys.max().toInt())
    }
}

/** One printed line of the receipt and the right edge of its last box. */
class ReceiptRow(val text: String, val right: Int, val cells: List<OcrLine>) {
    val left: Int get() = cells.first().aligned[0]
}

private class Row(val center: Double, val cells: MutableList<OcrLine>)

/** Moves each short box to the nearest row; scanning alone takes the first row in reach. */
private fun settleRows(rows: List<Row>) {
    fun width(cell: OcrLine) = cell.aligned[2] - cell.aligned[0]
    fun middle(cell: OcrLine) = (cell.aligned[1] + cell.aligned[3]) / 2.0
    fun isLong(cell: OcrLine) =
        width(cell) >= LONG_LINE_RATIO * (cell.aligned[3] - cell.aligned[1])

    val anchors = rows.map { row -> row.cells.maxBy(::width) }
    val centers = anchors.map(::middle)
    class Move(val cell: OcrLine, val source: Int, val target: Int)
    val moves = ArrayList<Move>()
    rows.forEachIndexed { index, row ->
        for (cell in row.cells) {
            if (cell === anchors[index]) continue
            // Only real text rows may take a box over; marks and handwriting are too short.
            val choices = anchors.indices.filter { isLong(anchors[it]) } + index
            val nearest = choices.minBy { abs(centers[it] - middle(cell)) }
            if (nearest != index) moves.add(Move(cell, index, nearest))
        }
    }
    for (move in moves) {
        rows[move.source].cells.removeAll { it === move.cell }
        rows[move.target].cells.add(move.cell)
    }
}

/** Joins boxes printed on the same line, left to right. */
fun groupRows(lines: List<OcrLine>): List<ReceiptRow> {
    if (lines.isEmpty()) return emptyList()
    val tolerance = 0.6 * median(lines.map { it.aligned[3] - it.aligned[1] })
    val rows = ArrayList<Row>()
    for (line in lines.sortedBy { it.aligned[1] + it.aligned[3] }) {
        val center = (line.aligned[1] + line.aligned[3]) / 2.0
        val last = rows.lastOrNull()
        if (last != null && abs(center - last.center) <= tolerance) {
            last.cells.add(line)
        } else {
            rows.add(Row(center, arrayListOf(line)))
        }
    }
    settleRows(rows)
    return rows.map { row ->
        val cells = row.cells.sortedBy { it.aligned[0] }
        ReceiptRow(cells.joinToString(" ") { it.text }, cells.maxOf { it.aligned[2] }, cells)
    }
}

private fun trailingAmount(row: String): Pair<Double?, String> {
    if (DATE_OR_TIME.containsMatchIn(row)) return null to row
    val match = TRAILING_AMOUNT.find(row) ?: return null to row
    val group = match.groups[1] ?: return null to row
    if (group.value.count { it.isDigit() } > MAX_AMOUNT_DIGITS) return null to row
    return toNumber(group.value) to row.substring(0, group.range.first).trimEnd { it in PRICE_TRIM }
}

/** Left limit of the right-aligned amount column, or null when there is no such column. */
private fun priceColumn(rows: List<ReceiptRow>, lines: List<OcrLine>): Double? {
    if (lines.isEmpty()) return null
    val tolerance = COLUMN_TOLERANCE * (lines.maxOf { it.aligned[2] } - lines.minOf { it.aligned[0] })
    val edges = rows
        .filter {
            trailingAmount(it.text).first != null &&
                !AT_PRICE.containsMatchIn(it.text) &&
                !SKIP.containsMatchIn(it.text) &&
                !NOT_MONEY.containsMatchIn(it.text)
        }
        .map { it.right }
        .sortedDescending()
    for (edge in edges) {
        if (edges.count { it >= edge - tolerance && it <= edge } >= 2) return edge - tolerance
    }
    return null
}

private fun classify(row: String): Field? = when {
    TOTAL_WITH_GST.containsMatchIn(row) -> Field.TOTAL
    GST_INCLUDED.containsMatchIn(row) -> Field.TAX
    SUBTOTAL.containsMatchIn(row) -> Field.SUBTOTAL
    TOTAL.containsMatchIn(row) -> Field.TOTAL
    DISCOUNT.containsMatchIn(row) -> Field.DISCOUNT
    SERVICE.containsMatchIn(row) -> Field.SERVICE
    TAX.containsMatchIn(row) -> Field.TAX
    else -> null
}

/** One row of the item area, in print order. */
private class ItemRow(
    val text: String,
    val label: String,
    val price: Double?,
    val right: Int,
    val left: Int,
    val cells: List<OcrLine>,
    val unitPriced: Boolean = false,
)

/** First plausible date in the text and what follows it; phone numbers fail the range check. */
private fun findDate(text: String): Pair<String, String> {
    for (match in DATE.findAll(text)) {
        val groups = match.groupValues
        val valid = when {
            groups[1].isNotEmpty() ->
                groups[1].toInt() in 1901..2099 && groups[2].toInt() <= 12 && groups[3].toInt() <= 31
            groups[4].isNotEmpty() -> {
                val first = groups[4].toInt()
                val second = groups[5].toInt()
                first <= 31 && second <= 31 && minOf(first, second) <= 12
            }
            else -> true
        }
        if (valid) return match.value to text.substring(match.range.last + 1)
    }
    return "" to ""
}

private fun findTime(text: String) = TIME.find(text)?.value.orEmpty()

private fun lineHeight(line: OcrLine) = line.aligned[3] - line.aligned[1]

/** Takes a legal name from the header, else the tallest text there, usually the logo. */
private fun readCompany(rows: List<ReceiptRow>, result: Receipt) {
    val block = ArrayList<ReceiptRow>()
    val firstItem = result.items.firstOrNull()?.name
    for (row in rows) {
        if (findDate(row.text).first.isNotEmpty() || findTime(row.text).isNotEmpty() ||
            SHOP_HEADER.containsMatchIn(row.text) || SKIP.containsMatchIn(row.text) ||
            CENTS_AT_END.containsMatchIn(row.text) || TOTAL.containsMatchIn(row.text) ||
            SUBTOTAL.containsMatchIn(row.text) ||
            (!firstItem.isNullOrEmpty() && firstItem in row.text)
        ) {
            break
        }
        if (HAS_NAME.containsMatchIn(row.text)) block.add(row)
    }
    val legal = block.firstOrNull { LEGAL_NAME.containsMatchIn(it.text) }
    if (legal != null) {
        result.company = legal.text
        result.fieldLines.addAll(legal.cells)
        return
    }
    if (block.isEmpty()) return
    val heights = block.map { row -> row.cells.maxOf(::lineHeight) }
    val tallest = heights.max()
    var first = heights.indexOf(tallest)
    var last = first
    val limit = COMPANY_HEIGHT_RATIO * tallest
    while (first > 0 && heights[first - 1] >= limit) first--
    while (last < block.size - 1 && heights[last + 1] >= limit) last++
    val cells = block.subList(first, last + 1).flatMap { it.cells }
        .filter { lineHeight(it) >= limit && HAS_NAME.containsMatchIn(it.text) }
    result.company = cells.joinToString(" ") { it.text }
    result.fieldLines.addAll(cells)
}

private fun readHeader(rows: List<ReceiptRow>, result: Receipt) {
    for ((index, row) in rows.withIndex()) {
        val (date, rest) = findDate(row.text)
        if (date.isNotEmpty()) {
            result.date = date
            // The time may print on a later row; rows above the date could be a phone's clock.
            val candidates = listOf(rest) + rows.drop(index + 1).map { it.text }
            result.time = candidates.map(::findTime).firstOrNull { it.isNotEmpty() }.orEmpty()
            result.fieldLines.addAll(row.cells)
            break
        }
    }
    readCompany(rows, result)
}

private fun joinSpec(spec: String, text: String) = if (spec.isEmpty()) text else "$spec, $text"

/** Separates a leading stock number from the description it belongs to. */
private fun splitSku(text: String): Pair<String, String> {
    val match = SKU_PREFIX.find(text) ?: return "" to text
    val rest = text.substring(match.range.last + 1)
    return if (HAS_NAME.containsMatchIn(rest)) match.groupValues[1] to rest else "" to text
}

private fun clean(text: String) =
    text.replace(ITEM_MARKER, "").replace(MODIFIER, "").replace(ITEM_INDEX, "").trim()

private fun isModifier(row: ItemRow) = MODIFIER.containsMatchIn(row.text)

/** A quantity, stock number or list number printed where an item begins. */
private fun hasStart(text: String): Boolean {
    val bare = text.replace(ITEM_MARKER, "").replace(MODIFIER, "")
    return SKU_PREFIX.containsMatchIn(bare) || LEADING_QTY.containsMatchIn(bare) ||
        ITEM_INDEX.containsMatchIn(bare)
}

/** Where a table header puts the amount when other columns print to its right. */
private class AmountColumn(val row: Int, val center: Double, val columnsAfter: Int)

private class Word(val text: String, val center: Double)

/** Words in a row with their estimated x, as OCR may join a whole row into one box. */
private fun wordsOf(row: ReceiptRow): List<Word> = row.cells.flatMap { cell ->
    val box = cell.aligned
    val share = (box[2] - box[0]).toDouble() / cell.text.length.coerceAtLeast(1)
    Regex("[^\\s\\u3000]+").findAll(cell.text)
        .map { Word(it.value, box[0] + share * (it.range.first + it.range.last + 1) / 2) }
        .toList()
}

private fun findAmountColumn(rows: List<ReceiptRow>): AmountColumn? {
    rows.forEachIndexed { index, row ->
        val words = wordsOf(row)
        val at = words.indexOfFirst { AMOUNT_HEADER.matches(it.text) }
        val after = words.drop(at + 1)
        // A payment table also has an amount column; only an item table has quantity or unit price.
        val itemTable = words.take(at.coerceAtLeast(0)).any { QUANTITY_HEADER.containsMatchIn(it.text) }
        if (at > 0 && itemTable && after.isNotEmpty() && row.text.none { it.isDigit() || it == ':' || it == '：' }) {
            return AmountColumn(index, words[at].center, after.size)
        }
    }
    return null
}

/** The amount under its header; a lone whole number, such as a barcode, is not a table row. */
private fun amountInColumn(row: ReceiptRow, column: AmountColumn): Pair<Double?, String> {
    val numbers = wordsOf(row).filter { NUMBER_TOKEN.matches(it.text) }
    if (numbers.isEmpty() || (numbers.size == 1 && !CENTS.containsMatchIn(numbers[0].text))) return null to row.text
    // Character positions drift in a row joined into one box, but columns keep their order.
    val fromRight = numbers.getOrNull(numbers.size - 1 - column.columnsAfter)
    val chosen = (if (row.cells.size == 1) fromRight else null) ?: numbers.minBy { abs(it.center - column.center) }
    var label = row.text.substring(0, row.text.lastIndexOf(chosen.text)).trimEnd()
    val named = label.split(' ', '\u3000').filter { it.isNotEmpty() }
    val text = named.dropLastWhile { NUMBER_TOKEN.matches(it) }
    if (text.any { HAS_NAME.containsMatchIn(it) }) {
        // Quantity and unit price columns sit between the name and the amount.
        val counts = named.drop(text.size)
        val qty = counts.takeIf { it.size >= 2 }?.firstOrNull { it.all(Char::isDigit) }
        label = text.joinToString(" ") + if (qty != null) " x $qty" else ""
    }
    return toNumber(chosen.text) to label
}

/** True when a priced row also carries a name, not only `1 x $3.00 = $3.00`. */
private fun namedPrice(row: ItemRow) =
    HAS_WORD.containsMatchIn(row.label.replace(COUNT_START, "")) && !MEASURE_START.containsMatchIn(row.label)

/** One printer lays out items alike, so the whole receipt votes on where amounts sit. */
private fun amountComesLast(entries: List<ItemRow?>): Boolean {
    val rows = entries.filterNotNull()
    val priced = rows.filter { it.price != null }
    if (priced.isEmpty()) return false
    val named = priced.filter(::namedPrice)
    val after = rows.subList(rows.indexOfLast { it.price != null } + 1, rows.size)
    val leftover = after.count { !isModifier(it) }
    val firstRow = named.count { hasStart(it.label) }
    val otherRow = rows.count { it.price == null && hasStart(it.text) }
    return 2 * (priced.size - named.size + otherRow) > named.size + 2 * firstRow + leftover
}

private fun Receipt.addItem(text: String, price: Double, spec: String, cells: List<OcrLine>) {
    var (sku, name) = splitSku(text)
    TRAILING_CODE.find(name)?.takeIf { sku.isEmpty() }?.let {
        sku = it.groupValues[1]
        name = name.substring(0, it.range.first)
    }
    var qty = 1
    val leading = LEADING_QTY.find(name)
    val trailing = TRAILING_QTY.find(name)
    if (leading != null) {
        qty = leading.groupValues[1].toInt()
        name = LEADING_QTY.replaceFirst(name, "").trim()
    } else if (trailing != null) {
        qty = trailing.groupValues[1].toInt()
        name = TRAILING_QTY.replaceFirst(name, "").trim()
    }
    items.add(ReceiptItem(name, qty, price, sku, spec).also { it.lines = cells })
}

private fun Receipt.addDetail(row: ItemRow) {
    val item = items.last()
    item.spec = joinSpec(item.spec, clean(row.text))
    item.lines = item.lines + row.cells
}

/** One item from the rows up to its amount: the first row starts it, the rest wrap or detail it. */
private fun Receipt.addBlock(rows: List<ItemRow>, closing: ItemRow, anchored: Boolean, wrapRight: Double) {
    var block = rows
    val marked = block.indexOfLast { hasStart(it.text) }
    // With no heading above the first item, its unit price or the row touching its amount shows it.
    val start = when {
        marked >= 0 -> marked
        anchored -> 0
        else -> block.indexOfFirst { it.unitPriced }.takeIf { it >= 0 } ?: max(block.size - 1, 0)
    }
    val lead = block.subList(0, start)
    block = block.subList(start, block.size)
    for (row in lead) if (items.isNotEmpty() && isModifier(row)) addDetail(row)
    while (block.size > 1 && isModifier(block[0])) {
        if (items.isNotEmpty()) addDetail(block[0])
        block = block.subList(1, block.size)
    }
    val label = clean(closing.label)
    if (block.isEmpty()) {
        if (HAS_NAME.containsMatchIn(label)) addItem(label, closing.price!!, "", closing.cells)
        return
    }
    var name = clean(block[0].text)
    var used = 1
    while (used < block.size && block[used - 1].right >= wrapRight && HAS_WORD.containsMatchIn(block[used].text)) {
        val text = clean(block[used].text)
        val gap = if (name.takeLast(1).all { it.code < 128 } && text.take(1).all { it.code < 128 }) " " else ""
        name = "$name$gap$text"
        used++
    }
    var spec = ""
    for (row in block.subList(used, block.size)) spec = joinSpec(spec, clean(row.text))
    val qty = QTY_IN_ROW.find(label)?.groupValues?.get(1)?.toInt() ?: 1
    if (HAS_WORD.containsMatchIn(label) || qty > 1) spec = joinSpec(spec, label.trimEnd(' ', '='))
    addItem(name, closing.price!!, spec, block.flatMap { it.cells } + closing.cells)
    val item = items.removeAt(items.size - 1)
    items.add(item.copy(qty = max(item.qty, qty)).also { it.lines = item.lines })
}

/** A row reaching this far right had no room left, so its text continues on the next row. */
private fun wrapLimit(entries: List<ItemRow?>): Double {
    val rows = entries.filterNotNull()
    val priced = rows.filter { it.price != null }.map { it.right }
    if (priced.isEmpty()) return Double.POSITIVE_INFINITY
    val right = median(priced)
    return right - (1 - WRAP_RATIO) * (right - rows.minOf { it.left })
}

/** Cuts the item area into one block per amount, then reads each block on its own. */
private fun Receipt.splitBlocks(entries: List<ItemRow?>) {
    val last = amountComesLast(entries)
    val wrapRight = wrapLimit(entries)
    var block = ArrayList<ItemRow>()
    var anchored = false

    fun flushDetails() {
        for (row in block) if (items.isNotEmpty() && (!last || isModifier(row))) addDetail(row)
    }

    for (entry in entries) {
        if (entry == null) {
            // Rows held after an amount still describe that item; modifiers print after their amount.
            flushDetails()
            block = ArrayList()
            anchored = true
        } else if (entry.price == null) {
            block.add(entry)
        } else {
            if (!last && namedPrice(entry)) {
                flushDetails()
                addItem(clean(entry.label), entry.price, "", entry.cells)
            } else {
                addBlock(block, entry, anchored, wrapRight)
            }
            block = ArrayList()
            anchored = true
        }
    }
    flushDetails()
}

fun parseReceipt(lines: List<OcrLine>): Receipt {
    val result = Receipt()
    var pendingField: Field? = null
    deskew(lines)
    val rows = groupRows(lines)
    val column = priceColumn(rows, lines)
    val table = findAmountColumn(rows)
    // Item rows in print order; null marks a row that separates items, such as a heading.
    val entries = ArrayList<ItemRow?>()

    for ((index, entry) in rows.withIndex()) {
        if (index == table?.row) {
            entries.clear()
            entries.add(null)
            continue
        }
        var row = entry.text
        var (amount, label) = trailingAmount(row)
        val waiting = pendingField
        if (waiting != null && amount != null && !HAS_NAME.containsMatchIn(label)) {
            result.setOnce(waiting, abs(amount))
            result.fieldLines.addAll(entry.cells)
            pendingField = null
            continue
        }
        pendingField = null
        // Several labelled values can share a row, so the last number need not be the wanted one.
        val paid = PAID_AMOUNT.find(row)
        val saved = SAVED_AMOUNT.find(row)
        if (paid != null || saved != null) {
            paid?.let { result.setOnce(Field.TOTAL, abs(toNumber(it.groupValues[1]) ?: 0.0)) }
            saved?.let { result.setOnce(Field.DISCOUNT, abs(toNumber(it.groupValues[1]) ?: 0.0)) }
            result.fieldLines.addAll(entry.cells)
            entries.add(null)
            continue
        }
        if (PAYMENT.containsMatchIn(row)) {
            entries.add(null)
            continue
        }
        if (NOT_MONEY.containsMatchIn(row)) {
            // Anything priced above the shop header is a misread logo or address.
            if (SHOP_HEADER.containsMatchIn(row) && result.total == null && result.subtotal == null) {
                entries.clear()
            }
            entries.add(null)
            continue
        }
        val field = classify(row)
        if (field != null) {
            entries.add(null)
            if (amount == null) {
                pendingField = field
            } else {
                result.setOnce(field, abs(amount))
                result.fieldLines.addAll(entry.cells)
            }
            continue
        }
        if (result.total != null || result.subtotal != null || SKIP.containsMatchIn(row)) {
            entries.add(null)
            continue
        }
        // Only the amount column prices an item; a unit price beside the name is description.
        val unitPriced = AT_PRICE.containsMatchIn(row)
        if (table != null && index > table.row) {
            val read = amountInColumn(entry, table)
            amount = read.first
            label = read.second
        } else if (unitPriced) {
            row = row.replace(AT_PRICE, "")
            amount = null
        } else if (amount != null && column != null && entry.right < column) {
            row = label
            amount = null
        }
        if (amount == null) {
            if (HAS_NAME.containsMatchIn(clean(row))) {
                entries.add(ItemRow(row, row, null, entry.right, entry.left, entry.cells, unitPriced))
            }
            continue
        }
        if (amount < 0) {
            result.discount = (result.discount ?: 0.0) + abs(amount)
            result.fieldLines.addAll(entry.cells)
            entries.add(null)
            continue
        }
        entries.add(ItemRow(row, label, amount, entry.right, entry.left, entry.cells))
    }
    result.splitBlocks(entries)
    if (result.total == null) result.total = result.subtotal
    reconcile(result)
    readHeader(rows, result)
    return result
}

private fun close(a: Double?, b: Double?): Boolean {
    if (a == null || b == null || b == 0.0) return false
    return abs(a - b) <= max(0.01, abs(b) * 0.005)
}

private fun exact(a: Double?, b: Double?): Boolean {
    if (a == null || b == null || b == 0.0) return false
    return abs(a - b) <= EXACT_TOLERANCE
}

/** True when these prices add up to the printed subtotal or total. */
private fun totalsAgree(
    receipt: Receipt,
    prices: List<Double>,
    near: (Double?, Double?) -> Boolean,
): Boolean {
    val items = prices.sum()
    val fees = items + (receipt.service ?: 0.0) - (receipt.discount ?: 0.0)
    // Australian GST is already inside item prices; elsewhere tax is added on top.
    return near(items, receipt.subtotal) || near(items, receipt.total) ||
        near(fees, receipt.total) || near(fees + (receipt.tax ?: 0.0), receipt.total)
}

/** True when the item prices add up to the printed subtotal or total. */
fun sumMatches(receipt: Receipt): Boolean {
    if (receipt.items.isEmpty() || (receipt.total == null && receipt.subtotal == null)) return false
    return totalsAgree(receipt, receipt.items.map { it.price }, ::close)
}

/** Every way to pick `size` of `count` rows, in lexicographic order. */
private fun combinations(count: Int, size: Int): List<List<Int>> {
    fun pick(start: Int, left: Int): List<List<Int>> {
        if (left == 0) return listOf(emptyList())
        return (start..count - left).flatMap { first ->
            pick(first + 1, left - 1).map { listOf(first) + it }
        }
    }
    return pick(0, size)
}

/** Trusts the printed total: when items overshoot it, moves the excess rows to dropped. */
private fun reconcile(receipt: Receipt) {
    val items = receipt.items
    if (items.size < 2 || sumMatches(receipt)) return
    if (receipt.total == null && receipt.subtotal == null) return
    val prices = items.map { it.price }
    if (items.size <= MAX_ITEMS_TO_SEARCH) {
        for (size in 1..minOf(MAX_DROPPED_ROWS, items.size - 1)) {
            val fits = combinations(items.size, size).filter { rows ->
                totalsAgree(receipt, prices.filterIndexed { i, _ -> i !in rows }, ::exact)
            }
            if (fits.isNotEmpty()) {
                // Subtotals follow the items, so with a choice the later rows go.
                val rows = fits.maxBy { it.sum() }
                receipt.dropped = rows.map { items[it] }
                receipt.items = items.filterIndexed { i, _ -> i !in rows }.toMutableList()
                return
            }
        }
    }
    // A row equal to everything above it is a subtotal, whatever the OCR made of its label.
    val kept = ArrayList<ReceiptItem>()
    val dropped = ArrayList<ReceiptItem>()
    var running = 0.0
    items.forEachIndexed { index, item ->
        if (index >= MIN_ROWS_ABOVE_SUBTOTAL && exact(item.price, running)) {
            dropped.add(item)
            if (receipt.subtotal == null) receipt.subtotal = item.price
        } else {
            kept.add(item)
            running += item.price
        }
    }
    receipt.items = kept
    receipt.dropped = dropped
}
