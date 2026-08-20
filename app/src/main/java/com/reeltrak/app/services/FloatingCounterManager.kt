package com.reeltrak.app.services

import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
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
import com.reeltrak.app.services.overlay.OverlayState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val windowOffsetFlow = MutableStateFlow<Pair<Int, Int>?>(null)
    private var snapAnimator: ValueAnimator? = null
    val snapStateFlow = MutableStateFlow("CENTER")
    private var accumulatedInwardDrag = 0f

    init {
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    private fun saveWindowPosition() {
        val view = composeView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        
        val density = context.resources.displayMetrics.density
        val extraPadding = (-4 * density).toInt() 
        
        var maxX = 0
        var safeTopInset = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val bounds = metrics.bounds
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()
            )
            safeTopInset = insets.top
            if (safeTopInset > 0) safeTopInset += extraPadding
            maxX = bounds.width() - view.width
        } else {
            val display = windowManager.defaultDisplay
            val point = android.graphics.Point()
            display.getSize(point)
            maxX = point.x - view.width
            val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resourceId > 0) {
                safeTopInset = context.resources.getDimensionPixelSize(resourceId) + extraPadding
            } else {
                safeTopInset = (24 * density).toInt()
            }
        }
        
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
        val screenHeight = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.height()
        } else {
            val p = android.graphics.Point(); windowManager.defaultDisplay.getSize(p); p.x
        }
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

    fun attach() {
        Log.d("OverlayDebug", "[FloatingCounterManager] attach() called.")
        if (composeView != null) return
        
        Handler(Looper.getMainLooper()).post {
            if (isDestroyed) return@post
            try {
                if (composeView != null) return@post
                
                composeView = ComposeView(context).apply {
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                    setViewTreeLifecycleOwner(this@FloatingCounterManager)
                    setViewTreeSavedStateRegistryOwner(this@FloatingCounterManager)
                    setViewTreeViewModelStoreOwner(this@FloatingCounterManager)
                    
                    setContent {
                        GenesisTheme {
                            Surface(color = Color.Transparent) {
                                val state by stateFlow.collectAsStateWithLifecycle()
                                val snapState by snapStateFlow.collectAsStateWithLifecycle()
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
                                                    val screenWidth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                                        windowManager.currentWindowMetrics.bounds.width()
                                                    } else {
                                                        val p = android.graphics.Point(); windowManager.defaultDisplay.getSize(p); p.x
                                                    }
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
                
                val screenWidth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    windowManager.currentWindowMetrics.bounds.width()
                } else {
                    val p = android.graphics.Point(); windowManager.defaultDisplay.getSize(p); p.x
                }
                val screenHeight = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    windowManager.currentWindowMetrics.bounds.height()
                } else {
                    val p = android.graphics.Point(); windowManager.defaultDisplay.getSize(p); p.y
                }

                // Initial position fallback logic
                val estimatedWidth = 75 * density
                val initX = savedX ?: when (savedSnap) {
                    "LEFT" -> -(estimatedWidth * 0.35f).toInt()
                    "RIGHT" -> (screenWidth - estimatedWidth * 0.65f).toInt()
                    else -> ((screenWidth - estimatedWidth) / 2).toInt()
                }
                
                var safeTopInset = (24 * density).toInt()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    safeTopInset = windowManager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                        WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()
                    ).top + (-4 * density).toInt()
                }
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
                
                // Binary Visibility Controller: Instantly sync View visibility with state
                // This eliminates all fading/translucency issues.
                scope.launch {
                    stateFlow.collect { state ->
                        val isVisible = state.lifecycleState == OverlayLifecycleState.VISIBLE
                        composeView?.visibility = if (isVisible) View.VISIBLE else View.GONE
                    }
                }
                
                scope.launch {
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
                
            } catch (e: Exception) {
                Log.e("OverlayDebug", "[FloatingCounterManager] Error in attach loop: ${e.message}", e)
            }
        }
    }
    
    private fun detach() {
        Log.d("OverlayDebug", "[FloatingCounterManager] detach() called.")
        composeView?.let { view ->
            try {
                // Safety: Force GONE before removal
                view.visibility = View.GONE
                windowManager.removeView(view)
            } catch (e: Exception) {
                Log.e("OverlayDebug", "[FloatingCounterManager] Error detaching counter: ${e.message}")
            } finally {
                composeView = null
            }
        }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }
    
    fun destroy() {
        isDestroyed = true
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
    val context = LocalContext.current
    val currentReels = state.count
    val isLimitSet = state.dailyGoal > 0
    val dailyGoal = if (isLimitSet) state.dailyGoal else 1
    
    val progress = if (isLimitSet) currentReels.toFloat() / dailyGoal.toFloat() else 0f
    
    val startColor = MaterialTheme.colorScheme.surface
    val targetRed = Color(0xFFEF9A9A) // Soft coral red
    
    val cappedProgress = progress.coerceIn(0f, 1f)
    
    val backgroundColor = if (progress > 0) {
        lerp(startColor, targetRed.copy(alpha = 0.4f), cappedProgress)
    } else {
        startColor
    }
    
    val isOverLimit = if (isLimitSet) progress >= 1f else false
    val containerColor = backgroundColor.copy(alpha = 1.0f) // Forced 100% opacity
    val borderColor = if (isOverLimit) {
        MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
    }
    val contentColor = if (isOverLimit) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
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
            .pointerInput(Unit) {
                val touchSlop = 8.dp.toPx()
                val tapTimeout = 300L
                
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown()
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
                                    onDrag(dragAmount.x, dragAmount.y)
                                    pointerChange.consume()
                                }
                                if (System.currentTimeMillis() - startTime > tapTimeout) {
                                    clickCancelled = true
                                }
                            } else {
                                // Up event
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
            Text(
                text = "REELS", 
                style = MaterialTheme.typography.labelSmall, 
                color = contentColor.copy(alpha = reelsLabelAlpha), 
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
