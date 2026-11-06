package com.im6705.ardogy

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.random.Random

internal data class GroundPoint(val x: Float, val z: Float)

/** The convex AR plane, inset by the dog's footprint, in placement-anchor coordinates. */
internal class WanderArea(
    private val polygon: List<GroundPoint>,
    private val margin: Float,
    private val maxAnchorDistance: Float,
) {
    private val area = polygon.indices.sumOf { i ->
        val a = polygon[i]
        val b = polygon[(i + 1) % polygon.size]
        a.x.toDouble() * b.z - b.x.toDouble() * a.z
    }
    private val winding = if (area > 0.0) 1.0 else -1.0

    fun contains(point: GroundPoint): Boolean {
        if (polygon.size < 3 || !area.isFinite() || abs(area) < EPSILON ||
            !point.x.isFinite() || !point.z.isFinite() ||
            hypot(point.x, point.z) > maxAnchorDistance + EPSILON
        ) return false
        return polygon.indices.all { i ->
            distanceToInsetEdge(point, polygon[i], polygon[(i + 1) % polygon.size]) >= -EPSILON
        }
    }

    fun chooseTarget(current: GroundPoint, random: Random): GroundPoint? {
        if (!contains(current)) return null
        val inset = insetPolygon()
        if (inset.isEmpty()) return null
        val minX = inset.minOf { it.x }.coerceAtLeast(-maxAnchorDistance)
        val maxX = inset.maxOf { it.x }.coerceAtMost(maxAnchorDistance)
        val minZ = inset.minOf { it.z }.coerceAtLeast(-maxAnchorDistance)
        val maxZ = inset.maxOf { it.z }.coerceAtMost(maxAnchorDistance)
        if (maxX < minX || maxZ < minZ) return null

        // Sampling the original plane wastes almost every attempt on its unsafe border when
        // a floor is only slightly wider than the dog. Sample the usable region instead.
        repeat(48) {
            val candidate = GroundPoint(
                minX + random.nextFloat() * (maxX - minX),
                minZ + random.nextFloat() * (maxZ - minZ),
            )
            if (isTarget(candidate, current)) return candidate
        }

        // Bad luck must not report NEEDS_SPACE. The farthest point of a convex polygon clipped
        // by a disk is a polygon vertex, an edge/circle intersection, or on the farthest arc.
        val candidates = inset.toMutableList()
        for (i in inset.indices) {
            candidates += circleIntersections(inset[i], inset[(i + 1) % inset.size])
        }
        val distanceFromAnchor = hypot(current.x, current.z)
        if (distanceFromAnchor > EPSILON) {
            candidates += GroundPoint(
                -current.x / distanceFromAnchor * maxAnchorDistance,
                -current.z / distanceFromAnchor * maxAnchorDistance,
            )
        } else {
            candidates += listOf(
                GroundPoint(maxAnchorDistance, 0f), GroundPoint(-maxAnchorDistance, 0f),
                GroundPoint(0f, maxAnchorDistance), GroundPoint(0f, -maxAnchorDistance),
            )
        }
        return candidates.filter { isTarget(it, current) }
            .maxByOrNull { hypot(it.x - current.x, it.z - current.z) }
    }

    private fun isTarget(point: GroundPoint, current: GroundPoint) =
        hypot(point.x - current.x, point.z - current.z) >= MIN_WALK_DISTANCE && contains(point)

    private fun distanceToInsetEdge(point: GroundPoint, a: GroundPoint, b: GroundPoint): Double {
        val dx = b.x.toDouble() - a.x
        val dz = b.z.toDouble() - a.z
        val length = hypot(dx, dz)
        if (length < EPSILON) return 0.0 // Repeated ARCore vertices have no boundary normal.
        return winding * (dx * (point.z - a.z) - dz * (point.x - a.x)) / length - margin
    }

    private fun insetPolygon(): List<GroundPoint> {
        var result = polygon
        for (i in polygon.indices) {
            if (result.isEmpty()) break
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            val clipped = mutableListOf<GroundPoint>()
            var previous = result.last()
            var previousDistance = distanceToInsetEdge(previous, a, b) + EPSILON / 2
            for (point in result) {
                val distance = distanceToInsetEdge(point, a, b) + EPSILON / 2
                if ((distance >= 0.0) != (previousDistance >= 0.0)) {
                    val t = previousDistance / (previousDistance - distance)
                    clipped += GroundPoint(
                        (previous.x + t * (point.x - previous.x)).toFloat(),
                        (previous.z + t * (point.z - previous.z)).toFloat(),
                    )
                }
                if (distance >= 0.0) clipped += point
                previous = point
                previousDistance = distance
            }
            result = clipped
        }
        return result
    }

    private fun circleIntersections(a: GroundPoint, b: GroundPoint): List<GroundPoint> {
        val dx = b.x.toDouble() - a.x
        val dz = b.z.toDouble() - a.z
        val lengthSquared = dx * dx + dz * dz
        if (lengthSquared < EPSILON * EPSILON) return emptyList()
        val projection = a.x * dx + a.z * dz
        val radiusSquared = maxAnchorDistance.toDouble() * maxAnchorDistance
        val discriminant = projection * projection - lengthSquared *
            (a.x.toDouble() * a.x + a.z.toDouble() * a.z - radiusSquared)
        if (discriminant < 0.0) return emptyList()
        val root = sqrt(discriminant)
        return listOf((-projection - root) / lengthSquared, (-projection + root) / lengthSquared)
            .filter { it in 0.0..1.0 }
            .map { GroundPoint((a.x + it * dx).toFloat(), (a.z + it * dz).toFloat()) }
    }

    companion object {
        private const val MIN_WALK_DISTANCE = 0.25f
        // Ten micrometres only absorbs float roundoff at inset/circle intersections.
        private const val EPSILON = 0.00001
    }
}
