package local.ocrbridge

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Replays OCR output from the sample receipts against the results recorded for them. */
class ReceiptParserTest {
    private fun JSONObject.amount(key: String): Double? = if (isNull(key)) null else getDouble(key)

    private fun describe(items: List<ReceiptItem>) =
        items.joinToString("\n") { "${it.sku} | ${it.name} | ${it.spec} | ${it.qty} | ${it.price}" }

    @Test
    fun matchesPythonParserOnSamples() {
        val fixture = javaClass.getResourceAsStream("/receipts.json")!!
            .bufferedReader(Charsets.UTF_8).readText()
        val samples = JSONArray(fixture)
        assertEquals(28, samples.length())
        for (index in 0 until samples.length()) {
            val sample = samples.getJSONObject(index)
            val image = sample.getString("image")
            val rawLines = sample.getJSONArray("lines")
            val lines = (0 until rawLines.length()).map {
                val line = rawLines.getJSONObject(it)
                val box = line.getJSONArray("box")
                val poly = line.optJSONArray("poly")
                val corners = poly?.let {
                    FloatArray(8) { i -> it.getJSONArray(i / 2).getInt(i % 2).toFloat() }
                }
                OcrLine(line.getString("text"), IntArray(4) { i -> box.getInt(i) }, 0f, corners)
            }
            val expected = sample.getJSONObject("expected")
            val rawItems = expected.getJSONArray("items")
            val expectedItems = (0 until rawItems.length()).map {
                val item = rawItems.getJSONObject(it)
                ReceiptItem(
                    item.getString("name"),
                    item.getInt("qty"),
                    item.getDouble("price"),
                    item.getString("sku"),
                    item.getString("spec"),
                )
            }
            val actual = parseReceipt(lines)
            assertEquals(image, describe(expectedItems), describe(actual.items))
            val rawDropped = expected.getJSONArray("dropped")
            val expectedDropped = (0 until rawDropped.length()).map {
                val item = rawDropped.getJSONObject(it)
                ReceiptItem(
                    item.getString("name"),
                    item.getInt("qty"),
                    item.getDouble("price"),
                    item.getString("sku"),
                    item.getString("spec"),
                )
            }
            assertEquals("$image dropped", describe(expectedDropped), describe(actual.dropped))
            assertEquals("$image total", expected.amount("total"), actual.total)
            assertEquals("$image subtotal", expected.amount("subtotal"), actual.subtotal)
            assertEquals("$image tax", expected.amount("tax"), actual.tax)
            assertEquals("$image service", expected.amount("service"), actual.service)
            assertEquals("$image discount", expected.amount("discount"), actual.discount)
            assertEquals("$image sum", sample.getBoolean("sum_ok"), sumMatches(actual))
            assertEquals("$image company", expected.getString("company"), actual.company)
            assertEquals("$image date", expected.getString("date"), actual.date)
            assertEquals("$image time", expected.getString("time"), actual.time)
        }
    }

    @Test
    fun pricesItemsFromTheAmountColumnOnly() {
        val rows = listOf(
            "2012" to intArrayOf(500, 0, 590, 30),
            "Lamb Shoulder @ \$36.00" to intArrayOf(0, 50, 540, 80),
            "1 x \$36.00 = \$36.00" to intArrayOf(300, 90, 830, 120),
            "large long black 9 5.50" to intArrayOf(0, 130, 588, 160),
            ">Kenya @ \$1.00" to intArrayOf(60, 170, 400, 200),
            "1 x \$6.50 = \$6.50" to intArrayOf(360, 210, 830, 240),
            "Subtotal: \$42.50" to intArrayOf(420, 300, 830, 330),
        )
        val receipt = parseReceipt(rows.map { (text, box) -> OcrLine(text, box) })
        assertEquals(
            listOf(
                ReceiptItem("Lamb Shoulder", 1, 36.0),
                ReceiptItem("large long black 9", 1, 6.5, "", "Kenya"),
            ),
            receipt.items,
        )
    }

    @Test
    fun readsRowsAcrossATiltedPhoto() {
        val slope = 0.1f
        fun line(text: String, left: Float, right: Float, top: Float): OcrLine {
            val corners = floatArrayOf(
                left, top + left * slope,
                right, top + right * slope,
                right, top + 30 + right * slope,
                left, top + 30 + left * slope,
            )
            val box = intArrayOf(
                left.toInt(), corners[1].toInt(), right.toInt(), corners[5].toInt(),
            )
            return OcrLine(text, box, 0f, corners)
        }
        val lines = listOf(
            line("Long black coffee", 0f, 300f, 0f),
            line("5.50", 700f, 800f, 0f),
            line("Banana bread slice", 0f, 300f, 50f),
            line("9.00", 700f, 800f, 50f),
            line("Avocado on toast", 0f, 300f, 100f),
            line("7.00", 700f, 800f, 100f),
            line("TOTAL", 0f, 300f, 150f),
            line("21.50", 700f, 800f, 150f),
        )
        val receipt = parseReceipt(lines)
        assertEquals(listOf(5.5, 9.0, 7.0), receipt.items.map { it.price })
        assertEquals(21.5, receipt.total)
    }

    @Test
    fun attachesDetailPrintedBelowTheAmountRow() {
        val rows = listOf(
            "0369446 BATTERIES ENERGIZER \$3.90" to intArrayOf(0, 0, 800, 30),
            "ENR CR2025 2PK" to intArrayOf(120, 40, 500, 70),
            "0011875 BATTERIES AAA \$2.15" to intArrayOf(0, 80, 800, 110),
            "4PK BBA-4AAA" to intArrayOf(120, 120, 400, 150),
            "Total \$6.05" to intArrayOf(0, 200, 800, 230),
        )
        val lines = rows.map { (text, box) -> OcrLine(text, box) }
        val receipt = parseReceipt(lines)
        assertEquals(listOf("ENR CR2025 2PK", "4PK BBA-4AAA"), receipt.items.map { it.spec })
        assertEquals(lines.subList(0, 2), receipt.items[0].lines)
    }

    @Test
    fun keepsAnItemWhenItsAddOnIsBarelyIndented() {
        val rows = listOf(
            "large long black @ \$5.50" to intArrayOf(0, 0, 500, 30),
            ">Kenya @ \$1.00" to intArrayOf(5, 40, 300, 70),
            "1 x \$6.50 = \$6.50" to intArrayOf(400, 80, 830, 110),
            "Milk 2.00" to intArrayOf(0, 120, 830, 150),
            "Subtotal: \$8.50" to intArrayOf(400, 160, 830, 190),
        )
        val receipt = parseReceipt(rows.map { (text, box) -> OcrLine(text, box) })
        assertEquals(ReceiptItem("large long black", 1, 6.5, "", "Kenya"), receipt.items[0])
    }

    @Test
    fun treatsRowsBelowAUnitPriceAsItsDetails() {
        val rows = listOf(
            "Flat white @ \$5.00" to intArrayOf(0, 0, 500, 30),
            "Oat milk" to intArrayOf(0, 40, 300, 70),
            "Extra hot" to intArrayOf(0, 80, 300, 110),
            "1 x \$5.00 = \$5.00" to intArrayOf(400, 120, 830, 150),
            "Milk 2.00" to intArrayOf(0, 160, 830, 190),
            "Subtotal: \$7.00" to intArrayOf(400, 200, 830, 230),
        )
        val receipt = parseReceipt(rows.map { (text, box) -> OcrLine(text, box) })
        assertEquals(ReceiptItem("Flat white", 1, 5.0, "", "Oat milk, Extra hot"), receipt.items[0])
    }

    @Test
    fun keepsQuantityLineAsSpec() {
        val rows = listOf(
            "Ravioli 325g" to intArrayOf(0, 0, 400, 30),
            "Qty 2 @ \$5.40 each 10.80" to intArrayOf(0, 40, 800, 70),
            "Milk 5.15" to intArrayOf(0, 80, 800, 110),
            "TOTAL \$15.95" to intArrayOf(0, 120, 800, 150),
        )
        val receipt = parseReceipt(rows.map { (text, box) -> OcrLine(text, box) })
        assertEquals(ReceiptItem("Ravioli 325g", 2, 10.80, "", "Qty 2 @ \$5.40 each"), receipt.items[0])
    }

    @Test
    fun readsCompanyDateAndTimeFromTheHeader() {
        val rows = listOf(
            "BUNNINGS GROUP LIMITED" to intArrayOf(0, 0, 400, 30),
            "Ph: (02) 9336 2400" to intArrayOf(0, 40, 400, 70),
            "Mon 19/10/2026 04:55:34 PM" to intArrayOf(0, 80, 500, 110),
        )
        val receipt = parseReceipt(rows.map { (text, box) -> OcrLine(text, box) })
        assertEquals("BUNNINGS GROUP LIMITED", receipt.company)
        assertEquals("19/10/2026", receipt.date)
        assertEquals("04:55:34 PM", receipt.time)
    }

    @Test
    fun dropsRowsThePrintedTotalRulesOut() {
        val rows = listOf(
            "Coffee 5.50" to intArrayOf(0, 0, 800, 30),
            "Toast 9.00" to intArrayOf(0, 40, 800, 70),
            "SUB TOTAI 14.50" to intArrayOf(0, 80, 800, 110),
            "TOTAL 14.50" to intArrayOf(0, 120, 800, 150),
        )
        val receipt = parseReceipt(rows.map { (text, box) -> OcrLine(text, box) })
        assertEquals(listOf("Coffee", "Toast"), receipt.items.map { it.name })
        assertEquals(listOf("SUB TOTAI"), receipt.dropped.map { it.name })
    }

    @Test
    fun keepsTwoEqualDishesWhenTheTotalSaysSo() {
        val rows = listOf(
            "Coffee 5.00" to intArrayOf(0, 0, 800, 30),
            "Coffee 5.00" to intArrayOf(0, 40, 800, 70),
            "TOTAL 10.00" to intArrayOf(0, 80, 800, 110),
        )
        val receipt = parseReceipt(rows.map { (text, box) -> OcrLine(text, box) })
        assertEquals(2, receipt.items.size)
        assertEquals(0, receipt.dropped.size)
    }

    @Test
    fun keepsStockNumberAndSpecApartAndTracksTheirLines() {
        val rows = listOf(
            "0867068 LAUNDRY DETERGENT COLD POWER" to intArrayOf(0, 0, 500, 30),
            "72PK REGULAR TRIPPLE CAPS \$29.49" to intArrayOf(100, 40, 800, 70),
            "1210028 SUPER GLUE LOCTITE" to intArrayOf(0, 80, 500, 110),
            "5G PRECISION LIQUID \$7.30" to intArrayOf(100, 120, 800, 150),
            "Total \$36.79" to intArrayOf(0, 200, 800, 230),
            "Thank you" to intArrayOf(0, 300, 300, 330),
        )
        val lines = rows.map { (text, box) -> OcrLine(text, box) }
        val receipt = parseReceipt(lines)
        assertEquals(
            listOf(
                ReceiptItem("LAUNDRY DETERGENT COLD POWER", 1, 29.49, "0867068", "72PK REGULAR TRIPPLE CAPS"),
                ReceiptItem("SUPER GLUE LOCTITE", 1, 7.30, "1210028", "5G PRECISION LIQUID"),
            ),
            receipt.items,
        )
        assertEquals(lines.subList(0, 2), receipt.items[0].lines)
        assertEquals(listOf(lines[4]), receipt.fieldLines)
    }

    @Test
    fun tellsEnglishReceiptsFromChineseOnes() {
        assertEquals(true, looksEnglish(listOf("Lamb Shoulder", "Total Sale", "Thank you 大水")))
        assertEquals(false, looksEnglish(listOf("西贝莜面村", "合计 87.00", "Visa")))
        assertEquals(true, isEnglishCharacter("£"))
        assertEquals(false, isEnglishCharacter("大"))
    }

    @Test
    fun readsAustralianReceiptConventions() {
        val rows = listOf(
            "^ Milk Full Cream 2L 3.10",
            "# Bananas",
            "1.234 kg NET @ \$3.99/kg 4.92",
            "Sunday Surcharge 10% 1.95",
            "TOTAL \$11.97",
            "Total includes GST \$0.41",
        )
        val lines = rows.mapIndexed { index, text ->
            OcrLine(text, intArrayOf(0, index * 40, 300, index * 40 + 30))
        }
        val receipt = parseReceipt(lines)
        assertEquals(listOf("Milk Full Cream 2L", "Bananas"), receipt.items.map { it.name })
        assertEquals(11.97, receipt.total)
        assertEquals(0.41, receipt.tax)
        assertEquals(1.95, receipt.service)
    }
}
