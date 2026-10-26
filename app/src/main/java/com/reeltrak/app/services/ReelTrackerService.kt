package com.reeltrak.app.services

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.app.KeyguardManager
import androidx.core.content.ContextCompat
import com.reeltrak.app.data.local.entity.SessionData
import com.reeltrak.app.domain.repository.PreferencesRepository
import com.reeltrak.app.services.overlay.OverlayStateManager

import com.reeltrak.app.data.security.authorization.AppAuthorizationManager
import com.reeltrak.app.data.security.authorization.AuthorizationState
import com.reeltrak.app.services.tracking.SessionManager
import com.reeltrak.app.services.tracking.SessionState
import com.reeltrak.app.services.tracking.InconclusiveSnapshotGate
import com.reeltrak.app.services.tracking.StrictModeEnforcementGate
import com.reeltrak.app.services.tracking.StrictModeEnforcementPolicy
import com.reeltrak.app.services.tracking.engine.detector.InstagramDetectorV2
import com.reeltrak.app.services.tracking.engine.detector.YouTubeDetectorV2
import com.reeltrak.app.services.tracking.engine.state.SharedPreferencesHistoryStore
import com.reeltrak.app.services.tracking.engine.state.TransitionLedger
import com.reeltrak.app.services.tracking.engine.state.TransitionAcceptance
import com.reeltrak.app.services.tracking.engine.core.IReelDetector
import com.reeltrak.app.services.tracking.engine.model.DetectionSnapshot
import com.reeltrak.app.services.tracking.engine.model.InternalOverlayKind
import com.reeltrak.app.services.accessibility.snapshot.DirectionStateManager
import com.reeltrak.app.services.accessibility.snapshot.DirectionStateSnapshot
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
    lateinit var preferencesRepository: PreferencesRepository

    @Inject
    lateinit var dayManager: com.reeltrak.app.services.tracking.DayManager

    @Inject
    lateinit var syncManager: com.reeltrak.app.core.sync.SyncManager

    @Inject
    lateinit var subscriptionManager: com.reeltrak.app.domain.managers.SubscriptionManager

    @Inject
    lateinit var accountRepository: com.reeltrak.app.domain.repository.AccountRepository

    @Inject
    lateinit var appAuthorizationManager: AppAuthorizationManager

    @Inject
    lateinit var inAppUpdateManager: com.reeltrak.app.core.update.InAppUpdateManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var processingQueue: LatestStateAnalysisQueue<TrackingAnalysisRequest>? = null
    private val detectorAnalysis = SerializedDetectorAnalysis()
    private val inconclusiveSnapshotGate = InconclusiveSnapshotGate()
    @Volatile private var transitionDirectionForLedger: DirectionStateSnapshot? = null
    @Volatile private var transitionSnapshotForLedger: DetectionSnapshot? = null

    @Inject
    lateinit var overlayStateManager: OverlayStateManager

    private lateinit var sessionManager: SessionManager
    private val pipelineMutex = kotlinx.coroutines.sync.Mutex()
    private val directionManager = DirectionStateManager()

    // --- V3 ENGINE ---
    private val transitionLedger by lazy {
        TransitionLedger(
            onIncrement = {},
            onHistoricalMatch = {
                val direction = transitionDirectionForLedger
                if (direction?.direction == ScrollDirection.BACKWARD) {
                    Log.d("ReelTrakEngine", "Backward scroll consumed on historical match.")
                    directionManager.onBackwardTransitionConsumed(direction.revision)
                }
            },
            historyStore = SharedPreferencesHistoryStore(this),
            transitionAcceptance = ::incrementProductionCount,
            onTransitionCommitted = ::applyCommittedTransition
        )
    }
    private val detectors = listOf<IReelDetector>(
        InstagramDetectorV2(),
        YouTubeDetectorV2()
    )
    // -----------------

    private var floatingCounterManager: FloatingCounterManager? = null

    private var hideJob: Job? = null
    private var watchdogJob: Job? = null
    private var overlaySyncJob: Job? = null
    @Volatile private var strictEnforcementJob: Job? = null
    @Volatile private var strictModeRecheckJob: Job? = null
    private val strictEnforcementGate = com.reeltrak.app.services.tracking.StrictModeEnforcementGate()
    private var lastToastTimeMs = -1L
    private var consecutiveEnforcementAttempts = 0
    private var lastEnforcedSurface: String? = null
    private val TOAST_COOLDOWN_MS = 4_000L
    private val MAX_CONSECUTIVE_BACK_ATTEMPTS = 3

    private var lastScrollFromIndex = -1
    private var wasOverlayOpenLastFrame = false

    @Volatile private var lastEventProcessingTime = 0L
    private val EVENT_DEBOUNCE_MS = 50L
    private val analysisEventPolicy = AccessibilityAnalysisEventPolicy(EVENT_DEBOUNCE_MS)
    private var lastProcessedDateString: String? = null

    @Volatile private var isStrictModeEnabledCached = false
    @Volatile private var isStrictModeEnforcedInCurrentSession = false

    // Screen state receiver

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d("ReelTrakEngine", "Screen OFF: Forcing INSTANT hide")
                    cancelStrictEnforcementCycle(resetGate = true)
                    // IMMEDIATE INTERRUPT: Bypass all queues and locks to hide the window
                    overlayStateManager.updateState { it.copy(isVisible = false, lifecycleState = com.reeltrak.app.services.overlay.OverlayLifecycleState.HIDDEN) }
                    forceVisibilityEvaluation()
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    Log.d("ReelTrakEngine", "Screen state changed: ${intent.action}")
                    forceVisibilityEvaluation()
                    if (isScreenInteractiveAndUnlocked()) evaluateCurrentStrictModeSurface()
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

    private fun ensureFloatingCounterAttached(resetRetryWindow: Boolean = false) {
        try {
            val manager = floatingCounterManager ?: FloatingCounterManager(
                context = this,
                stateFlow = overlayStateManager.state
            ).also { floatingCounterManager = it }
            if (resetRetryWindow) {
                manager.allowRetryAfterLifecycle()
            } else {
                manager.attach()
            }
        } catch (failure: Exception) {
            // Overlay creation/attachment is best-effort and must never abort event analysis/counting.
            Log.e("OverlayDebug", "Unable to initialize floating counter manager; a later lifecycle/event may retry", failure)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.e("ReelTrakEngine", "SERVICE_LIFECYCLE: onCreate() called")
        Log.d("STARTUP_DEBUG", "ReelTrackerService: onCreate() called")

        sessionManager = SessionManager(
            onStateChanged = { oldState, newState, completedSession ->
                handleSessionStateChange(oldState, newState, completedSession)
            }
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        cancelStrictEnforcementCycle(resetGate = true)
        Log.e("ReelTrakEngine", "SERVICE_LIFECYCLE: onServiceConnected() SUCCESS")
        Log.d("STARTUP_DEBUG", "ReelTrackerService: onServiceConnected() SUCCESS")

        // Reuse an existing manager on repeated connection callbacks. A fresh lifecycle
        // connection reopens the manager's bounded retry window if earlier addView attempts failed.
        ensureFloatingCounterAttached(resetRetryWindow = true)

        // --- Unified Day & Stats Sync ---
        serviceScope.launch {
            dayManager.currentDayStats.filterNotNull().collect { stats ->
                pipelineMutex.withLock {
                    val totalToday = stats.totalReelsCount
                    val currentGoal = stats.dailyGoal
                    if (com.reeltrak.app.BuildConfig.DEBUG) {
                        Log.d("COUNTER_DIAG", "[POINT_3_STATE_HOLDER_UPDATE] Updating overlayStateManager count=$totalToday, dailyGoal=$currentGoal, thread=${Thread.currentThread().name}")
                    }
                    val dateStr = stats.dateString
                    val isDateChanged = lastProcessedDateString != null && dateStr != lastProcessedDateString

                    overlayStateManager.updateState { currentState ->
                        val resolvedCount = if (isDateChanged) totalToday else maxOf(currentState.count, totalToday)
                        currentState.copy(
                            count = resolvedCount,
                            isOverLimit = (currentGoal > 0 && resolvedCount >= currentGoal),
                            dailyGoal = currentGoal
                        )
                    }

                    if (isDateChanged) {
                         saveSessionToDb()
                         sessionManager.resetSessionCount()
                         // Preserve 10-entry deduplication history across midnight so the active reel
                         // watched across midnight is not spuriously double-counted on the new day.
                         transitionLedger.clearActive()
                    }
                    lastProcessedDateString = dateStr
                }
            }
        }

        // Observe Strict Mode preference (Strict Mode is strictly restricted to Active Premium / Active Trial users)
        serviceScope.launch {
            combine(
                dayManager.currentDayStats,
                subscriptionManager.hasActiveEntitlement
            ) { stats, isEntitled ->
                isEntitled && (stats?.isStrictModeOn ?: false)
            }.collect { isStrict ->
                isStrictModeEnabledCached = isStrict
                if (isStrict && subscriptionManager.hasActiveEntitlementNow()) {
                    evaluateCurrentStrictModeSurface()
                }
            }
        }

        // Observe Counter Theme Mode preference
        serviceScope.launch {
            preferencesRepository.counterThemeMode.collect { mode ->
                pipelineMutex.withLock {
                    overlayStateManager.updateState { currentState ->
                        currentState.copy(counterThemeMode = mode)
                    }
                }
            }
        }

        // Observe Mandatory App Update state from Google Play
        serviceScope.launch {
            inAppUpdateManager.isUpdateRequired.collect { isRequired ->
                pipelineMutex.withLock {
                    overlayStateManager.updateState { currentState ->
                        currentState.copy(isUpdateRequired = isRequired)
                    }
                }
            }
        }

        // Observe Master Account Status (ACTIVE / RESTRICTED)
        serviceScope.launch {
            accountRepository.accountStatus.collect { status ->
                if (status == com.reeltrak.app.domain.models.AccountStatus.RESTRICTED) {
                    Log.w("ReelTrakEngine", "MASTER_ACCESS: Account is RESTRICTED. Force-hiding floating counter.")
                    overlayStateManager.evaluateVisibility(
                        isOnReels = false,
                        isSystemOverlay = false,
                        isScreenReady = false
                    )
                }
            }
        }

        // Observe Multi-Factor Cryptographic Authorization State transitions
        serviceScope.launch {
            appAuthorizationManager.authorizationState.collect { authState ->
                if (authState is AuthorizationState.Authorized) {
                    sessionManager.updateAuthorization(authState.token)
                } else if (authState is AuthorizationState.Tampered) {
                    Log.w("ReelTrakEngine", "AUTHORIZATION_GATE: State is tampered ($authState). Forcing session IDLE, hiding overlay, and draining event queue.")
                    pipelineMutex.withLock {
                        sessionManager.updateAuthorization(null)
                        overlayStateManager.evaluateVisibility(
                            isOnReels = false,
                            isSystemOverlay = false,
                            isScreenReady = false
                        )
                        processingQueue?.clearPending()
                    }
                }
            }
        }

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

        // One conflated consumer serializes every tree read and retains the newest pending state.
        val queue = processingQueue ?: LatestStateAnalysisQueue<TrackingAnalysisRequest>(
            scope = serviceScope,
            onFailure = { failure ->
                if (failure == null) {
                    Log.e("PipelineAudit", "Accessibility analysis retry exhausted")
                } else {
                    Log.e("PipelineAudit", "Accessibility analysis failed; retrying once", failure)
                }
            },
            analyze = ::processLatestUIState
        ).also { processingQueue = it }
        queue.start()
        queue.request(TrackingAnalysisRequest(
            android.os.SystemClock.elapsedRealtime(),
            directionManager.snapshot()
        ))

        if (com.reeltrak.app.BuildConfig.DEBUG) {
            Log.d("OverlayDebug", "OverlayService started (onServiceConnected)")
        }
    }


    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (com.reeltrak.app.BuildConfig.DEBUG) {
            Log.d("PipelineAudit", "1. onAccessibilityEvent received")
        }
        if (event == null) return

        // Capture direction immediately on every relevant event
        directionManager.updateDirection(event)

        val eventType = event.eventType
        if (!analysisEventPolicy.isRelevant(eventType)) return
        val directionSnapshot = directionManager.snapshot()

        // Attachment retries are isolated from analysis. The manager catches and records its own
        // WindowManager failures; the counting queue below continues independently.
        ensureFloatingCounterAttached()

        val now = android.os.SystemClock.elapsedRealtime()
        val isCriticalEvent = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        val isBusy = processingQueue?.isProcessing == true

        if (analysisEventPolicy.shouldDeferFocusEvent(eventType, now)) {
            scheduleOverlaySynchronization()
            return
        }

        if (!isCriticalEvent && isBusy && now - lastEventProcessingTime < EVENT_DEBOUNCE_MS) {
            scheduleOverlaySynchronization()
            return // Skip processing to save CPU and maintain 60fps
        }

        lastEventProcessingTime = now

        // Unconditionally update session app if it was a window state change
        // We do this immediately on the binder thread to prevent missing fast app switches
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val eventPkg = event.packageName?.toString()
            val activePkg = analysisEventPolicy.resolveWindowStatePackage(
                eventPackage = eventPkg,
                isTrackedPackage = sessionManager::isTrackedApp,
                rootProvider = { rootInActiveWindow },
                packageFromRoot = { it.packageName?.toString() },
                releaseRoot = { it.recycle() }
            )

            // IMMEDIATE VISIBILITY INTERRUPT:
            // If we are definitely NOT in a tracked app, hide the overlay immediately
            // on the Binder thread to ensure <100ms response.
            if (!sessionManager.isTrackedApp(activePkg) && !isSystemOverlay(activePkg)) {
                overlayStateManager.evaluateVisibility(isOnReels = false, isSystemOverlay = false, isScreenReady = isScreenInteractiveAndUnlocked())
                strictEnforcementGate.observe(null)
                cancelStrictEnforcementCycle(resetGate = false)
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

        // Keep the latest requested UI state; the single consumer serializes tree analysis.
        if (com.reeltrak.app.BuildConfig.DEBUG) {
            Log.d("PipelineAudit", "2. Analysis request $now")
        }
        processingQueue?.request(TrackingAnalysisRequest(now, directionSnapshot))
    }

    private fun isSystemOverlay(packageName: String?): Boolean {
        return packageName == null ||
               packageName == "com.android.systemui" ||
               packageName.contains("inputmethod", ignoreCase = true) ||
               packageName == "android"
    }

    private suspend fun processLatestUIState(request: TrackingAnalysisRequest): Boolean {
            val eventTime = request.eventTime
            if (com.reeltrak.app.BuildConfig.DEBUG) {
                Log.d("PipelineAudit", "4. processLatestUIState entered for $eventTime")
            }

            // Throttling: If this event arrived while we were already processing a more recent one,
            // we can safely skip it to maintain performance and avoid redundant BFS walks.
            if (eventTime < lastEventProcessingTime) {
                if (com.reeltrak.app.BuildConfig.DEBUG) {
                    Log.d("PipelineAudit", "Skipping stale event: $eventTime < $lastEventProcessingTime")
                }
                return true
            }

            val tBeforeRoot = android.os.SystemClock.elapsedRealtime()
            var rootNode = try { rootInActiveWindow } catch (e: Exception) { null }
            var retryCount = 0
            while (rootNode == null && retryCount < 2) {
                retryCount++
                delay(40)
                rootNode = try { rootInActiveWindow } catch (e: Exception) { null }
            }
            if (rootNode == null) return false
            val rootNodeIdentity = System.identityHashCode(rootNode)
            if (com.reeltrak.app.BuildConfig.DEBUG) {
                Log.d("PipelineAudit", "5. rootNode acquired [id: $rootNodeIdentity] (retries: $retryCount)")
            }
            val tAfterRoot = android.os.SystemClock.elapsedRealtime()

            // Record IPC latency for diagnostic monitoring
            com.reeltrak.app.services.accessibility.DiagnosticsManager.recordRootIpcDuration(tAfterRoot - tBeforeRoot, false)

            try {
                val activePackage = rootNode.packageName?.toString() ?: ""
                // Lifecycle 2: V3 Engine Pipeline
                val detector = detectors.find { it.supports(activePackage) }
                Log.d("STARTUP_DEBUG", "ReelTrackerService: activePackage='$activePackage', matchedDetector=${detector?.javaClass?.simpleName}")
                if (detector != null) {
                    // Performing BFS analysis
                    val detectedSnapshot = detectorAnalysis.run { detector.analyze(rootNode) }
                        .copy(feedTransitionToken = request.direction.feedTransitionToken)
                    // A missing reel anchor can be a transient accessibility-tree gap. Preserve the
                    // current session/overlay and ask the conflated queue for one delayed fresh read.
                    // A second inconclusive result for this trigger is treated as confirmed departure.
                    val snapshot = inconclusiveSnapshotGate.resolve(detectedSnapshot, eventTime)
                        ?: return false
                    Log.d("STARTUP_DEBUG", "ReelTrackerService: snapshot produced: platform=${snapshot.platform}, classification=${snapshot.classification}, isInternalOverlay=${snapshot.isInternalOverlay}")

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
                        Log.d("STARTUP_DEBUG", "ReelTrackerService: evaluating visibility isOnReels=${isActiveScreen && isScreenReady}, sessionState=${sessionManager.currentState}, isScreenReady=$isScreenReady")
                        overlayStateManager.evaluateVisibility(
                            isOnReels = isActiveScreen && isScreenReady,
                            isSystemOverlay = activePackage == "com.android.systemui",
                            isScreenReady = isScreenReady
                        )

                        // 4. Drive the Transition Ledger
                        // Rule 2: NEVER process increments while in an internal overlay.
                        if (!isInternalOverlay &&
                            (sessionManager.currentState == SessionState.ACTIVE ||
                             sessionManager.currentState == SessionState.ENTERING_REELS)) {
                            if (com.reeltrak.app.BuildConfig.DEBUG) {
                                Log.d("PipelineAudit", "8. Ledger invoked")
                            }

                            transitionDirectionForLedger = request.direction
                            transitionSnapshotForLedger = snapshot
                            try {
                                transitionLedger.process(snapshot)
                            } finally {
                                transitionDirectionForLedger = null
                                transitionSnapshotForLedger = null
                            }
                        }

                        evaluateStrictModeForSnapshot(snapshot, activePackage)

                        // Record that we have successfully analyzed up to this event time
                        lastEventProcessingTime = maxOf(lastEventProcessingTime, eventTime)
                    }
                } else {
                    inconclusiveSnapshotGate.reset()
                    strictEnforcementGate.observe(null)
                    cancelStrictEnforcementCycle(resetGate = false)
                    pipelineMutex.withLock {
                        // Not a supported app. Ensure session transitions to IDLE and overlay is hidden.
                        if (!isSystemOverlay(activePackage)) {
                            sessionManager.updateCurrentApp(activePackage)
                        }
                        overlayStateManager.evaluateVisibility(isOnReels = false, isSystemOverlay = false, isScreenReady = isScreenInteractiveAndUnlocked())
                    }
                }

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("PipelineAudit", "Pipeline analysis error: ${e.javaClass.simpleName}")
                return false
            } finally {
                val preRecycleIdentity = System.identityHashCode(rootNode)
                if (com.reeltrak.app.BuildConfig.DEBUG) {
                    Log.d("PipelineAudit", "9. Executing finally block, recycling rootNode [id: $preRecycleIdentity]")
                }
                try {
                    rootNode.recycle()
                } catch (e: Exception) {
                    Log.e("PipelineAudit", "rootNode.recycle() error: ${e.javaClass.simpleName}")
                }
            }
            return true
    }


    private fun strictModeAuthorizedNow(): Boolean =
        subscriptionManager.hasActiveEntitlementNow() &&
            (isStrictModeEnforcedInCurrentSession || isStrictModeEnabledCached)

    private fun strictLimitReachedNow(): Boolean {
        val state = overlayStateManager.state.value
        return state.dailyGoal > 0 && state.count >= state.dailyGoal
    }

    private fun evaluateStrictModeForSnapshot(snapshot: DetectionSnapshot, activePackage: String) {
        val inReelSession = sessionManager.currentState == SessionState.ACTIVE ||
            sessionManager.currentState == SessionState.PAUSED
        val surface = if (snapshot.isInternalOverlay && !inReelSession) null
            else StrictModeEnforcementPolicy.surfaceKey(snapshot)
        if (surface == null) {
            // Generic dialogs in a Reel session are not departure; unrelated overlays are.
            if (!snapshot.isInternalOverlay || !inReelSession) {
                strictEnforcementGate.observe(null)
                cancelStrictEnforcementCycle(resetGate = true)
            }
            return
        }
        if (strictModeShouldEnforce(snapshot)) {
            enforceStrictMode(surface, activePackage)
        }
    }

    private fun strictModeShouldEnforce(snapshot: DetectionSnapshot): Boolean {
        val state = overlayStateManager.state.value
        return StrictModeEnforcementPolicy.shouldEnforce(
            isEntitled = subscriptionManager.hasActiveEntitlementNow(),
            isStrictModeActive = isStrictModeEnforcedInCurrentSession || isStrictModeEnabledCached,
            dailyGoal = state.dailyGoal,
            count = state.count,
            snapshot = snapshot,
            isReelSessionActive = sessionManager.currentState == SessionState.ACTIVE ||
                sessionManager.currentState == SessionState.PAUSED
        )
    }

    private fun evaluateCurrentStrictModeSurface() {
        if (!isScreenInteractiveAndUnlocked() || !strictModeAuthorizedNow() || !strictLimitReachedNow()) return
        serviceScope.launch(Dispatchers.Default) {
            val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return@launch
            try {
                val packageName = root.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return@launch
                val detector = detectors.find { it.supports(packageName) }
                if (detector == null) {
                    strictEnforcementGate.observe(null)
                    return@launch
                }
                val snapshot = detectorAnalysis.run { detector.analyze(root) }
                evaluateStrictModeForSnapshot(snapshot, packageName)
            } catch (e: Exception) {
                Log.e("StrictMode", "Fresh enforcement evaluation failed: ${e.message}")
            } finally {
                root.recycle()
            }
        }
    }

    private fun enforceStrictMode(candidateSurface: String, candidatePackage: String) {
        if (!strictModeAuthorizedNow() || !strictLimitReachedNow()) return
        val token = strictEnforcementGate.tryBegin(
            candidateSurface,
            android.os.SystemClock.elapsedRealtime()
        ) ?: return
        var jobLaunched = false
        try {
            strictEnforcementJob = serviceScope.launch(Dispatchers.Default) {
                try {
                    if (!strictModeAuthorizedNow() || !strictLimitReachedNow()) return@launch
                    val currentRoot = try { rootInActiveWindow } catch (_: Exception) { null } ?: return@launch
                    val freshSurface: String?
                    val freshPackage: String
                    try {
                        freshPackage = currentRoot.packageName?.toString() ?: return@launch
                        if (freshPackage != candidatePackage) return@launch
                        val detector = detectors.find { it.supports(freshPackage) } ?: return@launch
                        val freshSnapshot = detectorAnalysis.run { detector.analyze(currentRoot) }
                        freshSurface = StrictModeEnforcementPolicy.surfaceKey(freshSnapshot)
                        if (freshSurface == null || !strictModeShouldEnforce(freshSnapshot)) return@launch
                    } finally {
                        currentRoot.recycle()
                    }

                    withContext(Dispatchers.Main.immediate) {
                        if (!strictModeAuthorizedNow() || !strictLimitReachedNow()) return@withContext
                        // Check the foreground package again on Main immediately before navigation.
                        val foregroundRoot = try { rootInActiveWindow } catch (_: Exception) { null } ?: return@withContext
                        val stillInCandidateApp = try {
                            foregroundRoot.packageName?.toString() == freshPackage
                        } finally {
                            foregroundRoot.recycle()
                        }
                        if (!stillInCandidateApp) return@withContext

                        var escalatedToHome = false
                        val performed = strictEnforcementGate.performOnce(
                            freshSurface,
                            token,
                            android.os.SystemClock.elapsedRealtime()
                        ) {
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (lastToastTimeMs == -1L || now - lastToastTimeMs >= TOAST_COOLDOWN_MS) {
                                lastToastTimeMs = now
                                android.widget.Toast.makeText(
                                    this@ReelTrackerService,
                                    "Daily Reel limit reached. Strict Mode is active.",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }

                            if (lastEnforcedSurface == freshSurface) {
                                consecutiveEnforcementAttempts++
                            } else {
                                lastEnforcedSurface = freshSurface
                                consecutiveEnforcementAttempts = 1
                            }

                            if (consecutiveEnforcementAttempts >= MAX_CONSECUTIVE_BACK_ATTEMPTS) {
                                Log.w("StrictMode", "Consecutive BACK attempts ($consecutiveEnforcementAttempts) failed on surface $freshSurface; escalating to GLOBAL_ACTION_HOME")
                                consecutiveEnforcementAttempts = 0
                                lastEnforcedSurface = null
                                escalatedToHome = true
                                performGlobalAction(GLOBAL_ACTION_HOME)
                            } else {
                                performGlobalAction(GLOBAL_ACTION_BACK)
                            }
                        } ?: return@withContext
                        if (!performed) Log.w("StrictMode", "Global navigation action was rejected; waiting for a fresh surface before another attempt")
                        // Back can be consumed by an IME or leave the user in another Reel.
                        // A rate-limited delayed tree recheck recovers, while the gate bounds the
                        // same-surface follow-up and the normal path revalidates policy state.
                        strictModeRecheckJob?.cancel()
                        if (!escalatedToHome) {
                            strictModeRecheckJob = serviceScope.launch(Dispatchers.Default) {
                                delay(strictEnforcementGate.sameSurfaceCooldownMs)
                                strictModeRecheckJob = null
                                if (!isScreenInteractiveAndUnlocked()) return@launch
                                evaluateCurrentStrictModeSurface()
                            }
                        } else {
                            strictModeRecheckJob = null
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("StrictMode", "Enforcement cycle failed: ${e.message}")
                } finally {
                    strictEnforcementGate.finish(token)
                }
            }
            jobLaunched = true
        } finally {
            if (!jobLaunched) {
                strictEnforcementGate.finish(token)
            }
        }
    }

    private fun cancelStrictEnforcementCycle(resetGate: Boolean) {
        strictEnforcementJob?.cancel()
        strictEnforcementJob = null
        strictModeRecheckJob?.cancel()
        strictModeRecheckJob = null
        if (resetGate) {
            strictEnforcementGate.reset()
            consecutiveEnforcementAttempts = 0
            lastEnforcedSurface = null
        }
    }

    private fun incrementProductionCount(): TransitionAcceptance {
        if (inAppUpdateManager.isUpdateRequired.value) {
            Log.w("ReelTrakEngine", "incrementProductionCount blocked: Mandatory update is required.")
            return TransitionAcceptance.REJECTED
        }
        // Forward-only counting: a deliberate backward-scroll suppression consumes the
        // transition just as it did before, but does not dispatch a daily/session increment.
        if (transitionDirectionForLedger?.direction == ScrollDirection.BACKWARD) {
            Log.d("ReelTrakEngine", "Increment suppressed: Backward scroll detected.")
            return TransitionAcceptance.SUPPRESSED
        }
        val mode = appAuthorizationManager.getOperationalMode()
        if (mode == com.reeltrak.app.data.security.authorization.OperationalMode.LOCKED_OR_EXPIRED) {
            Log.w("ReelTrakEngine", "incrementProductionCount blocked: App is locked or tampered.")
            return TransitionAcceptance.REJECTED
        }
        val token = appAuthorizationManager.activeToken
        val snapshot = transitionSnapshotForLedger ?: return TransitionAcceptance.REJECTED
        val rawFingerprint = snapshot.fingerprint
        val fingerprint = if (rawFingerprint.platform.isEmpty() && snapshot.platform.isNotEmpty() && snapshot.platform != "Unknown") {
            rawFingerprint.copy(platform = snapshot.platform)
        } else {
            rawFingerprint
        }
        val durableAcceptance = dayManager.acceptReelTransition(fingerprint, token)
        if (durableAcceptance == com.reeltrak.app.services.tracking.ReelCountAcceptance.REJECTED) {
            Log.w("ReelTrakEngine", "incrementProductionCount rejected by DayManager; transition remains uncommitted.")
            return TransitionAcceptance.REJECTED
        }

        if (durableAcceptance == com.reeltrak.app.services.tracking.ReelCountAcceptance.ALREADY_ACCEPTED) {
            Log.i("ReelTrakEngine", "Transition was already durably accepted by Room; committing ledger state without another count.")
            return TransitionAcceptance.ALREADY_ACCEPTED
        }

        Log.e("ReelTrakEngine", "V3_ENGINE: Legitimate Reel Count accepted in mode $mode!")
        if (com.reeltrak.app.BuildConfig.DEBUG) {
            Log.d("COUNTER_DIAG", "[POINT_1_INCREMENT_DISPATCHED] Legitimate reel increment dispatched, sessionReelsBefore=${sessionManager.reelsCount}, thread=${Thread.currentThread().name}")
        }
        // DayManager returns only after the Room count and idempotency marker commit atomically.
        return TransitionAcceptance.ACCEPTED
    }

    private fun applyCommittedTransition(acceptance: TransitionAcceptance) {
        if (acceptance == TransitionAcceptance.SUPPRESSED) {
            transitionDirectionForLedger?.let { directionManager.onBackwardTransitionConsumed(it.revision) }
            return
        }
        if (acceptance == TransitionAcceptance.ALREADY_ACCEPTED) return
        check(acceptance == TransitionAcceptance.ACCEPTED)

        val token = appAuthorizationManager.activeToken
        try {
            sessionManager.incrementReel(token)
            // Update the session and optimistic overlay only after the ledger commits the accepted
            // transition. If this post-commit work fails, the ledger remains consumed to avoid a
            // duplicate DayManager enqueue on the next accessibility event.
            overlayStateManager.updateState { currentState ->
                val newCount = currentState.count + 1
                currentState.copy(
                    count = newCount,
                    isOverLimit = (currentState.dailyGoal > 0 && newCount >= currentState.dailyGoal)
                )
            }
        } catch (failure: Exception) {
            Log.e("ReelTrakEngine", "Count was accepted by DayManager but session/overlay update failed; not retrying to avoid a duplicate.", failure)
        }
        Log.d("PipelineAudit", "incrementProductionCount called. New reelsCount=${sessionManager.reelsCount}")
    }

    private fun handleSessionStateChange(oldState: SessionState, newState: SessionState, completedSession: SessionData? = null) {
        Log.d("SessionDebug", "handleSessionStateChange: $oldState -> $newState")
        Log.d("STARTUP_DEBUG", "ReelTrackerService.handleSessionStateChange: $oldState -> $newState")
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

                // Freeze Strict Mode enforcement for the duration of this session
                isStrictModeEnforcedInCurrentSession = isStrictModeEnabledCached && subscriptionManager.hasActiveEntitlementNow()

                startWatchdog()
            }
            SessionState.EXITING -> {
                stopWatchdog()
                overlayStateManager.evaluateVisibility(isOnReels = false, isSystemOverlay = false, isScreenReady = isScreenInteractiveAndUnlocked())
            }
            SessionState.IDLE -> {
                stopWatchdog()

                lastScrollFromIndex = -1
                wasOverlayOpenLastFrame = false
                directionManager.reset()

                hideJob?.cancel()
                overlayStateManager.evaluateVisibility(isOnReels = false, isSystemOverlay = false, isScreenReady = isScreenInteractiveAndUnlocked())

                if (completedSession != null) {
                    saveSessionToDb(completedSession)
                }
                transitionLedger.clearActive()
            }
            SessionState.DESTROYED -> {
                // Clear active focal reel reference while preserving 10-entry deduplication history
                // in persistent storage across service recreation.
                transitionLedger.clearActive()
                if (completedSession != null) {
                    saveSessionToDb(completedSession)
                }
                // Destroy synchronously to guarantee execution before serviceScope is cancelled
                // (onDestroy runs on the companion thread)
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



    private fun startWatchdog() {
        stopWatchdog()
        watchdogJob = serviceScope.launch {
            // Give the system a brief moment to settle before the first check
            delay(500)
            while (isActive) {
                try {
                    pipelineMutex.withLock {
                        val rootNode = rootInActiveWindow
                        try {
                            val activePackage = rootNode?.packageName?.toString()
                                ?: windows.firstOrNull { it.isFocused || it.isActive }?.root?.packageName?.toString()

                            // Check if we are still in a tracked app or system overlay
                            val isTracked = sessionManager.isTrackedApp(activePackage)
                            val isSystemOverlay = isSystemOverlay(activePackage)

                            if (!isTracked && !isSystemOverlay) {
                                Log.w("ReelCounter-Debug", "[Watchdog] Inconsistency detected! Overlay is active but foreground app is $activePackage. Synchronizing state.")
                                // Force state synchronization
                                sessionManager.updateCurrentApp(activePackage)
                                overlayStateManager.evaluateVisibility(isOnReels = false, isSystemOverlay = false, isScreenReady = isScreenInteractiveAndUnlocked())
                            }
                        } finally {
                            rootNode?.recycle()
                        }
                    }
                } catch (e: Exception) {
                    // Ignore exceptions
                }
                // Check every 600ms while active to ensure fast response when user backgrounds app
                delay(600)
            }
        }
    }

    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    private fun saveSessionToDb(sessionToSave: SessionData) {
        if (sessionToSave.reelsScrolled <= 0) return
        CoroutineScope(Dispatchers.IO + NonCancellable).launch {
            try {
                val token = appAuthorizationManager.activeToken
                dayManager.appendSession(sessionToSave, token)
                Log.d("ReelTrakEngine", "Saved session to DB: $sessionToSave")
            } catch (e: Exception) {
                Log.e("ReelTrakEngine", "Error saving session to DB", e)
            }
        }
    }

    private fun saveSessionToDb() {
        val snapshot = sessionManager.extractCurrentSessionSnapshot(resetCount = false)
        if (snapshot != null) {
            saveSessionToDb(snapshot)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("OverlayDebug", "OverlayService destroyed")
        cancelStrictEnforcementCycle(resetGate = true)
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) { }

        sessionManager.transitionTo(SessionState.DESTROYED)
        processingQueue?.close()
        processingQueue = null
        serviceScope.cancel()
    }

    override fun onInterrupt() {
        cancelStrictEnforcementCycle(resetGate = true)
        serviceScope.launch {
            pipelineMutex.withLock {
                sessionManager.transitionTo(SessionState.IDLE)
                transitionLedger.clearActive()
                directionManager.reset()
            }
        }
    }

    private fun scheduleOverlaySynchronization() {
        overlaySyncJob?.cancel()
        overlaySyncJob = serviceScope.launch(Dispatchers.Default) {
            delay(EVENT_DEBOUNCE_MS + 20L)
            // Route the fallback through the same single consumer as ordinary event triggers.
            // If analysis is still running, the conflated queue retains this latest-state request.
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastEventProcessingTime >= EVENT_DEBOUNCE_MS) {
            processingQueue?.request(TrackingAnalysisRequest(now, directionManager.snapshot()))
            }
        }
    }
}
