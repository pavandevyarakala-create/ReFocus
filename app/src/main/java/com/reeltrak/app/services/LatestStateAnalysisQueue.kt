package com.reeltrak.app.services

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean

/** Serializes accessibility-tree reads while retaining only the newest pending UI state. */
internal class LatestStateAnalysisQueue<T : Any>(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val retryDelayMs: Long = 100L,
    private val onFailure: (Throwable?) -> Unit = {},
    private val recoveryDelay: suspend (Long) -> Unit = { delay(it) },
    private val analyze: suspend (T) -> Boolean
) {
    private val triggers = Channel<T>(Channel.CONFLATED)
    private val lock = Any()
    private val processing = AtomicBoolean(false)
    private var worker: Job? = null
    private var recoveryJob: Job? = null
    private var started = false
    private var recoveryFailureCount = 0
    @Volatile private var closed = false

    val isProcessing: Boolean
        get() = processing.get()

    fun start() {
        synchronized(lock) {
            if (closed) return
            started = true
            startWorkerLocked()
        }
    }

    /** A conflated channel bounds pending work to one latest-state trigger. */
    fun request(trigger: T): Boolean {
        synchronized(lock) {
            if (closed) return false
            val sent = triggers.trySend(trigger).isSuccess
            if (sent) {
                recoveryJob?.cancel()
                recoveryJob = null
                startWorkerLocked()
            }
            return sent
        }
    }

    /** Preserves the service's authorization-transition behavior of draining queued work. */
    fun clearPending() {
        synchronized(lock) {
            while (triggers.tryReceive().isSuccess) Unit
            recoveryJob?.cancel()
            recoveryJob = null
        }
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            triggers.close()
            recoveryJob?.cancel()
            recoveryJob = null
            worker?.cancel()
        }
    }

    private fun startWorkerLocked() {
        if (!started || closed || !scope.isActive || worker?.isActive == true) return

        val nextWorker = scope.launch(dispatcher) {
            var inFlightTrigger: T? = null
            try {
                for (trigger in triggers) {
                    inFlightTrigger = trigger
                    analyzeWithOneRetry(trigger)
                    inFlightTrigger = null
                    synchronized(lock) { recoveryFailureCount = 0 }
                }
            } finally {
                inFlightTrigger?.let(::restorePending)
            }
        }
        worker = nextWorker
        nextWorker.invokeOnCompletion { cause ->
            synchronized(lock) {
                if (worker === nextWorker) worker = null
                if (cause != null && !closed && scope.isActive) scheduleRecoveryLocked()
            }
        }
    }

    private fun scheduleRecoveryLocked() {
        if (closed || !scope.isActive || recoveryJob?.isActive == true) return

        recoveryFailureCount = (recoveryFailureCount + 1).coerceAtMost(MAX_RECOVERY_BACKOFF_STEP)
        val delayMs = BASE_RECOVERY_DELAY_MS shl (recoveryFailureCount - 1)
        val nextRecovery = scope.launch(dispatcher, start = CoroutineStart.LAZY) {
            recoveryDelay(delayMs)
            val thisRecoveryJob = currentCoroutineContext()[Job]
            synchronized(lock) {
                if (recoveryJob === thisRecoveryJob) {
                    recoveryJob = null
                    startWorkerLocked()
                }
            }
        }
        recoveryJob = nextRecovery
        nextRecovery.start()
    }

    private suspend fun analyzeWithOneRetry(firstTrigger: T) {
        val trigger = firstTrigger
        repeat(2) { attempt ->
            val succeeded = try {
                processing.set(true)
                analyze(trigger)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                onFailure(failure)
                false
            } finally {
                processing.set(false)
            }

            if (succeeded) return
            if (attempt == 1) {
                onFailure(null)
                return
            }

            // A single delayed retry avoids a tight loop. Keep newer triggers in the channel:
            // cancellation recovery can then restore that newer trigger instead of this stale one.
            delay(retryDelayMs)
        }
    }

    private fun restorePending(trigger: T) {
        synchronized(lock) {
            if (closed || !scope.isActive) return
            // Requests use the same lock, so a newer queued event wins over the canceled in-flight one.
            val latestPending = triggers.tryReceive().getOrNull()
            triggers.trySend(latestPending ?: trigger)
        }
    }

    private companion object {
        const val BASE_RECOVERY_DELAY_MS = 100L
        const val MAX_RECOVERY_BACKOFF_STEP = 6
    }
}
