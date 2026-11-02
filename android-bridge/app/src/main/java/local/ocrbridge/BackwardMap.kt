package local.ocrbridge

import kotlin.math.floor

/** Matches OpenCV blur/resize then grid_sample(align_corners=true, padding=zeros). */
internal object BackwardMap {
    fun remap(pixels: IntArray, width: Int, height: Int, grid: FloatArray, size: Int): IntArray {
        require(width > 0 && height > 0 && size > 1)
        require(pixels.size == width * height && grid.size == 2 * size * size)
        require(grid.all { it.isFinite() }) { "Invalid DewarpNet mapping" }
        val plane = size * size
        val smooth = FloatArray(grid.size)
        fun reflect(p: Int) = when { p < 0 -> -p; p >= size -> 2 * size - 2 - p; else -> p }
        for (c in 0..1) for (y in 0 until size) for (x in 0 until size) {
            var sum = 0f
            for (dy in -1..1) for (dx in -1..1) {
                sum += grid[c * plane + reflect(y + dy) * size + reflect(x + dx)]
            }
            smooth[c * plane + y * size + x] = sum / 9f
        }
        return sample(pixels, width, height, smooth, size)
    }

    internal fun sample(pixels: IntArray, width: Int, height: Int, grid: FloatArray, size: Int): IntArray {
        val plane = size * size
        fun coordinate(c: Int, x: Int, y: Int): Float {
            val gx = ((x + 0.5f) * size / width - 0.5f).coerceIn(0f, size - 1f)
            val gy = ((y + 0.5f) * size / height - 0.5f).coerceIn(0f, size - 1f)
            val x0 = gx.toInt(); val y0 = gy.toInt()
            val x1 = minOf(x0 + 1, size - 1); val y1 = minOf(y0 + 1, size - 1)
            val dx = gx - x0; val dy = gy - y0
            val offset = c * plane
            return (grid[offset + y0 * size + x0] * (1 - dx) + grid[offset + y0 * size + x1] * dx) * (1 - dy) +
                (grid[offset + y1 * size + x0] * (1 - dx) + grid[offset + y1 * size + x1] * dx) * dy
        }
        fun pixel(x: Int, y: Int) = if (x in 0 until width && y in 0 until height) pixels[y * width + x] else 0
        return IntArray(pixels.size) { index ->
            val x = (coordinate(0, index % width, index / width) + 1f) * (width - 1) / 2f
            val y = (coordinate(1, index % width, index / width) + 1f) * (height - 1) / 2f
            val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
            val dx = x - x0; val dy = y - y0
            val a = pixel(x0, y0); val b = pixel(x0 + 1, y0)
            val c = pixel(x0, y0 + 1); val d = pixel(x0 + 1, y0 + 1)
            var color = 0xFF000000.toInt()
            for (shift in 16 downTo 0 step 8) {
                val top = ((a ushr shift) and 255) * (1 - dx) + ((b ushr shift) and 255) * dx
                val bottom = ((c ushr shift) and 255) * (1 - dx) + ((d ushr shift) and 255) * dx
                val value = (top * (1 - dy) + bottom * dy + 0.5f).toInt().coerceIn(0, 255)
                color = color or (value shl shift)
            }
            color
        }
    }
}
