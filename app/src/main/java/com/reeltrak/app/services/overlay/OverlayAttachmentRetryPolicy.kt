package com.reeltrak.app.services.overlay

/**
 * Small, Android-free state machine for bounding retries when a system overlay cannot attach.
 * The first attempt is immediate, followed by two event-triggered retries with backoff. After
 * that, another service connection (or explicit detach/reattach) starts a fresh retry window.
 */
internal class OverlayAttachmentRetryPolicy(
    private val retryDelaysMs: List<Long> = listOf(500L, 1_500L)
) {
    enum class State { DETACHED, ATTACHING, ATTACHED, DESTROYED }

    @Volatile
    var state: State = State.DETACHED
        private set

    private var failedAttempts = 0
    private var nextRetryAtMs = 0L

    @Synchronized
    fun beginAttempt(nowMs: Long): Boolean {
        if (state != State.DETACHED || failedAttempts > retryDelaysMs.size || nowMs < nextRetryAtMs) return false
        state = State.ATTACHING
        return true
    }

    @Synchronized
    fun attachmentSucceeded() {
        if (state != State.ATTACHING) return
        state = State.ATTACHED
        failedAttempts = 0
        nextRetryAtMs = 0L
    }

    @Synchronized
    fun attachmentFailed(nowMs: Long) {
        if (state != State.ATTACHING) return
        state = State.DETACHED
        failedAttempts++
        val delayIndex = failedAttempts - 1
        nextRetryAtMs = if (delayIndex < retryDelaysMs.size) {
            nowMs + retryDelaysMs[delayIndex].coerceAtLeast(0L)
        } else {
            Long.MAX_VALUE
        }
    }

    /** Called when Android detached the window outside this manager. */
    @Synchronized
    fun windowDetached() {
        if (state == State.ATTACHED) {
            state = State.DETACHED
            nextRetryAtMs = 0L
        }
    }

    /** Explicit detach/reattach and service reconnection open a bounded retry window again. */
    @Synchronized
    fun allowLifecycleRetry() {
        if (state == State.DETACHED) {
            failedAttempts = 0
            nextRetryAtMs = 0L
        }
    }

    @Synchronized
    fun detached() {
        if (state == State.DESTROYED) return
        state = State.DETACHED
        failedAttempts = 0
        nextRetryAtMs = 0L
    }

    @Synchronized
    fun destroy() {
        state = State.DESTROYED
        nextRetryAtMs = Long.MAX_VALUE
    }
}
