package com.reeltrak.app.services

import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import com.reeltrak.app.BuildConfig
import com.reeltrak.app.core.ui.theme.ReFocusColors
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.reeltrak.app.core.ui.theme.GenesisTheme
import com.reeltrak.app.services.overlay.OverlayLifecycleState
import com.reeltrak.app.services.overlay.OverlayAttachmentRetryPolicy
import com.reeltrak.app.services.overlay.OverlayState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class FloatingCounterManager(
    private val context: Context,
    private val stateFlow: StateFlow<OverlayState>
) : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var composeView: ComposeView? = null
    
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store
    
    @Volatile
    private var isDestroyed = false

    private val attachmentPolicy = OverlayAttachmentRetryPolicy()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var attachRequestPending = false
    private var pendingAttachRunnable: Runnable? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateCollectorJob: Job? = null
    private var windowOffsetCollectorJob: Job? = null

    private val windowOffsetFlow = MutableStateFlow<Pair<Int, Int>?>(null)
    private var snapAnimator: ValueAnimator? = null
    val snapStateFlow = MutableStateFlow("CENTER")
    private var accumulatedInwardDrag = 0f

    init {
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    private data class ScreenDimensions(val width: Int, val height: Int)

    private fun getScreenDimensions(): ScreenDimensions {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            ScreenDimensions(bounds.width(), bounds.height())
        } else {
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            val point = android.graphics.Point()
            @Suppress("DEPRECATION")
            display.getSize(point)
            ScreenDimensions(point.x, point.y)
        }
    }

    private fun getSafeTopInset(density: Float, extraPadding: Int = (-4 * density).toInt()): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = windowManager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()
            )
            val top = insets.top
            if (top > 0) top + extraPadding else (24 * density).toInt() + extraPadding
        } else {
            val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resourceId > 0) {
                context.resources.getDimensionPixelSize(resourceId) + extraPadding
            } else {
                (24 * density).toInt() + extraPadding
            }
        }
    }

    private fun saveWindowPosition() {
        val view = composeView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        
        val density = context.resources.displayMetrics.density
        val extraPadding = (-4 * density).toInt()
        val screenDimensions = getScreenDimensions()
        val safeTopInset = getSafeTopInset(density, extraPadding)
        val maxX = screenDimensions.width - view.width
        
        val currentX = params.x
        
        val leftX = -(view.width * 0.35f).toInt()
        val centerX = maxX / 2
        val rightX = maxX + (view.width * 0.35f).toInt()
        
        val distLeft = Math.abs(currentX - leftX)
        val distCenter = Math.abs(currentX - centerX)
        val distRight = Math.abs(currentX - rightX)
        
        val targetX = when {
            distLeft <= distCenter && distLeft <= distRight -> leftX
            distRight <= distCenter && distRight <= distLeft -> rightX
            else -> centerX
        }
        
        val targetSnap = when (targetX) {
            leftX -> "LEFT"
            rightX -> "RIGHT"
            else -> "CENTER"
        }
        
        snapStateFlow.value = targetSnap
        
        val currentY = params.y
        val screenHeight = screenDimensions.height
        val maxYVal = screenHeight - view.height - (16 * density).toInt()
        val clampedY = currentY.coerceIn(safeTopInset, Math.max(safeTopInset, maxYVal))
        
        animateToPosition(targetX, clampedY)
        
        val prefs = context.getSharedPreferences("overlay_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("overlay_snap", targetSnap)
            .putInt("overlay_x", targetX)
            .putInt("overlay_y", clampedY)
            .putInt("overlay_last_view_width", view.width)
            .apply()
    }

    private fun animateToPosition(targetX: Int, targetY: Int) {
        val view = composeView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        
        snapAnimator?.cancel()
        
        val startX = params.x
        val startY = params.y
        
        if (startX == targetX && startY == targetY) return
        
        val pvhX = PropertyValuesHolder.ofInt("x", startX, targetX)
        val pvhY = PropertyValuesHolder.ofInt("y", startY, targetY)
        
        snapAnimator = ValueAnimator.ofPropertyValuesHolder(pvhX, pvhY).apply {
            duration = 350
            interpolator = OvershootInterpolator(0.9f)
            addUpdateListener { animator ->
                windowOffsetFlow.value = Pair(
                    animator.getAnimatedValue("x") as Int,
                    animator.getAnimatedValue("y") as Int
                )
            }
            start()
        }
    }

    @Synchronized
    fun attach() {
        Log.d("OverlayDebug", "[FloatingCounterManager] attach() called.")
        if (isDestroyed || attachRequestPending || attachmentPolicy.state == OverlayAttachmentRetryPolicy.State.ATTACHING) return
        if (attachmentPolicy.state == OverlayAttachmentRetryPolicy.State.ATTACHED && composeView?.isAttachedToWindow == true) return

        attachRequestPending = true
        val attachRunnable = Runnable {
            synchronized(this) {
                attachRequestPending = false
                pendingAttachRunnable = null
            }
            if (isDestroyed) return@Runnable
            try {
                if (attachmentPolicy.state == OverlayAttachmentRetryPolicy.State.ATTACHED) {
                    if (composeView?.isAttachedToWindow == true) return@Runnable
                    releaseViewAfterFailedAttachment()
                    attachmentPolicy.windowDetached()
                }
                if (!attachmentPolicy.beginAttempt(SystemClock.elapsedRealtime())) return@Runnable
                
                composeView = ComposeView(context).apply {
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                    setViewTreeLifecycleOwner(this@FloatingCounterManager)
                    setViewTreeSavedStateRegistryOwner(this@FloatingCounterManager)
                    setViewTreeViewModelStoreOwner(this@FloatingCounterManager)
                    
                    setContent {
                        val state by stateFlow.collectAsState()
                        val snapState by snapStateFlow.collectAsState()

                        val isCounterDark = when (state.counterThemeMode) {
                            com.reeltrak.app.domain.models.ThemeMode.LIGHT -> false
                            com.reeltrak.app.domain.models.ThemeMode.DARK -> true
                        }

                        GenesisTheme(darkTheme = isCounterDark) {
                            Surface(color = Color.Transparent) {
                                FloatingIcon(
                                    state = state,
                                    snapState = snapState,
                                    onDrag = { dx, dy ->
                                        snapAnimator?.cancel()
                                        val view = composeView ?: return@FloatingIcon
                                        val params = view.layoutParams as? WindowManager.LayoutParams ?: return@FloatingIcon
                                        val currentSnap = snapStateFlow.value
                                        
                                        if (currentSnap == "LEFT" || currentSnap == "RIGHT") {
                                            val inwardDelta = if (currentSnap == "LEFT") dx else -dx
                                            accumulatedInwardDrag += inwardDelta
                                            if (accumulatedInwardDrag < 0f) {
                                                accumulatedInwardDrag = 0f
                                            }
                                            
                                            val density = context.resources.displayMetrics.density
                                            val detachThreshold = 48f * density
                                            
                                            if (accumulatedInwardDrag >= detachThreshold) {
                                                snapStateFlow.value = "CENTER"
                                                accumulatedInwardDrag = 0f
                                                val newX = if (currentSnap == "LEFT") {
                                                    -(view.width * 0.35f).toInt() + detachThreshold.toInt()
                                                } else {
                                                    val screenWidth = getScreenDimensions().width
                                                    (screenWidth - (view.width * 0.65f).toInt()) - detachThreshold.toInt()
                                                }
                                                windowOffsetFlow.value = Pair(newX, params.y + dy.toInt())
                                            } else {
                                                windowOffsetFlow.value = Pair(params.x, params.y + dy.toInt())
                                            }
                                        } else {
                                            windowOffsetFlow.value = Pair(
                                                params.x + dx.toInt(),
                                                params.y + dy.toInt()
                                            )
                                        }
                                    },
                                    onDragEnd = {
                                        accumulatedInwardDrag = 0f
                                        saveWindowPosition()
                                    }
                                )
                            }
                        }
                    }
                }
                
                val layoutFlag = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                val density = context.resources.displayMetrics.density
                
                val prefs = context.getSharedPreferences("overlay_prefs", Context.MODE_PRIVATE)
                val savedSnap = prefs.getString("overlay_snap", "CENTER") ?: "CENTER"
                val savedX = if (prefs.contains("overlay_x")) prefs.getInt("overlay_x", 0) else null
                val savedY = if (prefs.contains("overlay_y")) prefs.getInt("overlay_y", 0) else null
                
                snapStateFlow.value = savedSnap
                
                val screenDimensions = getScreenDimensions()
                val screenWidth = screenDimensions.width
                val screenHeight = screenDimensions.height

                // Initial position fallback logic
                val estimatedWidth = 75 * density
                val initX = savedX ?: when (savedSnap) {
                    "LEFT" -> -(estimatedWidth * 0.35f).toInt()
                    "RIGHT" -> (screenWidth - estimatedWidth * 0.65f).toInt()
                    else -> ((screenWidth - estimatedWidth) / 2).toInt()
                }
                
                val safeTopInset = getSafeTopInset(density, (-4 * density).toInt())
                val initY = savedY ?: safeTopInset

                // Clamp to screen bounds if screen size changed
                val finalX = if (savedX != null) {
                    val lastViewWidth = prefs.getInt("overlay_last_view_width", estimatedWidth.toInt())
                    // Simple clamping to ensure it's not totally off-screen
                    initX.coerceIn(-(lastViewWidth * 0.8f).toInt(), screenWidth - (lastViewWidth * 0.2f).toInt())
                } else {
                    initX
                }
                
                val finalY = if (savedY != null) {
                    val estimatedHeight = 50 * density
                    initY.coerceIn(0, screenHeight - estimatedHeight.toInt())
                } else {
                    initY
                }
                
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutFlag,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = finalX
                    y = finalY
                }
                
                windowManager.addView(composeView, params)
                
                // Binary Visibility Controller & Samsung Display Synchronizer:
                // Instantly sync View visibility with state, and force a ViewRootImpl traversal
                // (invalidate) when state changes while visible.
                // On Samsung One UI, non-focused accessibility overlay windows have their display list
                // update/VSYNC throttled by the compositor if no touch events or View traversals occur.
                stateCollectorJob?.cancel()
                stateCollectorJob = scope.launch {
                    var lastRenderedCount: Int? = null
                    var lastRenderedThemeMode: com.reeltrak.app.domain.models.ThemeMode? = null
                    var lastRenderedUpdateRequired: Boolean? = null
                    var lastRenderedVisibility: Boolean? = null

                    stateFlow.collect { state ->
                        val isVisible = state.lifecycleState == OverlayLifecycleState.VISIBLE
                        composeView?.let { view ->
                            val visibilityChanged = (lastRenderedVisibility != isVisible)
                            if (visibilityChanged) {
                                view.visibility = if (isVisible) View.VISIBLE else View.GONE
                                lastRenderedVisibility = isVisible
                            }

                            if (isVisible) {
                                val countChanged = lastRenderedCount != null && lastRenderedCount != state.count
                                val themeChanged = lastRenderedThemeMode != null && lastRenderedThemeMode != state.counterThemeMode
                                val updateChanged = lastRenderedUpdateRequired != null && lastRenderedUpdateRequired != state.isUpdateRequired

                                if (countChanged || themeChanged || updateChanged) {
                                    val digitCountChanged = lastRenderedCount != null &&
                                        (lastRenderedCount.toString().length != state.count.toString().length)

                                    if (BuildConfig.DEBUG) {
                                        Log.d(
                                            "COUNTER_DIAG",
                                            "[POINT_4_UI_CONSUMED] State consumed: count=${state.count} (prev=$lastRenderedCount), theme=${state.counterThemeMode}, updateRequired=${state.isUpdateRequired}, invalidate=true, requestLayout=$digitCountChanged"
                                        )
                                    }
                                    // Always invalidate so ViewRootImpl performs performDraw() on Samsung One UI
                                    view.invalidate()

                                    // Only request layout pass if dimensions could genuinely change
                                    // (e.g. digit count expanded 9->10, or theme/update state changed)
                                    if (digitCountChanged || themeChanged || updateChanged) {
                                        view.requestLayout()
                                    }
                                } else if (lastRenderedCount == null && BuildConfig.DEBUG) {
                                    Log.d(
                                        "COUNTER_DIAG",
                                        "[POINT_4_UI_CONSUMED] Initial state consumed: count=${state.count}, theme=${state.counterThemeMode}"
                                    )
                                }
                            }
                        }
                        lastRenderedCount = state.count
                        lastRenderedThemeMode = state.counterThemeMode
                        lastRenderedUpdateRequired = state.isUpdateRequired
                    }
                }
                
                windowOffsetCollectorJob?.cancel()
                windowOffsetCollectorJob = scope.launch {
                    windowOffsetFlow.collect { offset ->
                        if (offset != null) {
                            val view = composeView ?: return@collect
                            val lp = view.layoutParams as? WindowManager.LayoutParams ?: return@collect
                            lp.x = offset.first
                            lp.y = offset.second
                            try {
                                windowManager.updateViewLayout(view, lp)
                            } catch (e: Exception) { }
                        }
                    }
                }
                
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
                attachmentPolicy.attachmentSucceeded()
                
            } catch (e: Exception) {
                Log.e("OverlayDebug", "[FloatingCounterManager] Overlay attachment failed; a later eligible event may retry: ${e.message}", e)
                attachmentPolicy.attachmentFailed(SystemClock.elapsedRealtime())
                releaseViewAfterFailedAttachment()
            }
        }
        pendingAttachRunnable = attachRunnable
        mainHandler.post(attachRunnable)
    }

    /** Reopens the bounded retry window when Android reconnects the accessibility service. */
    fun allowRetryAfterLifecycle() {
        attachmentPolicy.allowLifecycleRetry()
        attach()
    }

    private fun releaseViewAfterFailedAttachment() {
        stateCollectorJob?.cancel()
        stateCollectorJob = null
        windowOffsetCollectorJob?.cancel()
        windowOffsetCollectorJob = null
        composeView?.let { view ->
            try {
                if (view.isAttachedToWindow) {
                    view.visibility = View.GONE
                    windowManager.removeView(view)
                }
            } catch (cleanupFailure: Exception) {
                Log.e("OverlayDebug", "Failed to remove partially attached overlay view", cleanupFailure)
            } finally {
                composeView = null
            }
        }
    }
    
    fun detachForLifecycle() {
        if (isDestroyed) return
        detach()
    }

    @Synchronized
    private fun detach() {
        Log.d("OverlayDebug", "[FloatingCounterManager] detach() called.")
        pendingAttachRunnable?.let(mainHandler::removeCallbacks)
        pendingAttachRunnable = null
        attachRequestPending = false
        attachmentPolicy.detached()
        stateCollectorJob?.cancel()
        stateCollectorJob = null
        windowOffsetCollectorJob?.cancel()
        windowOffsetCollectorJob = null
        snapAnimator?.cancel()
        snapAnimator = null
        composeView?.let { view ->
            try {
                // Safety: Force GONE before removal
                view.visibility = View.GONE
                if (view.isAttachedToWindow) windowManager.removeView(view)
            } catch (e: Exception) {
                Log.e("OverlayDebug", "[FloatingCounterManager] Error detaching counter: ${e.message}", e)
            } finally {
                composeView = null
            }
        }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }
    
    fun destroy() {
        isDestroyed = true
        attachmentPolicy.destroy()
        detach()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        scope.cancel()
    }
}

@Composable
fun FloatingIcon(
    state: OverlayState,
    snapState: String,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit
) {
    if (BuildConfig.DEBUG) {
        Log.d(
            "COUNTER_DIAG",
            "[POINT_5_RECOMPOSE] FloatingIcon recomposed with count=${state.count}, snapState=$snapState, thread=${Thread.currentThread().name}"
        )
    }

    val context = LocalContext.current
    val currentReels = state.count
    val isLimitSet = state.dailyGoal > 0
    val dailyGoal = if (isLimitSet) state.dailyGoal else 1
    
    val progress = if (isLimitSet) currentReels.toFloat() / dailyGoal.toFloat() else 0f
    val isOverLimit = if (isLimitSet) progress >= 1f else false
    
    val startColor = ReFocusColors.counterSurface
    val targetWarning = ReFocusColors.counterSurfaceOverLimit
    
    val cappedProgress = progress.coerceIn(0f, 1f)
    
    val backgroundColor = when {
        state.isUpdateRequired -> ReFocusColors.errorSoft
        isOverLimit -> ReFocusColors.counterSurfaceOverLimit
        progress > 0.5f -> lerp(startColor, targetWarning, (cappedProgress - 0.5f) * 2f)
        else -> startColor
    }
    
    val containerColor = backgroundColor.copy(alpha = 1.0f) // Forced 100% opacity
    val borderColor = when {
        state.isUpdateRequired -> ReFocusColors.error.copy(alpha = 0.8f)
        isOverLimit -> ReFocusColors.counterBorderOverLimit
        else -> ReFocusColors.counterBorder
    }
    val contentColor = when {
        state.isUpdateRequired -> ReFocusColors.error
        isOverLimit -> ReFocusColors.warningText
        else -> ReFocusColors.counterTextPrimary
    }
    val labelColor = when {
        state.isUpdateRequired -> ReFocusColors.error
        isOverLimit -> ReFocusColors.warningText
        else -> ReFocusColors.counterTextSecondary
    }

    val contentOffset by androidx.compose.animation.core.animateDpAsState(
        targetValue = when (snapState) {
            "LEFT" -> 10.dp
            "RIGHT" -> (-10).dp
            else -> 0.dp
        },
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 300, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "content_shift"
    )

    val reelsLabelAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (snapState == "CENTER") 0.7f else 0f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 300),
        label = "label_alpha"
    )

    val yOffset by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (snapState == "CENTER") 0.dp else (-5).dp,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 300),
        label = "y_offset"
    )
    
    Card(
        modifier = Modifier
            .padding(12.dp)
            .drawWithContent {
                if (BuildConfig.DEBUG) {
                    Log.d(
                        "COUNTER_DIAG",
                        "[POINT_5_DRAW] FloatingIcon drawWithContent executed for count=${state.count}"
                    )
                }
                drawContent()
            }
            .pointerInput(Unit) {
                val touchSlop = 8.dp.toPx()
                val tapTimeout = 300L
                
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown()
                        if (BuildConfig.DEBUG) {
                            Log.d(
                                "COUNTER_DIAG",
                                "[POINT_6_TOUCH_EVENT] Touch down detected at pos=${down.position}, current count=${state.count}"
                            )
                        }
                        val startTime = System.currentTimeMillis()
                        val startPos = down.position
                        var isDrag = false
                        var clickCancelled = false
                        
                        do {
                            val event = awaitPointerEvent()
                            val pointerChange = event.changes.firstOrNull() ?: break
                            
                            if (pointerChange.pressed) {
                                val currentPos = pointerChange.position
                                val dist = (currentPos - startPos).getDistance()
                                if (dist > touchSlop) {
                                    isDrag = true
                                    clickCancelled = true
                                    val dragAmount = currentPos - pointerChange.previousPosition
                                    if (BuildConfig.DEBUG) {
                                        Log.d(
                                            "COUNTER_DIAG",
                                            "[POINT_6_DRAG_EVENT] Drag event detected, dragAmount=($dragAmount), current count=${state.count}"
                                        )
                                    }
                                    onDrag(dragAmount.x, dragAmount.y)
                                    pointerChange.consume()
                                }
                                if (System.currentTimeMillis() - startTime > tapTimeout) {
                                    clickCancelled = true
                                }
                            } else {
                                if (!clickCancelled && !isDrag) {
                                    val duration = System.currentTimeMillis() - startTime
                                    val finalPos = pointerChange.position
                                    val finalDist = (finalPos - startPos).getDistance()
                                    if (duration < tapTimeout && finalDist < touchSlop) {
                                        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        if (intent != null) {
                                            context.startActivity(intent)
                                        }
                                    }
                                }
                                if (isDrag) {
                                    onDragEnd()
                                }
                                break
                            }
                        } while (true)
                    }
                }
            },
        shape = CircleShape,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 7.dp)
    ) {
        Column(
            modifier = Modifier
                .offset(x = contentOffset, y = yOffset)
                .padding(horizontal = 18.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (state.isUpdateRequired) {
                Text(
                    text = "RE:FOCUS", 
                    style = MaterialTheme.typography.labelSmall, 
                    color = contentColor.copy(alpha = reelsLabelAlpha), 
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.0.sp,
                    fontSize = 8.sp
                )
                Text(
                    text = "UPDATE", 
                    style = MaterialTheme.typography.titleMedium, 
                    fontSize = 14.sp,
                    color = contentColor, 
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.5.sp
                )
            } else {
                Text(
                    text = "REELS", 
                    style = MaterialTheme.typography.labelSmall, 
                    color = labelColor.copy(alpha = reelsLabelAlpha), 
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.0.sp,
                    fontSize = 8.sp
                )
                Text(
                    text = "${state.count}", 
                    style = MaterialTheme.typography.titleLarge, 
                    fontSize = 22.sp,
                    color = contentColor, 
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}
