package local.ocrbridge

import org.junit.Assert.assertEquals
import org.junit.Test

class BillSplitTest {
    private val people = listOf(0, 1)

    @Test
    fun givesUnpickedItemsToTheLastPerson() {
        val items = listOf(BillItem(1, "Water", 248, pickers = listOf(0)), BillItem(2, "Bag", 10))
        val split = splitBill(items, people, 0.0)
        assertEquals(listOf(248L, 10L), split.shares.map { it.total })
    }

    @Test
    fun sharesAnItemEquallyAndTheFirstPickerTakesTheOddCent() {
        val items = listOf(BillItem(1, "Lamb", 1001, pickers = listOf(1, 0)))
        val split = splitBill(items, people, 0.0)
        assertEquals(listOf(500L, 501L), split.shares.map { it.total })
    }

    @Test
    fun ignoresPickersWhoWereRemoved() {
        val items = listOf(BillItem(1, "Tea", 598, pickers = listOf(2)))
        val split = splitBill(items, people, 0.0)
        assertEquals(listOf(0L, 598L), split.shares.map { it.total })
    }

    @Test
    fun scalesEveryoneByTheWholeBillShareAndKeepsTheTotal() {
        // Yonghui: items 36.98, rounding brings the bill to 37.00.
        val items = listOf(
            BillItem(1, "Water", 248, pickers = listOf(0)),
            BillItem(2, "Tea", 598, pickers = listOf(0)),
            BillItem(3, "Lamb", 1398, pickers = listOf(0, 1)),
            BillItem(4, "Rest", 1454),
        )
        val split = splitBill(items, people, (37.00 - 36.98) / 36.98)
        assertEquals(3700L, split.totalCents)
        assertEquals(3700L, split.shares.sumOf { it.total })
        assertEquals(listOf(1546L, 2154L), split.shares.map { it.total })
    }

    @Test
    fun takesASurchargeOffEachPersonByTheSameShare() {
        val items = listOf(BillItem(1, "A", 1000, pickers = listOf(0)), BillItem(2, "B", 3000))
        val split = splitBill(items, people, 0.10)
        assertEquals(listOf(1100L, 3300L), split.shares.map { it.total })
        assertEquals(listOf(100L, 300L), split.shares.map { it.adjustment })
    }

    @Test
    fun readsTheWholeBillShareFromThePrintedFields() {
        val withSubtotal = Receipt(subtotal = 36.98, total = 37.00)
        assertEquals(0.02 / 36.98, billAdjustment(withSubtotal), 1e-9)
        val withService = Receipt(items = mutableListOf(ReceiptItem("A", 1, 20.0)), service = 2.0, total = 22.0)
        assertEquals(0.1, billAdjustment(withService), 1e-9)
        assertEquals(0.0, billAdjustment(Receipt(total = 17.81)), 1e-9)
    }
}
