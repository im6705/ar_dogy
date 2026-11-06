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

    // Original metre scale is preserved. The supplied dog's evaluated Walk envelope needs 0.33 m;
    // glTF accessor bounds can describe its smaller seated bind mesh. Keep the larger measured disk.
    private val margin = dog.boundingBox.let {
        max(0.33f, hypot(abs(it.center[0]) + it.halfExtent[0], abs(it.center[2]) + it.halfExtent[2])) + 0.12f
    }
    private val idle = clipIndex("Idle")
    private val walk = clipIndex("Walk")
    private var target: GroundPoint? = null
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

    fun canPlace(): Boolean = area().contains(GroundPoint(0f, 0f))

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

        val area = area()
        val current = GroundPoint(dog.position.x, dog.position.z)
        if (!area.contains(current)) {
            target = null
            applyAnimation(idle, dt)
            return State.NEEDS_SPACE
        }

        if (target?.let { !area.contains(it) } == true) target = null
        if (target == null) {
            restSeconds -= dt
            if (restSeconds <= 0f) {
                target = area.chooseTarget(current, random)
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
        val next = GroundPoint(current.x + dx / distance * step, current.z + dz / distance * step)
        // Both endpoints lie in the inset convex polygon; re-check every actual movement step.
        if (!area.contains(next)) {
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

    private fun polygon(): List<GroundPoint> {
        val vertices = plane.polygon.duplicate().apply { rewind() }
        val worldToAnchor = anchor.pose.inverse()
        val planeToWorld = plane.centerPose
        val points = ArrayList<GroundPoint>(vertices.remaining() / 2)
        while (vertices.remaining() >= 2) {
            val point = worldToAnchor.transformPoint(
                planeToWorld.transformPoint(floatArrayOf(vertices.get(), 0f, vertices.get()))
            )
            points += GroundPoint(point[0], point[2])
        }
        return points
    }

    private fun area() = WanderArea(polygon(), margin, MAX_ANCHOR_DISTANCE)

    private fun wrapDegrees(angle: Float) = ((angle + 180f) % 360f + 360f) % 360f - 180f

    companion object {
        private const val WALK_METRES_PER_SECOND = 0.16f
        private const val MAX_ANCHOR_DISTANCE = 2f
        private const val BLEND_DURATION = 0.22f
    }
}
