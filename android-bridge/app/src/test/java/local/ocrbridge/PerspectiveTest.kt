package local.ocrbridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PerspectiveTest {
    @Test
    fun movesEverySideOutwardByTheSameMargin() {
        val corners = floatArrayOf(100f, 200f, 300f, 200f, 300f, 800f, 100f, 800f)
        val padded = Perspective.padded(corners, 1000, 1000)
        assertArrayEquals(floatArrayOf(92f, 192f, 308f, 192f, 308f, 808f, 92f, 808f), padded, 0.01f)
    }

    @Test
    fun staysInsideThePhoto() {
        val corners = floatArrayOf(5f, 5f, 395f, 5f, 395f, 595f, 5f, 595f)
        val padded = Perspective.padded(corners, 400, 600)
        assertEquals(0f, padded.min(), 0f)
        assertEquals(399f, padded[2], 0f)
        assertEquals(599f, padded[5], 0f)
    }

    @Test
    fun keepsAFullPhotoSelectionUnchanged() {
        val full = Perspective.fullImage(400, 600)
        assertArrayEquals(full, Perspective.padded(full, 400, 600), 0f)
    }

    @Test
    fun widensASlantedQuadWithoutCrossingSides() {
        val corners = floatArrayOf(120f, 100f, 300f, 90f, 340f, 700f, 90f, 710f)
        val padded = Perspective.padded(corners, 1000, 1000)
        assert(padded[0] < 120f && padded[1] < 100f && padded[4] > 340f && padded[5] > 700f)
        assert(Perspective.valid(padded))
    }
}
