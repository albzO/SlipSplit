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
 * Whole-bill charges as a share of the items: the gap between the printed subtotal and total
 * when both were read, else the service charge less the discount.
 */
fun billAdjustment(receipt: Receipt): Double {
    val subtotal = receipt.subtotal
    val total = receipt.total
    if (subtotal != null && total != null && subtotal > 0) return (total - subtotal) / subtotal
    val items = receipt.items.sumOf { it.price }
    if (items <= 0) return 0.0
    return ((receipt.service ?: 0.0) - (receipt.discount ?: 0.0)) / items
}

/**
 * Shares every item equally among its owners, the first picker taking any odd cent, then
 * scales each person by the same whole-bill [adjustment]. Leftover cents after scaling go to
 * the largest remainders, so the shares always add up to the bill's total.
 */
fun splitBill(items: List<BillItem>, people: List<Int>, adjustment: Double): BillSplit {
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
    val totalCents = (itemsCents * (1 + adjustment)).roundToLong()
    val exact = subtotals.map { it * (1 + adjustment) }
    val totals = exact.map { floor(it).toLong() }.toMutableList()
    var left = totalCents - totals.sum()
    val byRemainder = exact.indices.sortedByDescending { exact[it] - floor(exact[it]) }
    for (index in byRemainder) {
        if (left <= 0) break
        totals[index]++
        left--
    }
    val shares = people.mapIndexed { index, person ->
        PersonShare(person, subtotals[index], totals[index], lines.getValue(person))
    }
    return BillSplit(shares, itemsCents, totalCents)
}
