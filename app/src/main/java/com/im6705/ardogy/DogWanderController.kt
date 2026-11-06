package com.im6705.ardogy

import com.google.ar.core.Anchor
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.node.ModelNode
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Movement uses the current ARCore convex polygon, expressed in the placement anchor's space. */
internal class DogWanderController(
    private val dog: ModelNode,
    private val anchor: Anchor,
    private var plane: Plane,
    private val random: Random = Random.Default,
) {
    enum class State { WALKING, RESTING, PAUSED, TRACKING_LOST, NEEDS_SPACE, PLANE_LOST }

    private data class Point(val x: Float, val z: Float)

    // Original metre scale is preserved. The supplied dog's evaluated Walk envelope needs 0.33 m;
    // glTF accessor bounds can describe its smaller seated bind mesh. Keep the larger measured disk.
    private val margin = dog.boundingBox.let {
        max(0.33f, hypot(abs(it.center[0]) + it.halfExtent[0], abs(it.center[2]) + it.halfExtent[2])) + 0.12f
    }
    private val idle = clipIndex("Idle")
    private val walk = clipIndex("Walk")
    private var target: Point? = null
    private var restSeconds = 1.2f
    private var yaw = 0f
    private var clip = idle
    private var clipSeconds = 0f
    private var previousClip = idle
    private var previousClipSeconds = 0f
    private var blendSeconds = BLEND_DURATION
    private var needsSpace = false

    init {
        require(margin.isFinite() && margin in 0.15f..1.5f) { "Dog bounds are not in the expected metre scale" }
        require(dog.skinCount > 0) { "Dog asset has no skeleton skin" }
        applyAnimation(idle, 0f)
    }

    fun canPlace(): Boolean = contains(polygon(), Point(0f, 0f))

    fun update(dt: Float, cameraTracking: Boolean, userPaused: Boolean): State {
        plane.subsumedBy?.let { plane = it }
        if (plane.trackingState == TrackingState.STOPPED || anchor.trackingState == TrackingState.STOPPED) {
            return State.PLANE_LOST
        }
        if (!cameraTracking || plane.trackingState != TrackingState.TRACKING ||
            anchor.trackingState != TrackingState.TRACKING
        ) return State.TRACKING_LOST // Keep both the position and skin pose frozen.

        if (userPaused) {
            applyAnimation(idle, dt)
            return State.PAUSED
        }

        val polygon = polygon()
        val current = Point(dog.position.x, dog.position.z)
        if (!contains(polygon, current)) {
            target = null
            applyAnimation(idle, dt)
            return State.NEEDS_SPACE
        }

        if (target?.let { !contains(polygon, it) } == true) target = null
        if (target == null) {
            restSeconds -= dt
            if (restSeconds <= 0f) {
                target = chooseTarget(polygon, current)
                needsSpace = target == null
                restSeconds = if (target == null) 1f else random.nextFloat() * 2f + 1f
            }
            applyAnimation(idle, dt)
            return if (needsSpace) State.NEEDS_SPACE else State.RESTING
        }

        val destination = target!!
        val dx = destination.x - current.x
        val dz = destination.z - current.z
        val distance = hypot(dx, dz)
        if (distance < 0.025f) {
            target = null
            applyAnimation(idle, dt)
            return State.RESTING
        }
        val wantedYaw = atan2(dx, dz) * (180f / Math.PI.toFloat())
        val delta = wrapDegrees(wantedYaw - yaw)
        yaw = wrapDegrees(yaw + delta.coerceIn(-100f * dt, 100f * dt))
        dog.rotation = Rotation(0f, yaw, 0f)
        if (abs(delta) > 12f) {
            applyAnimation(idle, dt)
            return State.RESTING
        }

        val step = min(distance, WALK_METRES_PER_SECOND * dt)
        val next = Point(current.x + dx / distance * step, current.z + dz / distance * step)
        // Both endpoints lie in the inset convex polygon; re-check every actual movement step.
        if (!contains(polygon, next)) {
            target = null
            applyAnimation(idle, dt)
            return State.NEEDS_SPACE
        }
        dog.position = Position(next.x, 0f, next.z)
        applyAnimation(walk, dt)
        return State.WALKING
    }

    private fun clipIndex(name: String): Int = (0 until dog.animationCount)
        .firstOrNull { dog.animator.getAnimationName(it) == name }
        ?.also { require(dog.animator.getAnimationDuration(it) > 0f) { "$name animation is empty" } }
        ?: error("Dog asset is missing the $name animation")

    private fun applyAnimation(index: Int, dt: Float) {
        if (clip != index) {
            previousClip = clip
            previousClipSeconds = clipSeconds
            blendSeconds = 0f
            clip = index
            clipSeconds = 0f
        }
        clipSeconds = (clipSeconds + dt) % dog.animator.getAnimationDuration(index)
        dog.animator.applyAnimation(index, clipSeconds)
        if (blendSeconds < BLEND_DURATION) {
            blendSeconds = min(BLEND_DURATION, blendSeconds + dt)
            previousClipSeconds = (previousClipSeconds + dt) % dog.animator.getAnimationDuration(previousClip)
            dog.animator.applyCrossFade(previousClip, previousClipSeconds, blendSeconds / BLEND_DURATION)
        }
        dog.animator.updateBoneMatrices()
    }

    private fun polygon(): List<Point> {
        val vertices = plane.polygon.duplicate().apply { rewind() }
        val worldToAnchor = anchor.pose.inverse()
        val planeToWorld = plane.centerPose
        val points = ArrayList<Point>(vertices.remaining() / 2)
        while (vertices.remaining() >= 2) {
            val point = worldToAnchor.transformPoint(
                planeToWorld.transformPoint(floatArrayOf(vertices.get(), 0f, vertices.get()))
            )
            points += Point(point[0], point[2])
        }
        return points
    }

    private fun contains(polygon: List<Point>, point: Point): Boolean {
        if (polygon.size < 3 || hypot(point.x, point.z) > MAX_ANCHOR_DISTANCE) return false
        var area = 0f
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            area += a.x * b.z - b.x * a.z
        }
        if (abs(area) < 0.0001f) return false
        val sign = if (area > 0f) 1f else -1f
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            val edgeX = b.x - a.x
            val edgeZ = b.z - a.z
            val length = hypot(edgeX, edgeZ)
            if (length < 0.0001f) continue
            val inwardDistance = sign * (edgeX * (point.z - a.z) - edgeZ * (point.x - a.x)) / length
            if (inwardDistance < margin) return false
        }
        return true
    }

    private fun chooseTarget(polygon: List<Point>, current: Point): Point? {
        if (polygon.size < 3) return null
        val minX = max(-MAX_ANCHOR_DISTANCE, polygon.minOf { it.x })
        val maxX = min(MAX_ANCHOR_DISTANCE, polygon.maxOf { it.x })
        val minZ = max(-MAX_ANCHOR_DISTANCE, polygon.minOf { it.z })
        val maxZ = min(MAX_ANCHOR_DISTANCE, polygon.maxOf { it.z })
        if (maxX <= minX || maxZ <= minZ) return null
        repeat(48) {
            val candidate = Point(minX + random.nextFloat() * (maxX - minX), minZ + random.nextFloat() * (maxZ - minZ))
            if (hypot(candidate.x - current.x, candidate.z - current.z) >= 0.25f && contains(polygon, candidate)) {
                return candidate
            }
        }
        return null
    }

    private fun wrapDegrees(angle: Float) = ((angle + 180f) % 360f + 360f) % 360f - 180f

    companion object {
        private const val WALK_METRES_PER_SECOND = 0.16f
        private const val MAX_ANCHOR_DISTANCE = 2f
        private const val BLEND_DURATION = 0.22f
    }
}
