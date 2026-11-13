package local.ocrbridge

import org.junit.Assert.assertEquals
import org.junit.Test

class BillSplitTest {
    private val people = listOf(0, 1)

    @Test
    fun givesUnpickedItemsToTheLastPerson() {
        val items = listOf(BillItem(1, "Water", 248, pickers = listOf(0)), BillItem(2, "Bag", 10))
        val split = splitBill(items, people, Adjustment())
        assertEquals(listOf(248L, 10L), split.shares.map { it.total })
    }

    @Test
    fun sharesAnItemEquallyAndTheFirstPickerTakesTheOddCent() {
        val items = listOf(BillItem(1, "Lamb", 1001, pickers = listOf(1, 0)))
        val split = splitBill(items, people, Adjustment())
        assertEquals(listOf(500L, 501L), split.shares.map { it.total })
    }

    @Test
    fun ignoresPickersWhoWereRemoved() {
        val items = listOf(BillItem(1, "Tea", 598, pickers = listOf(2)))
        val split = splitBill(items, people, Adjustment())
        assertEquals(listOf(0L, 598L), split.shares.map { it.total })
    }

    @Test
    fun splitsAFixedDiscountEvenlyAmongPeopleWithItemsAndKeepsTheTotal() {
        val items = listOf(
            BillItem(1, "Water", 248, pickers = listOf(0)),
            BillItem(2, "Tea", 598, pickers = listOf(0)),
            BillItem(3, "Lamb", 1398, pickers = listOf(0)),
            BillItem(4, "Rest", 1454),
        )
        val split = splitBill(items, listOf(0, 1, 2), Adjustment(-501))
        assertEquals(3698L - 501L, split.totalCents)
        assertEquals(split.totalCents, split.shares.sumOf { it.total })
        // Person 1 took nothing and pays nothing; the odd cent goes to one of the others.
        assertEquals(0L, split.shares[1].total)
        assertEquals(listOf(-250L, 0L, -251L).sorted(), split.shares.map { it.adjustment }.sorted())
    }

    @Test
    fun sharesAPercentageAdjustmentByWhatEachPersonTook() {
        val items = listOf(BillItem(1, "A", 1000, pickers = listOf(0)), BillItem(2, "B", 3000))
        val split = splitBill(items, people, Adjustment(400, proportional = true))
        assertEquals(listOf(1100L, 3300L), split.shares.map { it.total })
        assertEquals(listOf(100L, 300L), split.shares.map { it.adjustment })
    }

    @Test
    fun readsTheWholeBillAdjustmentFromThePrintedFields() {
        fun items(vararg prices: Double) = prices.map { ReceiptItem("A", 1, it) }.toMutableList()
        // Rounding after a subtotal that matches the items.
        assertEquals(Adjustment(2), billAdjustment(Receipt(items(36.98), subtotal = 36.98, total = 37.00)))
        assertEquals(Adjustment(200), billAdjustment(Receipt(items(20.0), service = 2.0, total = 22.0)))
        assertEquals(Adjustment(), billAdjustment(Receipt(total = 17.81)))
        // Prices already discounted add up to the total: the printed discount and gross subtotal add nothing.
        val net = Receipt(items(8.96, 3.5, 19.9), subtotal = 37.2, discount = 4.84, total = 32.36)
        assertEquals(Adjustment(), billAdjustment(net))
        // A fixed voucher stays a fixed amount; a printed percentage makes it proportional.
        assertEquals(Adjustment(-500), billAdjustment(Receipt(items(30.0), discount = 5.0, total = 25.0)))
        val percent = Receipt(items(30.0), discount = 3.0, total = 27.0).also { it.percentage = true }
        assertEquals(Adjustment(-300, proportional = true), billAdjustment(percent))
        // Tax added on top is a percentage of the items.
        assertEquals(Adjustment(300, proportional = true), billAdjustment(Receipt(items(30.0), tax = 3.0, total = 33.0)))
    }
}
