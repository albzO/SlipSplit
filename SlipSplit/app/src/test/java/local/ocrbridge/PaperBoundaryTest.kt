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

    @Test
    fun extendsTheSidesOverACurledCorner() {
        // A receipt seen at an angle whose top-left corner curls back.
        val corners = floatArrayOf(160f, 100f, 470f, 140f, 430f, 1100f, 110f, 1060f)
        val full = mask(corners, 600, 1200)
        val curled = FloatArray(full.size) { index ->
            val x = (index % side + 0.5) * 600 / side
            val y = (index / side + 0.5) * 1200 / side
            if ((x - 160) + (y - 100) < 100) 0f else full[index]
        }
        val found = PaperBoundary.quad(curled, 600, 1200)
        assertNotNull(found)
        for (i in 0 until 8) assertEquals("coordinate $i", corners[i], found!![i], 12f)
    }

    @Test
    fun leavesOutAHandTheMaskJoinsToThePaper() {
        // A hand comes in from the photo's left edge and covers part of the paper's left side.
        val corners = floatArrayOf(200f, 100f, 460f, 130f, 440f, 1100f, 220f, 1080f)
        val paper = mask(corners, 600, 1200)
        val joined = FloatArray(paper.size) { index ->
            val x = (index % side + 0.5) * 600 / side
            val y = (index / side + 0.5) * 1200 / side
            val palm = (x / 280) * (x / 280) + ((y - 650) / 260) * ((y - 650) / 260) <= 1
            if (palm) 1f else paper[index]
        }
        val found = PaperBoundary.quad(joined, 600, 1200)
        assertNotNull(found)
        for (i in 0 until 8) assertEquals("coordinate $i", corners[i], found!![i], 16f)
    }
}
