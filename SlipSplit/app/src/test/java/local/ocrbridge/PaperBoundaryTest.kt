package local.ocrbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PaperBoundaryTest {
    private val side = 320

    /** A mask that is 1 inside the polygon, given in photo pixels, and 0 elsewhere. */
    private fun mask(corners: FloatArray, photoWidth: Int, photoHeight: Int): FloatArray {
        fun inside(x: Double, y: Double): Boolean {
            var sign = 0
            for (i in 0..3) {
                val ax = corners[i * 2].toDouble(); val ay = corners[i * 2 + 1].toDouble()
                val bx = corners[(i + 1) % 4 * 2].toDouble(); val by = corners[(i + 1) % 4 * 2 + 1].toDouble()
                val turn = (bx - ax) * (y - ay) - (by - ay) * (x - ax)
                val s = if (turn > 0) 1 else -1
                if (sign == 0) sign = s else if (s != sign) return false
            }
            return true
        }
        return FloatArray(side * side) { index ->
            val x = (index % side + 0.5) * photoWidth / side
            val y = (index / side + 0.5) * photoHeight / side
            if (inside(x, y)) 1f else 0f
        }
    }

    @Test
    fun findsTheCornersOfATiltedReceipt() {
        val corners = floatArrayOf(160f, 100f, 470f, 140f, 430f, 1100f, 110f, 1060f)
        val found = PaperBoundary.quad(mask(corners, 600, 1200), 600, 1200)
        assertNotNull(found)
        for (i in 0 until 8) assertEquals("coordinate $i", corners[i], found!![i], 12f)
    }

    @Test
    fun ignoresAMaskThatCoversNearlyNothing() {
        val corners = floatArrayOf(10f, 10f, 40f, 10f, 40f, 40f, 10f, 40f)
        assertNull(PaperBoundary.quad(mask(corners, 600, 1200), 600, 1200))
    }
}
