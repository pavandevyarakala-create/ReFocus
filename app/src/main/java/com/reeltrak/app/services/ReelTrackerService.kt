package com.reeltrak.app.services

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.app.KeyguardManager
import androidx.core.content.ContextCompat
import com.reeltrak.app.data.local.dao.SessionDao
import com.reeltrak.app.data.local.entity.SessionEntity
import com.reeltrak.app.domain.repository.PreferencesRepository
import com.reeltrak.app.data.local.dao.DailyStatisticsDao
import com.reeltrak.app.services.overlay.OverlayStateManager

import com.reeltrak.app.services.tracking.SessionManager
import com.reeltrak.app.services.tracking.SessionState
import com.reeltrak.app.services.tracking.engine.detector.InstagramDetectorV2
import com.reeltrak.app.services.tracking.engine.detector.YouTubeDetectorV2
import com.reeltrak.app.services.tracking.engine.state.TransitionLedger
import com.reeltrak.app.services.tracking.engine.core.IReelDetector
import com.reeltrak.app.services.tracking.engine.model.DetectionSnapshot
import com.reeltrak.app.services.tracking.engine.model.SnapshotClassification
import com.reeltrak.app.services.accessibility.snapshot.DirectionStateManager
import com.reeltrak.app.services.accessibility.snapshot.ScrollDirection
import java.util.UUID
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

@AndroidEntryPoint
class ReelTrackerService : AccessibilityService() {

    @Inject
    lateinit var sessionDao: SessionDao

    @Inject
    lateinit var preferencesRepository: PreferencesRepository

    @Inject
    lateinit var dailyStatisticsDao: DailyStatisticsDao

    @Inject
    lateinit var dayManager: com.reeltrak.app.services.tracking.DayManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processingChannel = kotlinx.coroutines.channels.Channel<Long>(capacity = 64, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    
    @Inject
    lateinit var overlayStateManager: OverlayStateManager
    
    private lateinit var sessionManager: SessionManager
    private val pipelineMutex = kotlinx.coroutines.sync.Mutex()
    private val directionManager = DirectionStateManager()
    
    // --- V3 ENGINE ---
    private val transitionLedger = TransitionLedger {
        // Forward-only counting: Ignore increments if we are navigating backwards
        if (directionManager.currentDirection != ScrollDirection.BACKWARD) {
            incrementProductionCount()
        } else {
            Log.d("ReelTrakEngine", "Increment suppressed: Backward scroll detected.")
        }
    }
    private val detectors = listOf<IReelDetector>(
        InstagramDetectorV2(),
        YouTubeDetectorV2()
    )
    // -----------------

    private var floatingCounterManager: FloatingCounterManager? = null
    
    private var hideJob: Job? = null
    private var watchdogJob: Job? = null

    private var lastScrollFromIndex = -1
    private var wasOverlayOpenLastFrame = false
    private var watchTimerJob: Job? = null

    private var lastEventProcessingTime = 0L
    private val EVENT_DEBOUNCE_MS = 50L
    private var lastProcessedEpochDay = -1L
    private var lastStrictKickTime = 0L
    
    // Debug Harness
    // (Legacy debug variables removed for V2 Engine)
    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            serviceScope.launch {
                pipelineMutex.withLock {
                    when (intent?.action) {
                        "ACTION_DEBUG_SHOW_OVERLAY" -> {
                            Log.d("OverlayDebug", "ACTION_DEBUG_SHOW_OVERLAY received (Mock Start Session)")
                            sessionManager.transitionTo(SessionState.ENTERING_REELS)
                            sessionManager.transitionTo(SessionState.ACTIVE)
                        }
                        "ACTION_DEBUG_HIDE_OVERLAY" -> {
                            Log.d("OverlayDebug", "ACTION_DEBUG_HIDE_OVERLAY received (Mock End Session)")
                            sessionManager.transitionTo(SessionState.EXITING)
                            sessionManager.transitionTo(SessionState.IDLE)
                        }
                        "ACTION_DEBUG_INCREMENT" -> {
                            Log.d("OverlayDebug", "ACTION_DEBUG_INCREMENT received (Mock Reel Change)")
                            sessionManager.incrementReel()
                            dayManager.incrementReelCount(1)
                        }
                        "ACTION_DEBUG_RESET" -> {
                            Log.d("OverlayDebug", "ACTION_DEBUG_RESET received")
                            overlayStateManager.updateState { it.copy(count = 0) }
                        }
                        "ACTION_DEBUG_DESTROY" -> {
                            Log.d("OverlayDebug", "ACTION_DEBUG_DESTROY received")
                            floatingCounterManager?.destroy()
                            floatingCounterManager = null
                            sessionManager.transitionTo(SessionState.DESTROYED)
                        }
                        "ACTION_DEBUG_RECREATE" -> {
                            Log.d("OverlayDebug", "ACTION_DEBUG_RECREATE received")
                            floatingCounterManager?.destroy()
                            floatingCounterManager = FloatingCounterManager(this@ReelTrackerService, overlayStateManager.state)
                            floatingCounterManager?.attach()
                        }
                        "ACTION_DEBUG_REQUEST_STATE" -> {
                            val reportIntent = Intent("ACTION_DEBUG_STATE_REPORT").apply {
                                putExtra("isAttached", floatingCounterManager != null)
                                putExtra("isVisible", overlayStateManager.state.value.isVisible)
                                putExtra("count", sessionManager.reelsCount)
                            }
                            sendBroadcast(reportIntent)
                        }
                    }
                }
            }
        }
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d("ReelTrakEngine", "Screen OFF: Forcing INSTANT hide")
                    // IMMEDIATE INTERRUPT: Bypass all queues and locks to hide the window
                    overlayStateManager.updateState { it.copy(isVisible = false, lifecycleState = com.reeltrak.app.services.overlay.OverlayLifecycleState.HIDDEN) }
                    forceVisibilityEvaluation()
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    Log.d("ReelTrakEngine", "Screen state changed: ${intent.action}")
                    forceVisibilityEvaluation()
                }
            }
        }
    }

    private fun isScreenInteractiveAndUnlocked(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return powerManager.isInteractive && !keyguardManager.isKeyguardLocked
    }

    private fun forceVisibilityEvaluation() {
        val isActive = sessionManager.currentState == SessionState.ACTIVE || sessionManager.currentState == SessionState.ENTERING_REELS || sessionManager.currentState == SessionState.PAUSED
        val isScreenReady = isScreenInteractiveAndUnlocked()
        
        // If the screen is off or locked, we MUST hide the widget
        if (!isScreenReady) {
            overlayStateManager.evaluateVisibility(isOnReels = false, isSystemOverlay = false, isScreenReady = false)
            if (sessionManager.currentState != SessionState.IDLE) {
                sessionManager.transitionTo(SessionState.IDLE)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.e("ReelTrakEngine", "SERVICE_LIFECYCLE: onCreate() called")
        
        sessionManager = SessionManager { oldState, newState ->
            handleSessionStateChange(oldState, newState)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.e("ReelTrakEngine", "SERVICE_LIFECYCLE: onServiceConnected() SUCCESS")
        
        // Initial setup
        floatingCounterManager = FloatingCounterManager(
            context = this, 
            stateFlow = overlayStateManager.state
        )
        serviceScope.launch(Dispatchers.Main) {
            floatingCounterManager?.attach()
        }
        
        // --- Unified Day & Stats Sync ---
        serviceScope.launch {
            combine(
                dayManager.currentEpochDay,
                dayManager.currentDayStats
            ) { today, stats ->
                today to stats
            }
            .distinctUntilChanged()
            .collect { (today, stats) ->
                pipelineMutex.withLock {
                    overlayStateManager.updateState { currentState ->
                        var nextState = currentState.copy(currentDateEpochDay = today)
                        
                        // PARITY CHECK: Only update count if the stats belong to the currently active day.
                        // This prevents "New Day + Old Stats" race conditions during midnight transitions.
                        if (stats != null && stats.dateEpochDay == today) {
                            val totalToday = stats.totalReelsScrolled
                            val currentGoal = stats.dailyGoal
                            nextState = nextState.copy(
                                count = totalToday,
                                isOverLimit = (currentGoal > 0 && totalToday > currentGoal),
                                dailyGoal = currentGoal
                            )
                        } else {
                            // Transient state or new day loading: Safely reset to 0/Not Set.
                            nextState = nextState.copy(
                                count = 0,
                                isOverLimit = false,
                                dailyGoal = -1
                            )
                        }
                        nextState
                    }
                    
                    if (lastProcessedEpochDay != -1L && today > lastProcessedEpochDay) {
                         saveSessionToDb()
                         sessionManager.resetSessionCount()
                    }
                    lastProcessedEpochDay = today
                }
            }
        }

        // Observe Strict Mode preference
        serviceScope.launch {
            preferencesRepository.isStrictModeEnabled.collect { isEnabled ->
                overlayStateManager.updateState { it.copy(isStrictModeEnabled = isEnabled) }
            }
        }

        // Register Debug Receiver
        val filter = IntentFilter().apply {
            addAction("ACTION_DEBUG_SHOW_OVERLAY")
            addAction("ACTION_DEBUG_HIDE_OVERLAY")
            addAction("ACTION_DEBUG_INCREMENT")
            addAction("ACTION_DEBUG_RESET")
            addAction("ACTION_DEBUG_DESTROY")
            addAction("ACTION_DEBUG_RECREATE")
            addAction("ACTION_DEBUG_REQUEST_STATE")
        }
        ContextCompat.registerReceiver(
            this,
            debugReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(
            this,
            screenStateReceiver,
            screenFilter,
            ContextCompat.RECEIVER_EXPORTED
        )
        
        // Start snapshot processor
        serviceScope.launch(Dispatchers.Default) {
            for (eventTime in processingChannel) {
                Log.e("PipelineAudit", "3. Channel received trigger at $eventTime")
                processLatestUIState(eventTime)
            }
        }
        
        Log.d("OverlayDebug", "OverlayService started (onServiceConnected)")
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        Log.e("PipelineAudit", "1. onAccessibilityEvent received")
        if (event == null) return
        
        // Capture direction immediately on every relevant event
        directionManager.updateDirection(event)

        val eventType = event.eventType
        // REJECTION: Ignore events that don't indicate a potential UI mutation or navigation
        val interestingEvent = when (eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> true
            else -> false
        }
        if (!interestingEvent) return

        val now = android.os.SystemClock.elapsedRealtime()
        val isCriticalEvent = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        
        if (!isCriticalEvent && now - lastEventProcessingTime < EVENT_DEBOUNCE_MS) {
            scheduleOverlaySynchronization()
            return // Skip processing to save CPU and maintain 60fps
        }
        
        // Cancel the sync job since we are processing a new event normally
        overlaySyncJob?.cancel()
        lastEventProcessingTime = now

        // Unconditionally update session app if it was a window state change
        // We do this immediately on the binder thread to prevent missing fast app switches
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val root = rootInActiveWindow
            val activePkg = root?.packageName?.toString()
            root?.recycle()
            
            // IMMEDIATE VISIBILITY INTERRUPT:
            // If we are definitely NOT in a tracked app, hide the overlay immediately 
            // on the Binder thread to ensure <100ms response.
            if (!sessionManager.isTrackedApp(activePkg) && !isSystemOverlay(activePkg)) {
                overlayStateManager.evaluateVisibility(isOnReels = false, isSystemOverlay = false, isScreenReady = isScreenInteractiveAndUnlocked())
            }

            serviceScope.launch {
                pipelineMutex.withLock {
                    Log.d("PipelineAudit", "updateCurrentApp: $activePkg")
                    sessionManager.updateCurrentApp(activePkg)
                    
                    // Fail-safe: if the app is definitely not tracked, hide overlay immediately
                    if (!sessionManager.isTrackedApp(activePkg)) {
                        overlayStateManager.evaluateVisibility(isOnReels = false, isSystemOverlay = false)
                    }
                }
            }
        }

        // Try to send a trigger. Since it's a BUFFERED channel, this will likely succeed.
        Log.e("PipelineAudit", "2. Channel trySend $now")
        processingChannel.trySend(now)
    }

    private fun isSystemOverlay(packageName: String?): Boolean {
        return packageName == null || 
               packageName == "com.android.systemui" || 
               packageName.contains("inputmethod", ignoreCase = true) || 
               packageName == "android"
    }
    
    private suspend fun processLatestUIState(eventTime: Long) {
        Log.e("PipelineAudit", "4. processLatestUIState entered for $eventTime")
        
        // Throttling: If this event arrived while we were already processing a more recent one,
        // we can safely skip it to maintain performance and avoid redundant BFS walks.
        if (eventTime < lastEventProcessingTime) {
            Log.d("PipelineAudit", "Skipping stale event: $eventTime < $lastEventProcessingTime")
            return
        }

        val tBeforeRoot = android.os.SystemClock.elapsedRealtime()
        val rootNode = rootInActiveWindow ?: return
        val rootNodeIdentity = System.identityHashCode(rootNode)
        Log.e("PipelineAudit", "5. rootNode acquired [id: $rootNodeIdentity]")
        val tAfterRoot = android.os.SystemClock.elapsedRealtime()
    
        // Record IPC latency for diagnostic monitoring
        com.reeltrak.app.services.accessibility.DiagnosticsManager.recordRootIpcDuration(tAfterRoot - tBeforeRoot, false)
        
        val activePackage = rootNode.packageName?.toString() ?: ""
        
        try {
            // Lifecycle 2: V3 Engine Pipeline
            val detector = detectors.find { it.supports(activePackage) }
            if (detector != null) {
                Log.e("PipelineAudit", "6. Detector invoked")
                // Perform single-pass BFS analysis - Expensive IPC-heavy UI traversal
                val snapshot = detector.analyze(rootNode)
                Log.e("PipelineAudit", "7. Detector returned")
                
                pipelineMutex.withLock {
                    // --- VISIBILITY HARDENING: Internal Overlay Logic ---
                    // Rule 1: We are "Active" if COMMITTED, PENDING, or an INTERNAL_OVERLAY (but only if already in a session).
                    val isInternalOverlay = snapshot.isInternalOverlay && 
                                           (sessionManager.currentState == SessionState.ACTIVE || sessionManager.currentState == SessionState.PAUSED)
                    
                    val isActiveScreen = snapshot.isCommitted || snapshot.isPending || isInternalOverlay

                    // 1. First record detection so SessionManager transitions to ACTIVE
                    if (snapshot.isCommitted || snapshot.isPending) {
                        sessionManager.recordSuccessfulDetection()
                    } else if (isInternalOverlay) {
                        // Keep session alive but block counting
                        sessionManager.transitionTo(SessionState.PAUSED)
                    } else if (snapshot.isDiscarded) {
                        // We are in Instagram/YouTube but NOT on a Reel or an Internal Overlay (e.g. Feed)
                        // Transition to IDLE to hide widget and end session.
                        sessionManager.transitionTo(SessionState.IDLE)
                    }

                    // 2. Update Global Overlay State
                    overlayStateManager.updateState { 
                        it.copy(
                            confidence = if (isActiveScreen) 100 else 0,
                            screenClassification = snapshot.platform
                        )
                    }
                    
                    // 3. Evaluate Overlay Visibility (Will now see SessionState.ACTIVE or PAUSED)
                    val isScreenReady = isScreenInteractiveAndUnlocked()
                    overlayStateManager.evaluateVisibility(
                        isOnReels = isActiveScreen && isScreenReady, 
                        isSystemOverlay = activePackage == "com.android.systemui",
                        isScreenReady = isScreenReady
                    )
                    
                    // --- Strict Mode Enforcement ---
                    val currentState = overlayStateManager.state.value
                    if (currentState.isStrictModeEnabled && currentState.isOverLimit && isActiveScreen) {
                        enforceStrictMode()
                    }
                    
                    // 4. Drive the Transition Ledger
                    // Rule 2: NEVER process increments while in an internal overlay.
                    if (!isInternalOverlay && 
                        (sessionManager.currentState == SessionState.ACTIVE || 
                         sessionManager.currentState == SessionState.ENTERING_REELS)) {
                        Log.e("PipelineAudit", "8. Ledger invoked")
                        transitionLedger.process(snapshot)
                    }

                    // Record that we have successfully analyzed up to this event time
                    lastEventProcessingTime = eventTime
                }
            } else {
                pipelineMutex.withLock {
                    // Not a supported app. Ensure session transitions to IDLE and overlay is hidden.
                    if (!isSystemOverlay(activePackage)) {
                        sessionManager.updateCurrentApp(activePackage)
                    }
                    overlayStateManager.evaluateVisibility(isOnReels = false, isSystemOverlay = false, isScreenReady = isScreenInteractiveAndUnlocked())
                }
            }
            
        } catch (e: Exception) {
            Log.e("PipelineAudit", "CRITICAL: Pipeline error", e)
        } finally {
            val preRecycleIdentity = System.identityHashCode(rootNode)
            Log.e("PipelineAudit", "9. Executing finally block, recycling rootNode [id: $preRecycleIdentity]")
            try {
                rootNode.recycle()
            } catch (e: Exception) {
                Log.e("PipelineAudit", "CRITICAL: rootNode.recycle() threw exception", e)
                throw e
            }
        }
    }
    
    private fun enforceStrictMode() {
        val now = android.os.SystemClock.elapsedRealtime()
        // Rate limit kicks to once every 2 seconds to avoid vibration loops/UI jitter
        if (now - lastStrictKickTime < 2000) return
        lastStrictKickTime = now

        serviceScope.launch(Dispatchers.Main) {
            Toast.makeText(
                this@ReelTrackerService,
                "Daily Reel limit reached. Strict Mode is active.",
                Toast.LENGTH_LONG
            ).show()
            
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun incrementProductionCount() {
        Log.e("ReelTrakEngine", "V3_ENGINE: Legitimate Reel Counted!")
        sessionManager.incrementReel()
        dayManager.incrementReelCount(1)
        Log.d("PipelineAudit", "incrementProductionCount called. New reelsCount=${sessionManager.reelsCount}")
        
        // Single source of truth: 
        // We no longer optimistically update overlayStateManager.count here.
        // The count will be updated via the dayManager.currentDayStats collector in onServiceConnected.
    }

    private fun handleSessionStateChange(oldState: SessionState, newState: SessionState) {
        Log.d("SessionDebug", "handleSessionStateChange: $oldState -> $newState")
        overlayStateManager.updateState { 
            it.copy(
                sessionState = newState, 
                sessionStartTime = sessionManager.sessionStartTime,
                currentApp = sessionManager.currentApp,
                currentSessionReels = sessionManager.reelsCount
            ) 
        }
        
        when (newState) {
            SessionState.ENTERING_REELS -> {
                // Overlay is permanently attached; its visibility is driven by Compose state.
            }
            SessionState.ACTIVE -> {
                hideJob?.cancel()
                overlayStateManager.updateState { 
                    it.copy(currentApp = sessionManager.currentApp) 
                }
                // REMOVED: overlayStateManager.evaluateVisibility(isOnReels = true, isSystemOverlay = false)
                // Visibility is now strictly driven by onAccessibilityEvent
                startWatchdog()
            }
            SessionState.EXITING -> {
                stopWatchdog()
                overlayStateManager.evaluateVisibility(isOnReels = false, isSystemOverlay = false, isScreenReady = isScreenInteractiveAndUnlocked())
            }
            SessionState.IDLE -> {
                stopWatchdog()
                // Completely reset all temporary tracking variables linked to the viewing session
                lastScrollFromIndex = -1
                wasOverlayOpenLastFrame = false
                directionManager.reset()
                
                hideJob?.cancel()
                overlayStateManager.evaluateVisibility(isOnReels = false, isSystemOverlay = false, isScreenReady = isScreenInteractiveAndUnlocked())
                
                saveSessionToDb()
                transitionLedger.reset()
            }
            SessionState.DESTROYED -> {
                saveSessionToDb()
                // Destroy synchronously to guarantee execution before serviceScope is cancelled
                // (onDestroy runs on the main thread)
                try {
                    floatingCounterManager?.destroy()
                } catch (e: Exception) {
                    Log.e("ReelTrakEngine", "Error destroying floating counter: ${e.message}")
                }
                floatingCounterManager = null
            }
            else -> {}
        }
    }

    // checkDailyLimit legacy logic removed in favor of DayManager

    
    private fun startWatchdog() {
        stopWatchdog()
        watchdogJob = serviceScope.launch {
            // Give the system time to settle before the first check
            delay(2000)
            while (isActive) {
                try {
                    pipelineMutex.withLock {
                        val rootNode = rootInActiveWindow
                        try {
                            val activePackage = rootNode?.packageName?.toString()
        
                            // Check if we are still in a tracked app or system overlay
                            val isTracked = sessionManager.isTrackedApp(activePackage)
                            val isSystemOverlay = activePackage == "com.android.systemui" || 
                                                  activePackage?.contains("inputmethod") == true || 
                                                  activePackage == "android"
        
                            if (activePackage != null && !isTracked && !isSystemOverlay) {
                                Log.w("ReelCounter-Debug", "[Watchdog] Inconsistency detected! Overlay is active but foreground app is $activePackage. Synchronizing state.")
                                // Force state synchronization
                                sessionManager.updateCurrentApp(activePackage)
                            } else if (isTracked) {
                                // Standard inactivity timeout (15s). Closing session.
                                val now = System.currentTimeMillis()
                                val timeSinceLastDetection = now - sessionManager.lastSuccessfulDetection
                                
                                if (timeSinceLastDetection > 15000L) {
                                    Log.d("ReelTrakEngine", "Watchdog: Inactivity timeout (15s). Closing session.")
                                    sessionManager.transitionTo(SessionState.EXITING)
                                    sessionManager.transitionTo(SessionState.IDLE)
                                }
                            }
                        } finally {
                            rootNode?.recycle()
                        }
                    }
                } catch (e: Exception) {
                    // Ignore exceptions
                }
                // Check every 3 seconds
                delay(3000)
            }
        }
    }

    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    private fun saveSessionToDb() {
        val app = sessionManager.currentApp
        val count = sessionManager.reelsCount
        val startTime = sessionManager.sessionStartTime
        
        if (app != null && count > 0 && startTime > 0) {
            val endTime = System.currentTimeMillis()
            val sessionToSave = SessionEntity(
                appName = app,
                startTimeStamp = startTime,
                endTimeStamp = endTime,
                reelsScrolled = count
            )
            serviceScope.launch {
                sessionDao.insertSession(sessionToSave)
                Log.d("ReelTrakEngine", "Saved session to DB: $sessionToSave")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("OverlayDebug", "OverlayService destroyed")
        try {
            unregisterReceiver(debugReceiver)
            unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) { }
        
        sessionManager.transitionTo(SessionState.DESTROYED)
        serviceScope.cancel()
    }

    override fun onInterrupt() {
        serviceScope.launch {
            pipelineMutex.withLock {
                sessionManager.transitionTo(SessionState.IDLE)
                transitionLedger.reset()
                directionManager.reset()
            }
        }
    }

    private var overlaySyncJob: Job? = null

    private fun scheduleOverlaySynchronization() {
        overlaySyncJob?.cancel()
        overlaySyncJob = serviceScope.launch(Dispatchers.Default) {
            delay(EVENT_DEBOUNCE_MS + 20L)
            
            // If another event was processed normally during our delay, we don't need to sync.
            if (android.os.SystemClock.elapsedRealtime() - lastEventProcessingTime < EVENT_DEBOUNCE_MS) {
                return@launch
            }
            
            // Expensive IPC and analysis outside the lock
            val rootNode = rootInActiveWindow ?: return@launch
            val activePackage = rootNode.packageName?.toString() ?: ""
            
            try {
                val detector = detectors.find { it.supports(activePackage) }
                if (detector != null) {
                    // REUSE the exact same snapshot generation pipeline
                    val snapshot = detector.analyze(rootNode)
                    
                    pipelineMutex.withLock {
                        // Double check after acquiring lock in case a normal event finished just now
                        if (android.os.SystemClock.elapsedRealtime() - lastEventProcessingTime < EVENT_DEBOUNCE_MS) {
                            return@withLock
                        }

                        val isInternalOverlay = snapshot.isInternalOverlay && 
                                               (sessionManager.currentState == SessionState.ACTIVE || sessionManager.currentState == SessionState.PAUSED)
                        
                        val isActiveScreen = snapshot.isCommitted || snapshot.isPending || isInternalOverlay
                        val isScreenReady = isScreenInteractiveAndUnlocked()
                        
                        if (snapshot.isCommitted || snapshot.isPending) {
                            sessionManager.recordSuccessfulDetection()
                        } else if (isInternalOverlay) {
                            sessionManager.transitionTo(SessionState.PAUSED)
                        } else if (snapshot.isDiscarded) {
                            sessionManager.transitionTo(SessionState.IDLE)
                        }
                        
                        overlayStateManager.updateState { 
                            it.copy(
                                confidence = if (isActiveScreen) 100 else 0,
                                screenClassification = snapshot.platform
                            )
                        }
                        
                        overlayStateManager.evaluateVisibility(
                            isOnReels = isActiveScreen && isScreenReady, 
                            isSystemOverlay = activePackage == "com.android.systemui",
                            isScreenReady = isScreenReady
                        )

                        if (!isInternalOverlay && 
                            (sessionManager.currentState == SessionState.ACTIVE || 
                             sessionManager.currentState == SessionState.ENTERING_REELS)) {
                            transitionLedger.process(snapshot)
                        }
                    }
                } else {
                    pipelineMutex.withLock {
                        if (!isSystemOverlay(activePackage)) {
                            sessionManager.updateCurrentApp(activePackage)
                        }
                        overlayStateManager.evaluateVisibility(isOnReels = false, isSystemOverlay = false, isScreenReady = isScreenInteractiveAndUnlocked())
                    }
                }
            } catch (e: Exception) {
                Log.e("ReelTrakEngine", "Error in overlay synchronization", e)
            } finally {
                rootNode.recycle()
            }
        }
    }
}
