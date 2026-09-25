package com.projectx.script.impl.combat.raksha

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

internal data class Point(val x: Int, val y: Int)

internal data class HazardBox(val minX: Double, val maxX: Double, val minY: Double, val maxY: Double, val clearance: Double) {
    val centerX: Double get() = (minX + maxX) / 2
    val centerY: Double get() = (minY + maxY) / 2

    fun distanceTo(x: Double, y: Double): Double {
        val dx = max(max(minX - x, 0.0), x - maxX)
        val dy = max(max(minY - y, 0.0), y - maxY)
        return hypot(dx, dy)
    }

    companion object {
        fun around(x: Double, y: Double, half: Double, clearance: Double) =
            HazardBox(x - half, x + half, y - half, y + half, clearance)
    }
}

internal object ArenaGeometry {
    private const val PATH_MARGIN = 1.0
    const val ORBIT_SLOTS = 16

    private val rings = HashMap<Int, List<Point>>()

    fun escapeClearance(safeRange: Int, triggerRange: Int): Double = max(safeRange, triggerRange + 1).toDouble()

    fun tileIsClear(x: Int, y: Int, hazards: List<HazardBox>): Boolean =
        hazards.none { it.distanceTo(x.toDouble(), y.toDouble()) < it.clearance }

    private fun outsideHazards(x: Int, y: Int, hazards: List<HazardBox>): Boolean =
        hazards.none { it.distanceTo(x.toDouble(), y.toDouble()) < PATH_MARGIN }

    fun pathHazardCost(x0: Int, y0: Int, x1: Int, y1: Int, hazards: List<HazardBox>): Int {
        val dx = x1 - x0
        val dy = y1 - y0
        val steps = max(abs(dx), abs(dy))
        if (steps == 0) return 0
        var cost = 0
        for (i in 1..steps) {
            val t = i.toDouble() / steps
            val px = floor(x0 + dx * t + 0.5).toInt()
            val py = floor(y0 + dy * t + 0.5).toInt()
            if (!outsideHazards(px, py, hazards)) cost++
        }
        return cost
    }

    fun distance(x0: Number, y0: Number, x1: Number, y1: Number): Double =
        hypot(x0.toDouble() - x1.toDouble(), y0.toDouble() - y1.toDouble())

    fun findSafeTile(
        from: Point,
        hazards: List<HazardBox>,
        anchor: Point?,
        anchorRadius: Double,
        searchRadius: Int = 10,
    ): Point? {
        var best: Point? = null
        var bestCost = Int.MAX_VALUE
        var bestMove = Double.MAX_VALUE
        for (dx in -searchRadius..searchRadius) {
            for (dy in -searchRadius..searchRadius) {
                val move = hypot(dx.toDouble(), dy.toDouble())
                if (move > searchRadius) continue
                val tx = from.x + dx
                val ty = from.y + dy
                if (anchor != null && distance(tx, ty, anchor.x, anchor.y) > anchorRadius) continue
                if (!tileIsClear(tx, ty, hazards)) continue
                val cost = pathHazardCost(from.x, from.y, tx, ty, hazards)
                if (cost < bestCost || (cost == bestCost && move < bestMove)) {
                    best = Point(tx, ty)
                    bestCost = cost
                    bestMove = move
                }
            }
        }
        return best
    }

    fun steppingStone(from: Point, to: Point, hazards: List<HazardBox>): Point? {
        val currentGap = distance(to.x, to.y, from.x, from.y)
        var best: Point? = null
        var bestGap = Double.MAX_VALUE
        for (dx in -8..8) {
            for (dy in -8..8) {
                val tx = from.x + dx
                val ty = from.y + dy
                val gap = distance(to.x, to.y, tx, ty)
                if (gap < currentGap && gap < bestGap && tileIsClear(tx, ty, hazards) &&
                    pathHazardCost(from.x, from.y, tx, ty, hazards) == 0
                ) {
                    best = Point(tx, ty)
                    bestGap = gap
                }
            }
        }
        return best
    }

    fun clampTo(x: Int, y: Int, anchor: Point?, radius: Double): Point {
        if (anchor == null) return Point(x, y)
        val dx = (x - anchor.x).toDouble()
        val dy = (y - anchor.y).toDouble()
        val length = hypot(dx, dy)
        if (length <= radius) return Point(x, y)
        val scale = radius / length
        return Point(floor(anchor.x + dx * scale).toInt(), floor(anchor.y + dy * scale).toInt())
    }

    fun ring(radius: Int): List<Point> = rings.getOrPut(radius) {
        (0 until ORBIT_SLOTS).map { i ->
            val angle = i.toDouble() / ORBIT_SLOTS * 2 * Math.PI
            Point((radius * cos(angle)).roundToInt(), (radius * sin(angle)).roundToInt())
        }
    }

    fun ringDelta(from: Int, to: Int): Pair<Int, Int> {
        val diff = Math.floorMod(to - from, ORBIT_SLOTS)
        return if (diff <= ORBIT_SLOTS / 2) diff to 1 else (ORBIT_SLOTS - diff) to -1
    }

    fun wrapSlot(slot: Int): Int = Math.floorMod(slot - 1, ORBIT_SLOTS) + 1
}
