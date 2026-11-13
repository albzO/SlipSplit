package local.ocrbridge

import kotlin.math.floor
import kotlin.math.roundToLong

/** One line of the bill in cents, with the people who took it in the order they picked it. */
data class BillItem(
    val id: Long,
    val name: String,
    val cents: Long,
    val note: String = "",
    val pickers: List<Int> = emptyList(),
)

/** What one item costs one person, and how many people share it. */
class ShareLine(val item: BillItem, val cents: Long, val sharedBy: Int)

class PersonShare(val person: Int, val subtotal: Long, val total: Long, val lines: List<ShareLine>) {
    /** This person's part of the whole-bill discount, surcharge, tip or rounding. */
    val adjustment: Long get() = total - subtotal
}

class BillSplit(val shares: List<PersonShare>, val itemsCents: Long, val totalCents: Long)

fun toCents(amount: Double): Long = (amount * 100).roundToLong()

/** Who pays for an item: its pickers, or the last person when nobody picked it. */
fun owners(item: BillItem, people: List<Int>): List<Int> {
    val picked = item.pickers.filter { it in people }.distinct()
    return picked.ifEmpty { listOf(people.last()) }
}

/**
 * A whole-bill discount (negative) or charge in cents. A fixed amount is shared equally by the
 * people who took something; a percentage one is shared by the value of what each person took.
 */
data class Adjustment(val cents: Long = 0, val proportional: Boolean = false)

private fun near(a: Double, b: Double?) = b != null && kotlin.math.abs(a - b) <= 0.011

/**
 * The whole-bill amount the printed fields leave on top of the items. Item prices that already
 * add up to the total leave nothing, whatever discount or subtotal is printed beside them. Tax
 * added on top is a percentage, and so is any discount or charge printed with a percent sign.
 */
fun billAdjustment(receipt: Receipt): Adjustment {
    val items = receipt.items.sumOf { it.price }
    val total = receipt.total
    val subtotal = receipt.subtotal
    val fees = (receipt.service ?: 0.0) - (receipt.discount ?: 0.0)
    val tax = receipt.tax ?: 0.0
    if (total == null) return Adjustment(toCents(fees), receipt.percentage)
    if (near(items, total)) return Adjustment()
    // A subtotal that matches the items is where the printed adjustment starts from.
    val base = if (subtotal != null && near(items, subtotal)) subtotal else items
    val taxOnTop = tax > 0 && !near(base + fees, total) && near(base + fees + tax, total)
    val amount = if (base == subtotal || taxOnTop) total - base else fees
    return Adjustment(toCents(amount), receipt.percentage || taxOnTop)
}

/** Splits [cents] by [weights], floor first, then one cent each to the largest remainders. */
private fun shareOut(cents: Long, weights: List<Long>): List<Long> {
    val sum = weights.sum()
    if (sum == 0L) return weights.map { 0L }
    val exact = weights.map { cents.toDouble() * it / sum }
    val shares = exact.map { floor(it).toLong() }.toMutableList()
    var left = cents - shares.sum()
    for (index in exact.indices.sortedByDescending { exact[it] - floor(exact[it]) }) {
        if (left <= 0) break
        shares[index]++
        left--
    }
    return shares
}

/**
 * Shares every item equally among its owners, the first picker taking any odd cent, then adds
 * the whole-bill [adjustment]: a fixed amount equally among the people who took something, a
 * percentage by what each took. The shares always add up to the items plus the adjustment.
 */
fun splitBill(items: List<BillItem>, people: List<Int>, adjustment: Adjustment): BillSplit {
    require(people.isNotEmpty())
    val lines = people.associateWith { ArrayList<ShareLine>() }
    for (item in items) {
        val owners = owners(item, people)
        val base = Math.floorDiv(item.cents, owners.size.toLong())
        owners.forEachIndexed { index, person ->
            val extra = if (index == 0) item.cents - base * owners.size else 0
            lines.getValue(person).add(ShareLine(item, base + extra, owners.size))
        }
    }
    val subtotals = people.map { person -> lines.getValue(person).sumOf { it.cents } }
    val itemsCents = subtotals.sum()
    val weights = when {
        adjustment.proportional && subtotals.all { it >= 0 } && itemsCents > 0 -> subtotals
        subtotals.any { it != 0L } -> subtotals.map { if (it != 0L) 1L else 0L }
        else -> people.map { 1L }
    }
    val extras = shareOut(adjustment.cents, weights)
    val shares = people.mapIndexed { index, person ->
        PersonShare(person, subtotals[index], subtotals[index] + extras[index], lines.getValue(person))
    }
    return BillSplit(shares, itemsCents, itemsCents + adjustment.cents)
}
