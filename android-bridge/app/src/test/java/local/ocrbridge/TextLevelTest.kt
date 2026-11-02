package local.ocrbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class TextLevelTest {
    private val width = 800
    private val height = 1600

    /** Boxes along rows bent by `shape`, split into three cells per row like real OCR. */
    private fun boxes(shape: (Double) -> Double, slope: (Double) -> Double): List<FloatArray> {
        val polygons = ArrayList<FloatArray>()
        for (row in 2 until 26) {
            val y = row * 60.0
            for (cell in 0 until 3) {
                val left = 60.0 + cell * 240
                val right = left + 200
                val middle = (left + right) / 2
                val rise = slope(middle) * 100
                val center = y + shape(middle)
                polygons.add(floatArrayOf(
                    left.toFloat(), (center - rise - 12).toFloat(),
                    right.toFloat(), (center + rise - 12).toFloat(),
                    right.toFloat(), (center + rise + 12).toFloat(),
                    left.toFloat(), (center - rise + 12).toFloat(),
                ))
            }
        }
        return polygons
    }

    @Test
    fun levelRowsNeedNoWork() {
        val fit = TextLevel.fit(boxes({ 0.0 }, { 0.0 }), width, height)
        assertNotNull(fit)
        assertFalse(fit!!.needsLevelling)
    }

    @Test
    fun measuresAndUndoesACurl() {
        val size = PI / 800
        val polygons = boxes({ 60 * sin(size * it) }, { 60 * size * cos(size * it) })
        val fit = requireNotNull(TextLevel.fit(polygons, width, height))
        assertTrue("bend ${fit.bend}", fit.bend > 0.7)
        assertTrue(fit.needsLevelling)
        val row = 800.0 + 60 * sin(size * 160)
        assertEquals(800.0 + 60 * sin(size * 400), fit.field.trace(160.0, row, 400.0), 8.0)
    }

    @Test
    fun keepsTooLittleTextUnchanged() {
        assertNull(TextLevel.fit(boxes({ 0.0 }, { 0.0 }).take(3), width, height))
    }

    @Test
    fun flattenMovesPixelsAlongTheField() {
        val nodes = DoubleArray(91) { 0.1 }
        val field = SlopeField(100, 100, nodes, 50.0)
        val pixels = IntArray(100 * 100) { index -> if (abs(index / 100 - (50 + (index % 100 - 50) * 0.1)) < 1) -1 else 0xFF shl 24 }
        val flat = requireNotNull(TextLevel.flatten(pixels, 100, 100, field))
        val bright = (0 until 100).filter { flat[50 * 100 + it] and 255 > 128 }
        assertTrue("bright ${bright.size}", bright.size > 90)
    }
}
