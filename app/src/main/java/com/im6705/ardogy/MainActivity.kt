package com.im6705.ardogy

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.node.ModelNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The production entry point: permissions → ARCore → scan → place → wander. */
class MainActivity : ComponentActivity() {
    private lateinit var root: FrameLayout
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var action: Button
    private lateinit var pause: Button
    private lateinit var reset: Button
    private var scene: ARSceneView? = null
    private var sceneOwner: SceneOwner? = null
    private var dog: ModelNode? = null
    private var anchorNode: AnchorNode? = null
    private var wander: DogWanderController? = null
    private var loading: Job? = null
    private var userPaused = false
    private var resumed = false
    private var availabilityChecking = false
    private var installRequested = false
    private var permissionRequested = false
    private var assetFailed = false
    private var sessionFailed = false
    private var previousTimestamp = 0L
    private var lastUiKey = ""

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) prepareAr() else showPermissionState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        permissionRequested = savedInstanceState?.getBoolean("permission_requested") ?: false
        buildInterface()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        previousTimestamp = 0L
        if (hasCameraPermission()) {
            if (scene != null) {
                try {
                    sceneOwner?.registry?.currentState = Lifecycle.State.RESUMED
                } catch (error: Exception) {
                    showSessionFailure(error)
                }
            } else prepareAr()
        } else {
            // A user can revoke the permission in Settings while the app is in the background.
            disposeScene()
            if (!permissionRequested) requestCamera() else showPermissionState()
        }
    }

    override fun onPause() {
        resumed = false
        previousTimestamp = 0L
        sceneOwner?.registry?.currentState = Lifecycle.State.CREATED
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("permission_requested", permissionRequested)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        disposeScene()
        super.onDestroy()
    }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestCamera() {
        permissionRequested = true
        showState("permission", R.string.camera_title, R.string.camera_explanation)
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun showPermissionState() {
        showState("permission_denied", R.string.camera_title, R.string.camera_denied)
        showAction(R.string.allow_camera) {
            if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) requestCamera()
            else startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun prepareAr() {
        if (!resumed || isDestroyed || scene != null || availabilityChecking) return
        if (!hasCameraPermission()) {
            showPermissionState()
            return
        }
        availabilityChecking = true
        showState("checking", R.string.checking_title, R.string.checking_detail)
        ArCoreApk.getInstance().checkAvailabilityAsync(this) { availability ->
            runOnUiThread {
                availabilityChecking = false
                if (!resumed || isDestroyed || scene != null) return@runOnUiThread
                if (!availability.isSupported) {
                    showState("unsupported", R.string.unavailable_title,
                        if (availability == ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE) R.string.unsupported_detail else R.string.availability_unknown)
                    showAction(R.string.retry) { prepareAr() }
                    return@runOnUiThread
                }
                try {
                    when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                        ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                            installRequested = true
                            showState("install", R.string.install_title, R.string.install_detail)
                        }
                        ArCoreApk.InstallStatus.INSTALLED -> createScene()
                        else -> Unit
                    }
                } catch (error: Exception) {
                    showSessionFailure(error)
                }
            }
        }
    }

    private fun createScene() {
        showState("loading", R.string.loading_title, R.string.loading_detail)
        assetFailed = false
        sessionFailed = false
        val owner = SceneOwner()
        sceneOwner = owner
        try {
            val view = ARSceneView(
                context = this,
                sharedActivity = this,
                sharedLifecycle = owner.lifecycle,
                sessionConfiguration = { _, config ->
                    config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                    config.instantPlacementMode = Config.InstantPlacementMode.DISABLED
                    config.depthMode = Config.DepthMode.DISABLED
                    config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                },
                onSessionFailed = { showSessionFailure(it) },
                onSessionUpdated = { _, frame -> onArFrame(frame) },
            )
            // This Activity owns permission/install prompts. The view owns only its AR session.
            view.arCore.checkCameraPermission = false
            view.arCore.checkAvailability = false
            scene = view
            root.addView(view, 0, FrameLayout.LayoutParams(-1, -1))
            val taps = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    placeDog(e.x, e.y)
                    return true
                }
            })
            view.setOnTouchListener { _, event -> taps.onTouchEvent(event); true }
            owner.registry.currentState = Lifecycle.State.RESUMED
            loading = lifecycleScope.launch {
                try {
                    val instance = requireNotNull(view.modelLoader.loadModelInstance("models/dog.glb")) { "Cannot load models/dog.glb" }
                    if (scene !== view || isDestroyed) return@launch
                    val model = ModelNode(instance, autoAnimate = false).apply {
                        isPositionEditable = false
                        isRotationEditable = false
                        isScaleEditable = false
                        isTouchable = false
                    }
                    // Validate here, before the user can try placing a broken or unanimated asset.
                    require(model.skinCount > 0) { "Dog asset has no skeleton skin" }
                    val names = (0 until model.animationCount).associateBy { model.animator.getAnimationName(it) }
                    for (name in listOf("Idle", "Walk")) {
                        val index = requireNotNull(names[name]) { "Dog asset is missing $name" }
                        require(model.animator.getAnimationDuration(index) > 0f) { "$name animation is empty" }
                    }
                    dog = model
                    if (!sessionFailed) showState("scan", R.string.scan_title, R.string.scan_detail)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    android.util.Log.e(TAG, "Bundled dog asset failed", error)
                    assetFailed = true
                    showState("asset_error", R.string.asset_error_title, R.string.asset_error_detail)
                    showAction(R.string.retry) { restartAr() }
                }
            }
        } catch (error: Exception) {
            disposeScene()
            showSessionFailure(error)
        }
    }

    private fun placeDog(x: Float, y: Float) {
        val view = scene ?: return
        val model = dog ?: return
        if (anchorNode != null || assetFailed || sessionFailed) return
        val frame = view.frame ?: return
        if (frame.camera.trackingState != TrackingState.TRACKING) return
        val hit = frame.hitTest(x, y).firstOrNull {
            val plane = it.trackable as? Plane
            plane != null && plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                plane.trackingState == TrackingState.TRACKING && plane.isPoseInPolygon(it.hitPose)
        }
        if (hit == null) {
            showState("tap_miss", R.string.scan_title, R.string.tap_miss_detail)
            return
        }
        var pendingAnchor: AnchorNode? = null
        try {
            val anchor = hit.createAnchor()
            val node = AnchorNode(view.engine, anchor).apply {
                isPositionEditable = false
                isRotationEditable = false
                isScaleEditable = false
                isTouchable = false
            }
            pendingAnchor = node
            model.position = io.github.sceneview.math.Position(0f, 0f, 0f)
            model.rotation = io.github.sceneview.math.Rotation(0f, 0f, 0f)
            val controller = DogWanderController(model, anchor, hit.trackable as Plane)
            if (!controller.canPlace()) {
                node.destroy()
                pendingAnchor = null
                showState("too_small", R.string.space_title, R.string.placement_space_detail)
                return
            }
            node.addChildNode(model)
            view.addChildNode(node)
            anchorNode = node
            pendingAnchor = null
            wander = controller
            userPaused = false
            pause.isEnabled = true
            pause.setText(R.string.pause)
            reset.isEnabled = true
            showState("resting", R.string.resting_title, R.string.placed_detail)
        } catch (error: Exception) {
            pendingAnchor?.destroy()
            android.util.Log.e(TAG, "Placement failed", error)
            showState("placement_error", R.string.placement_error_title, R.string.placement_error_detail)
        }
    }

    private fun onArFrame(frame: Frame) {
        if (!resumed || sessionFailed || assetFailed) return
        val dt = if (previousTimestamp == 0L) 0f else ((frame.timestamp - previousTimestamp) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
        previousTimestamp = frame.timestamp
        val tracking = frame.camera.trackingState == TrackingState.TRACKING
        val controller = wander
        if (controller != null) {
            when (controller.update(dt, tracking, userPaused)) {
                DogWanderController.State.WALKING -> showState("walking", R.string.walking_title, R.string.placed_detail)
                DogWanderController.State.RESTING -> showState("resting", R.string.resting_title, R.string.placed_detail)
                DogWanderController.State.PAUSED -> showState("paused", R.string.paused_title, R.string.paused_detail)
                DogWanderController.State.TRACKING_LOST -> showTrackingFailure(frame)
                DogWanderController.State.NEEDS_SPACE -> showState("needs_space", R.string.space_title, R.string.movement_space_detail)
                DogWanderController.State.PLANE_LOST -> showState("plane_lost", R.string.plane_lost_title, R.string.plane_lost_detail)
            }
        } else if (dog != null) {
            if (!tracking) showTrackingFailure(frame)
            else if (lastUiKey !in setOf("too_small", "tap_miss", "placement_error")) {
                val hasFloor = scene?.session?.getAllTrackables(Plane::class.java)?.any {
                    it.type == Plane.Type.HORIZONTAL_UPWARD_FACING && it.trackingState == TrackingState.TRACKING && it.subsumedBy == null
                } == true
                showState(if (hasFloor) "tap" else "scan", if (hasFloor) R.string.tap_title else R.string.scan_title,
                    if (hasFloor) R.string.tap_detail else R.string.scan_detail)
            }
        }
    }

    private fun showTrackingFailure(frame: Frame) {
        val message = when (frame.camera.trackingFailureReason) {
            TrackingFailureReason.INSUFFICIENT_LIGHT -> R.string.tracking_dark
            TrackingFailureReason.EXCESSIVE_MOTION -> R.string.tracking_motion
            TrackingFailureReason.INSUFFICIENT_FEATURES -> R.string.tracking_features
            TrackingFailureReason.CAMERA_UNAVAILABLE -> R.string.tracking_camera
            else -> R.string.tracking_detail
        }
        showState("tracking_$message", R.string.tracking_title, message)
    }

    private fun showSessionFailure(error: Exception) {
        android.util.Log.e(TAG, "AR session failed", error)
        sessionFailed = true
        previousTimestamp = 0L
        val message = when (error) {
            is UnavailableDeviceNotCompatibleException -> R.string.unsupported_detail
            is UnavailableUserDeclinedInstallationException -> R.string.install_declined
            is UnavailableApkTooOldException -> R.string.ar_update_detail
            is UnavailableSdkTooOldException -> R.string.app_update_detail
            is CameraNotAvailableException -> R.string.camera_unavailable
            is SecurityException -> R.string.camera_denied
            else -> R.string.session_error_detail
        }
        showState("session_error_$message", R.string.unavailable_title, message)
        showAction(R.string.retry) { restartAr() }
    }

    private fun restartAr() {
        disposeScene()
        installRequested = false
        prepareAr()
    }

    private fun resetPlacement() {
        wander = null
        dog?.parent = null
        anchorNode?.let { node ->
            scene?.removeChildNode(node)
            node.destroy()
        }
        anchorNode = null
        previousTimestamp = 0L
        userPaused = false
        pause.isEnabled = false
        pause.setText(R.string.pause)
        reset.isEnabled = false
        if (!assetFailed && !sessionFailed) showState("scan", R.string.scan_title, R.string.scan_detail)
    }

    private fun disposeScene() {
        loading?.cancel()
        loading = null
        sceneOwner?.registry?.currentState = Lifecycle.State.CREATED
        resetPlacement()
        dog = null
        scene?.let { root.removeView(it) }
        // Destroy while native resources are still owned here, before Activity destruction.
        sceneOwner?.registry?.currentState = Lifecycle.State.DESTROYED
        sceneOwner = null
        scene = null
    }

    private fun showState(key: String, title: Int, body: Int) {
        if (lastUiKey == key) return
        lastUiKey = key
        status.setText(title)
        detail.setText(body)
        action.visibility = View.GONE
    }

    private fun showAction(label: Int, onClick: () -> Unit) {
        action.setText(label)
        action.visibility = View.VISIBLE
        action.setOnClickListener { onClick() }
    }

    private fun buildInterface() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(15, 24, 27)) }
        setContentView(root)
        val chrome = FrameLayout(this)
        root.addView(chrome, FrameLayout.LayoutParams(-1, -1))
        ViewCompat.setOnApplyWindowInsetsListener(chrome) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val title = TextView(this).apply {
            setText(R.string.app_name)
            textSize = 23f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setShadowLayer(5f, 0f, 2f, Color.BLACK)
        }
        chrome.addView(title, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(24), dp(20), dp(24), 0) })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(16))
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(0xED142527.toInt()) }
        }
        chrome.addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(16), 0, dp(16), dp(16)) })
        status = TextView(this).apply { textSize = 21f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        detail = TextView(this).apply { textSize = 15f; setTextColor(0xFFDCE8E6.toInt()); setLineSpacing(dp(3).toFloat(), 1f) }
        card.addView(status)
        card.addView(detail, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) })
        action = makeButton(R.string.retry).apply { visibility = View.GONE }
        card.addView(action, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12) })
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pause = makeButton(R.string.pause).apply {
            isEnabled = false
            setOnClickListener {
                userPaused = !userPaused
                setText(if (userPaused) R.string.resume else R.string.pause)
            }
        }
        reset = makeButton(R.string.reset).apply { isEnabled = false; setOnClickListener { resetPlacement() } }
        controls.addView(pause, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(8) })
        controls.addView(reset, LinearLayout.LayoutParams(0, dp(52), 1f))
        card.addView(controls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        card.addView(TextView(this).apply { setText(R.string.safety_note); textSize = 12f; setTextColor(0xFFABBDB9.toInt()) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        showState("initial", R.string.checking_title, R.string.checking_detail)
    }

    private fun makeButton(label: Int) = Button(this).apply {
        setText(label)
        textSize = 15f
        isAllCaps = false
        setTextColor(Color.rgb(16, 36, 32))
        backgroundTintList = android.content.res.ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(0xFF667B76.toInt(), 0xFFBCEBD5.toInt()),
        )
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private class SceneOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    companion object { private const val TAG = "ArDogy" }
}
