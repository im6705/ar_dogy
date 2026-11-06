package com.im6705.ardogy

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WanderAreaTest {
    private val origin = GroundPoint(0f, 0f)
    private val margin = 0.33f + 0.12f
    private val centerOnlyRandom = object : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextFloat(): Float = 0.5f
    }

    @Test fun narrowWalkableFloorAlwaysHasATarget() {
        val area = WanderArea(rectangle(0.451f, 1.5f), margin, 2f)
        assertTrue(area.contains(origin))
        repeat(200) { seed -> assertSafeTarget(area, origin, Random(seed)) }
    }

    @Test fun unluckySamplingFallsBackInsteadOfReportingNoSpace() {
        val area = WanderArea(rectangle(0.451f, 1.5f), margin, 2f)
        assertSafeTarget(area, origin, centerOnlyRandom)
    }

    @Test fun footprintWidthCorridorHandlesFloatRoundoff() {
        val area = WanderArea(rectangle(0.45f, 1.5f), margin, 2f)
        assertTrue(area.contains(origin))
        assertSafeTarget(area, origin, centerOnlyRandom)
    }

    @Test fun bothPolygonWindingsAreSupported() {
        val area = WanderArea(rectangle(0.451f, 1.5f).reversed(), margin, 2f)
        assertSafeTarget(area, origin, centerOnlyRandom)
    }

    @Test fun rotatedNarrowFloorUsesFallbackSafely() {
        val angle = Math.PI / 4
        val polygon = rectangle(0.451f, 1.5f).map {
            GroundPoint((it.x * cos(angle) - it.z * sin(angle)).toFloat(),
                (it.x * sin(angle) + it.z * cos(angle)).toFloat())
        }
        val area = WanderArea(polygon, margin, 2f)
        assertSafeTarget(area, origin, centerOnlyRandom)
    }

    @Test fun fallbackRespectsAnchorDistanceWhenAllVerticesAreOutside() {
        val area = WanderArea(rectangle(5f, 5f), margin, 2f)
        assertSafeTarget(area, origin, centerOnlyRandom)
    }

    @Test fun fallbackFindsCircleIntersectionsInALongCorridor() {
        val area = WanderArea(rectangle(0.451f, 5f), margin, 2f)
        assertSafeTarget(area, origin, centerOnlyRandom)
    }

    @Test fun fallbackFindsFarthestArcFromAnOffCenterPosition() {
        val area = WanderArea(rectangle(5f, 5f), margin, 2f)
        val current = GroundPoint(1.9f, 0f)
        val target = area.chooseTarget(current, object : Random() {
            override fun nextBits(bitCount: Int): Int = 0
            private var x = true
            override fun nextFloat(): Float {
                x = !x
                return if (!x) 0.975f else 0.5f
            }
        })
        assertNotNull(target)
        assertTrue(target!!.x < -1.9f)
        assertTrue(area.contains(target))
    }

    @Test fun trulyInsufficientSpaceDoesNotProduceATarget() {
        val area = WanderArea(rectangle(0.5f, 0.5f), margin, 2f)
        assertTrue(area.contains(origin))
        assertNull(area.chooseTarget(origin, centerOnlyRandom))
    }

    @Test fun tooNarrowFloorRejectsPlacementAndMovement() {
        val area = WanderArea(rectangle(0.44f, 5f), margin, 2f)
        assertFalse(area.contains(origin))
        assertNull(area.chooseTarget(origin, Random(0)))
    }

    @Test fun boundariesAndAnchorRangeRemainEnforced() {
        val area = WanderArea(rectangle(3f, 3f), margin, 2f)
        assertTrue(area.contains(GroundPoint(1.99f, 0f)))
        assertFalse(area.contains(GroundPoint(2.01f, 0f)))
        val smallArea = WanderArea(rectangle(1f, 1f), margin, 2f)
        assertTrue(smallArea.contains(GroundPoint(0.54f, 0f)))
        assertFalse(smallArea.contains(GroundPoint(0.56f, 0f)))
    }

    @Test fun degenerateAndNonFiniteGeometryIsRejected() {
        for (polygon in listOf(emptyList(), listOf(origin, origin, origin),
            listOf(origin, GroundPoint(Float.NaN, 1f), GroundPoint(1f, 0f)))) {
            val area = WanderArea(polygon, margin, 2f)
            assertFalse(area.contains(origin))
            assertNull(area.chooseTarget(origin, Random(0)))
        }
        val area = WanderArea(rectangle(2f, 2f), margin, 2f)
        assertFalse(area.contains(GroundPoint(Float.NaN, 0f)))
        assertFalse(area.contains(GroundPoint(0f, Float.POSITIVE_INFINITY)))
    }

    @Test fun repeatedPolygonVerticesAreHarmless() {
        val polygon = rectangle(0.451f, 1.5f)
        val area = WanderArea(polygon + polygon.last(), margin, 2f)
        assertSafeTarget(area, origin, centerOnlyRandom)
    }

    private fun assertSafeTarget(area: WanderArea, current: GroundPoint, random: Random) {
        val target = area.chooseTarget(current, random)
        assertNotNull(target)
        assertTrue(area.contains(target!!))
        assertTrue(hypot(target.x - current.x, target.z - current.z) >= 0.25f)
        assertTrue(hypot(target.x, target.z) <= 2.00001f)
        // Every intermediate point on a straight walk must remain in the same safe convex area.
        for (step in 0..100) {
            val t = step / 100f
            assertTrue(area.contains(GroundPoint(current.x + (target.x - current.x) * t,
                current.z + (target.z - current.z) * t)))
        }
    }

    private fun rectangle(halfWidth: Float, halfLength: Float) = listOf(
        GroundPoint(-halfWidth, -halfLength), GroundPoint(halfWidth, -halfLength),
        GroundPoint(halfWidth, halfLength), GroundPoint(-halfWidth, halfLength),
    )
}
