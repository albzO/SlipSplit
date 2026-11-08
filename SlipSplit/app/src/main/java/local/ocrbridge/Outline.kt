package local.ocrbridge

import kotlin.math.abs
import kotlin.math.hypot

/** A point in pixels. */
internal class Pt(val x: Double, val y: Double)

/** One connected region of a mask: its size, the cells on its edge and which cells it holds. */
internal class Region(val count: Int, val edge: List<Pt>, val cells: BooleanArray, val width: Int, val height: Int)

/** Plane geometry for tracing the paper's outline, so finding it needs no image library. */
internal object Outline {
    private fun cross(o: Pt, a: Pt, b: Pt) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

    /** Andrew's monotone chain; the hull runs counter-clockwise in y-up terms. */
    fun convexHull(points: List<Pt>): List<Pt> {
        val sorted = points.sortedWith(compareBy({ it.x }, { it.y }))
        if (sorted.size < 3) return sorted
        fun half(input: List<Pt>): List<Pt> {
            val hull = ArrayList<Pt>()
            for (point in input) {
                while (hull.size >= 2 && cross(hull[hull.size - 2], hull.last(), point) <= 0) hull.removeAt(hull.size - 1)
                hull.add(point)
            }
            return hull.dropLast(1)
        }
        return half(sorted) + half(sorted.reversed())
    }

    fun perimeter(closed: List<Pt>) = closed.indices.sumOf { i ->
        val next = closed[(i + 1) % closed.size]
        hypot(next.x - closed[i].x, next.y - closed[i].y)
    }

    fun area(polygon: List<Pt>): Double {
        var twice = 0.0
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            twice += a.x * b.y - a.y * b.x
        }
        return abs(twice) / 2
    }

    private fun distanceToLine(p: Pt, a: Pt, b: Pt): Double {
        val length = hypot(b.x - a.x, b.y - a.y)
        if (length == 0.0) return hypot(p.x - a.x, p.y - a.y)
        return abs(cross(a, b, p)) / length
    }

    /** Douglas–Peucker on an open chain, keeping both ends. */
    private fun simplifyChain(chain: List<Pt>, epsilon: Double): List<Pt> {
        if (chain.size < 3) return chain
        var farthest = 0
        var distance = 0.0
        for (i in 1 until chain.size - 1) {
            val d = distanceToLine(chain[i], chain.first(), chain.last())
            if (d > distance) {
                distance = d
                farthest = i
            }
        }
        if (distance <= epsilon) return listOf(chain.first(), chain.last())
        val left = simplifyChain(chain.subList(0, farthest + 1), epsilon)
        val right = simplifyChain(chain.subList(farthest, chain.size), epsilon)
        return left.dropLast(1) + right
    }

    /**
     * Douglas–Peucker on a closed outline: split it at two far-apart points and simplify
     * each side.
     */
    fun simplify(closed: List<Pt>, epsilon: Double): List<Pt> {
        if (closed.size < 4) return closed
        fun farthestFrom(index: Int) = closed.indices.maxBy {
            hypot(closed[it].x - closed[index].x, closed[it].y - closed[index].y)
        }
        val start = farthestFrom(farthestFrom(0))
        val end = farthestFrom(start)
        val rotated = closed.subList(start, closed.size) + closed.subList(0, start)
        val split = (end - start + closed.size) % closed.size
        val first = simplifyChain(rotated.subList(0, split + 1), epsilon)
        val second = simplifyChain(rotated.subList(split, rotated.size) + rotated.first(), epsilon)
        return first.dropLast(1) + second.dropLast(1)
    }

    /**
     * The four outline points enclosing the most area, kept in outline order. On a convex
     * outline these are its corners, wherever a nick or fold puts extra points between them.
     */
    fun largestQuad(outline: List<Pt>): List<Pt>? {
        val n = outline.size
        if (n < 4) return null
        var best: List<Pt>? = null
        var bestArea = 0.0
        for (a in 0 until n) for (b in a + 1 until n) for (c in b + 1 until n) for (d in c + 1 until n) {
            val quad = listOf(outline[a], outline[b], outline[c], outline[d])
            val area = area(quad)
            if (area > bestArea) {
                bestArea = area
                best = quad
            }
        }
        return best
    }

    /** The smallest rectangle around a convex hull; one of its sides lies on a hull edge. */
    fun minAreaRect(hull: List<Pt>): List<Pt> {
        var best: List<Pt> = emptyList()
        var bestArea = Double.MAX_VALUE
        for (i in hull.indices) {
            val a = hull[i]
            val b = hull[(i + 1) % hull.size]
            val length = hypot(b.x - a.x, b.y - a.y)
            if (length == 0.0) continue
            val ux = (b.x - a.x) / length
            val uy = (b.y - a.y) / length
            val us = hull.map { it.x * ux + it.y * uy }
            val vs = hull.map { -it.x * uy + it.y * ux }
            val area = (us.max() - us.min()) * (vs.max() - vs.min())
            if (area < bestArea) {
                bestArea = area
                fun corner(u: Double, v: Double) = Pt(u * ux - v * uy, u * uy + v * ux)
                best = listOf(
                    corner(us.min(), vs.min()), corner(us.max(), vs.min()),
                    corner(us.max(), vs.max()), corner(us.min(), vs.max()),
                )
            }
        }
        return best
    }

    /**
     * The largest 4-connected region of set cells, as its pixel count and the cells on its edge.
     */
    fun largestRegion(cells: BooleanArray, width: Int, height: Int): Region? {
        val label = IntArray(cells.size)
        val stack = IntArray(cells.size)
        var bestLabel = 0
        var bestCount = 0
        var next = 0
        for (start in cells.indices) {
            if (!cells[start] || label[start] != 0) continue
            next++
            var count = 0
            var top = 0
            stack[top++] = start
            label[start] = next
            while (top > 0) {
                val index = stack[--top]
                count++
                val x = index % width
                val y = index / width
                for ((nx, ny) in listOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1)) {
                    if (nx !in 0 until width || ny !in 0 until height) continue
                    val n = ny * width + nx
                    if (cells[n] && label[n] == 0) {
                        label[n] = next
                        stack[top++] = n
                    }
                }
            }
            if (count > bestCount) {
                bestCount = count
                bestLabel = next
            }
        }
        if (bestLabel == 0) return null
        fun inside(x: Int, y: Int) = x in 0 until width && y in 0 until height && label[y * width + x] == bestLabel
        val edge = ArrayList<Pt>()
        for (y in 0 until height) for (x in 0 until width) {
            if (inside(x, y) && !(inside(x - 1, y) && inside(x + 1, y) && inside(x, y - 1) && inside(x, y + 1))) {
                edge.add(Pt(x.toDouble(), y.toDouble()))
            }
        }
        return Region(bestCount, edge, BooleanArray(cells.size) { label[it] == bestLabel }, width, height)
    }

    /**
     * Intersection over union of a convex quad and a region: 1 when the quad covers exactly the
     * region, lower for paper it leaves out and for background it takes in.
     */
    fun overlap(quad: List<Pt>, region: Region): Double {
        var both = 0
        var quadOnly = 0
        for (y in 0 until region.height) for (x in 0 until region.width) {
            var sign = 0
            var inside = true
            for (i in 0..3) {
                val turn = cross(quad[i], quad[(i + 1) % 4], Pt(x.toDouble(), y.toDouble()))
                val s = if (turn > 0) 1 else if (turn < 0) -1 else 0
                if (s == 0) continue
                if (sign == 0) sign = s else if (s != sign) { inside = false; break }
            }
            val set = region.cells[y * region.width + x]
            if (inside && set) both++ else if (inside) quadOnly++
        }
        return both.toDouble() / (region.count + quadOnly)
    }
}
