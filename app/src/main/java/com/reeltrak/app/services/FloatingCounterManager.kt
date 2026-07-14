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
        
        val leftX = 0
        val centerX = maxX / 2
        val rightX = maxX
        
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
        
        animateToPosition(targetX, safeTopInset)
        
        val prefs = context.getSharedPreferences("overlay_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("overlay_snap", targetSnap).apply()
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
                                FloatingIcon(
                                    state = state,
                                    onDrag = { dx, dy ->
                                        snapAnimator?.cancel()
                                        val view = composeView ?: return@FloatingIcon
                                        val params = view.layoutParams as? WindowManager.LayoutParams ?: return@FloatingIcon
                                        windowOffsetFlow.value = Pair(
                                            params.x + dx.toInt(),
                                            params.y + dy.toInt()
                                        )
                                    },
                                    onDragEnd = {
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
                val estimatedWidth = 75 * density
                val screenWidth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    windowManager.currentWindowMetrics.bounds.width()
                } else {
                    val p = android.graphics.Point(); windowManager.defaultDisplay.getSize(p); p.x
                }
                
                val initX = when (savedSnap) {
                    "LEFT" -> 0
                    "RIGHT" -> (screenWidth - estimatedWidth).toInt()
                    else -> ((screenWidth - estimatedWidth) / 2).toInt()
                }
                
                var safeTopInset = (24 * density).toInt()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    safeTopInset = windowManager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                        WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()
                    ).top + (-4 * density).toInt()
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
                    x = initX
                    y = safeTopInset
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
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit
) {
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
    
    Card(
        modifier = Modifier
            .padding(12.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x, dragAmount.y)
                    }
                )
            },
        shape = CircleShape,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 7.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "REELS", 
                style = MaterialTheme.typography.labelSmall, 
                color = contentColor.copy(alpha = 0.7f), 
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

            if (state.dailyGoal == -1) {
                Text(
                    text = "Limit not set", 
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 8.sp,
                    color = contentColor.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}
