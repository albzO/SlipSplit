package local.ocrbridge

import org.junit.Assert.*
import org.junit.Test

class BackwardMapTest {
    @Test fun identityOnNonSquareImage() {
        val pixels = intArrayOf(0xFF102030.toInt(), 0xFF405060.toInt(), 0xFF708090.toInt(),
            0xFFA0B0C0.toInt(), 0xFFD0E0F0.toInt(), 0xFF010203.toInt())
        // A 3x3 grid resizes to a 3x2 image using OpenCV half-pixel coordinates.
        val grid = floatArrayOf(-1f, 0f, 1f, -1f, 0f, 1f, -1f, 0f, 1f,
            -4f/3, -4f/3, -4f/3, 0f, 0f, 0f, 4f/3, 4f/3, 4f/3)
        assertArrayEquals(pixels, BackwardMap.sample(pixels, 3, 2, grid, 3))
    }

    @Test fun planarXYAndBilinearColors() {
        val pixels = intArrayOf(0xFF000000.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt())
        val grid = FloatArray(8) // Always sample the center of the original.
        val output = BackwardMap.remap(pixels, 2, 2, grid, 2)
        assertTrue(output.all { it == 0xFF404040.toInt() })
    }

    @Test fun outsideMapUsesBlackPadding() {
        val grid = FloatArray(8) { 10f }
        assertTrue(BackwardMap.remap(IntArray(4) { -1 }, 2, 2, grid, 2).all { it == 0xFF000000.toInt() })
    }

    @Test(expected = IllegalArgumentException::class) fun rejectInvalidGrid() {
        BackwardMap.remap(IntArray(4), 2, 2, FloatArray(8) { Float.NaN }, 2)
    }
}
