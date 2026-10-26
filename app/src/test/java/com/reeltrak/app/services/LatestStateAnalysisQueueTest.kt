package com.reeltrak.app.services

import com.reeltrak.app.services.accessibility.snapshot.DirectionStateSnapshot
import com.reeltrak.app.services.accessibility.snapshot.ScrollDirection
import com.reeltrak.app.services.tracking.engine.model.DetectionSnapshot
import com.reeltrak.app.services.tracking.engine.model.SemanticFingerprint
import com.reeltrak.app.services.tracking.engine.model.SnapshotClassification
import com.reeltrak.app.services.tracking.InconclusiveSnapshotGate
import com.reeltrak.app.services.tracking.engine.state.TransitionLedger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class LatestStateAnalysisQueueTest {

    @Test
    fun `coalescing preserves direction evidence belonging to newest trigger`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val latestFinished = CompletableDeferred<TrackingAnalysisRequest>()
        val analyzed = Collections.synchronizedList(mutableListOf<TrackingAnalysisRequest>())
        val queue = LatestStateAnalysisQueue<TrackingAnalysisRequest>(scope) { request ->
            analyzed += request
            if (request.eventTime == 1L) {
                firstStarted.complete(Unit)
                releaseFirst.await()
            } else {
                latestFinished.complete(request)
            }
            true
        }
        val first = TrackingAnalysisRequest(1L, DirectionStateSnapshot(ScrollDirection.FORWARD, 1L, 1L))
        val overwritten = TrackingAnalysisRequest(2L, DirectionStateSnapshot(ScrollDirection.BACKWARD, 1L, 2L))
        val latest = TrackingAnalysisRequest(3L, DirectionStateSnapshot(ScrollDirection.FORWARD, 2L, 3L))

        try {
            queue.start()
            assertTrue(queue.request(first))
            firstStarted.await()
            assertTrue(queue.request(overwritten))
            assertTrue(queue.request(latest))
            releaseFirst.complete(Unit)
            assertEquals(latest, withTimeout(2_000L) { latestFinished.await() })
            assertEquals(listOf(first, latest), analyzed)
        } finally {
            queue.close()
            scope.cancel()
        }
    }

    @Test
    fun `inconclusive detector result is retried without another accessibility event`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = AtomicInteger()
        val confirmedDeparture = CompletableDeferred<Unit>()
        val gate = InconclusiveSnapshotGate()
        val inconclusive = DetectionSnapshot(
            timestamp = 50L,
            classification = SnapshotClassification.INCONCLUSIVE,
            platform = "Instagram"
        )
        val queue = LatestStateAnalysisQueue<Long>(scope, retryDelayMs = 10L) { eventTime ->
            calls.incrementAndGet()
            val resolved = gate.resolve(inconclusive, eventTime)
            if (resolved == null) false else {
                assertEquals(SnapshotClassification.DISCARDED, resolved.classification)
                confirmedDeparture.complete(Unit)
                true
            }
        }

        try {
            queue.start()
            assertTrue(queue.request(50L))
            withTimeout(2_000L) { confirmedDeparture.await() }
            assertEquals("One request must trigger the bounded fresh analysis", 2, calls.get())
        } finally {
            queue.close()
            scope.cancel()
        }
    }

    @Test
    fun `event received during analysis is retained and replayed`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondFinished = CompletableDeferred<Unit>()
        val processed = Collections.synchronizedList(mutableListOf<Long>())
        val queue = LatestStateAnalysisQueue<Long>(scope, retryDelayMs = 10L) { eventTime ->
            processed += eventTime
            if (eventTime == 1L) {
                firstStarted.complete(Unit)
                releaseFirst.await()
            } else {
                secondFinished.complete(Unit)
            }
            true
        }

        try {
            queue.start()
            assertTrue(queue.request(1L))
            firstStarted.await()
            assertTrue(queue.request(2L))
            releaseFirst.complete(Unit)
            withTimeout(2_000L) { secondFinished.await() }
            assertEquals(listOf(1L, 2L), processed)
        } finally {
            queue.close()
            scope.cancel()
        }
    }

    @Test
    fun `multiple events during one analysis coalesce to the latest state`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondFinished = CompletableDeferred<Unit>()
        val processed = Collections.synchronizedList(mutableListOf<Long>())
        val queue = LatestStateAnalysisQueue<Long>(scope, retryDelayMs = 10L) { eventTime ->
            processed += eventTime
            if (eventTime == 10L) {
                firstStarted.complete(Unit)
                releaseFirst.await()
            } else {
                secondFinished.complete(Unit)
            }
            true
        }

        try {
            queue.start()
            queue.request(10L)
            firstStarted.await()
            queue.request(11L)
            queue.request(12L)
            queue.request(13L)
            releaseFirst.complete(Unit)
            withTimeout(2_000L) { secondFinished.await() }
            assertEquals(listOf(10L, 13L), processed)
        } finally {
            queue.close()
            scope.cancel()
        }
    }

    @Test
    fun `analysis exception gets one bounded retry`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = AtomicInteger()
        val retryCompleted = CompletableDeferred<Unit>()
        val queue = LatestStateAnalysisQueue<Long>(
            scope = scope,
            retryDelayMs = 10L,
        ) {
            if (calls.incrementAndGet() == 1) throw IllegalStateException("transient tree failure")
            retryCompleted.complete(Unit)
            true
        }

        try {
            queue.start()
            queue.request(20L)
            withTimeout(2_000L) { retryCompleted.await() }
            assertEquals(2, calls.get())
        } finally {
            queue.close()
            scope.cancel()
        }
    }

    @Test
    fun `persistent analysis exception stops after the bounded retry`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = AtomicInteger()
        val exhausted = CompletableDeferred<Unit>()
        val queue = LatestStateAnalysisQueue<Long>(
            scope = scope,
            retryDelayMs = 10L,
            onFailure = { failure -> if (failure == null) exhausted.complete(Unit) }
        ) {
            calls.incrementAndGet()
            throw IllegalStateException("persistent tree failure")
        }

        try {
            queue.start()
            queue.request(25L)
            withTimeout(2_000L) { exhausted.await() }
            delay(30L)
            assertEquals(2, calls.get())
        } finally {
            queue.close()
            scope.cancel()
        }
    }

    @Test
    fun `cancellation requeues consumed trigger and restarts worker`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = AtomicInteger()
        val completed = CompletableDeferred<Unit>()
        val queue = LatestStateAnalysisQueue<Long>(scope, retryDelayMs = 10L) {
            if (calls.incrementAndGet() == 1) throw CancellationException("simulated worker interruption")
            completed.complete(Unit)
            true
        }

        try {
            queue.start()
            queue.request(30L)
            withTimeout(2_000L) { completed.await() }
            assertEquals(2, calls.get())
        } finally {
            queue.close()
            scope.cancel()
        }
    }

    @Test
    fun `cancellation during retry restores newer pending event`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = AtomicInteger()
        val latestRequested = AtomicLong(70L)
        val retryStarted = CompletableDeferred<Long>()
        val newerAnalyzed = CompletableDeferred<Long>()
        lateinit var queue: LatestStateAnalysisQueue<Long>
        queue = LatestStateAnalysisQueue(scope, retryDelayMs = 10L) { eventTime ->
            when (calls.incrementAndGet()) {
                1 -> {
                    latestRequested.set(71L)
                    queue.request(71L)
                    false
                }
                2 -> {
                    retryStarted.complete(eventTime)
                    throw CancellationException("cancel during retry")
                }
                else -> {
                    if (eventTime < latestRequested.get()) true
                    else {
                        newerAnalyzed.complete(eventTime)
                        true
                    }
                }
            }
        }

        try {
            queue.start()
            queue.request(70L)
            assertEquals(70L, withTimeout(2_000L) { retryStarted.await() })
            assertEquals(71L, withTimeout(2_000L) { newerAnalyzed.await() })
            assertEquals(3, calls.get())
        } finally {
            queue.close()
            scope.cancel()
        }
    }

    @Test
    fun `two worker failures recover pending event without a new request and back off`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = AtomicInteger()
        val recoveryDelays = Channel<Long>(Channel.UNLIMITED)
        val allowFirstRecovery = CompletableDeferred<Unit>()
        val allowSecondRecovery = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Long>()
        val processed = Collections.synchronizedList(mutableListOf<Long>())
        val queue = LatestStateAnalysisQueue<Long>(
            scope = scope,
            recoveryDelay = { delayMs ->
                recoveryDelays.send(delayMs)
                when (delayMs) {
                    100L -> allowFirstRecovery.await()
                    200L -> allowSecondRecovery.await()
                    else -> error("Unexpected recovery delay: $delayMs")
                }
            }
        ) { eventTime ->
            processed += eventTime
            when (calls.incrementAndGet()) {
                1, 2 -> throw CancellationException("unexpected worker cancellation")
                else -> {
                    completed.complete(eventTime)
                    true
                }
            }
        }

        try {
            queue.start()
            queue.request(32L)
            assertEquals(100L, withTimeout(2_000L) { recoveryDelays.receive() })
            assertEquals(1, calls.get())
            allowFirstRecovery.complete(Unit)
            assertEquals(200L, withTimeout(2_000L) { recoveryDelays.receive() })
            assertEquals(2, calls.get())
            allowSecondRecovery.complete(Unit)
            assertEquals(32L, withTimeout(2_000L) { completed.await() })
            delay(30L)
            assertEquals(3, calls.get())
            assertEquals(listOf(32L, 32L, 32L), processed)
        } finally {
            queue.close()
            scope.cancel()
        }
    }

    @Test
    fun `closing queue does not restart canceled in flight analysis`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val recoveryCalls = AtomicInteger()
        val queue = LatestStateAnalysisQueue<Long>(
            scope = scope,
            recoveryDelay = { delayMs ->
                recoveryCalls.incrementAndGet()
                delay(delayMs)
            }
        ) {
            calls.incrementAndGet()
            started.complete(Unit)
            kotlinx.coroutines.awaitCancellation()
        }

        try {
            queue.start()
            queue.request(35L)
            withTimeout(2_000L) { started.await() }
            queue.close()
            delay(50L)
            assertEquals(1, calls.get())
            assertEquals(0, recoveryCalls.get())
            assertFalse(queue.request(36L))
        } finally {
            queue.close()
            scope.cancel()
        }
    }

    @Test
    fun `new service queue can analyze current state after prior queue closes`() = runBlocking {
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firstQueue = LatestStateAnalysisQueue<Long>(firstScope) { true }
        firstQueue.start()
        firstQueue.close()
        firstScope.cancel()

        val nextScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val processed = CompletableDeferred<Long>()
        val nextQueue = LatestStateAnalysisQueue<Long>(nextScope) { eventTime ->
            processed.complete(eventTime)
            true
        }
        try {
            nextQueue.start()
            assertTrue(nextQueue.request(40L)) // onServiceConnected requests a fresh snapshot
            assertEquals(40L, withTimeout(2_000L) { processed.await() })
        } finally {
            nextQueue.close()
            nextScope.cancel()
        }
    }

    @Test
    fun `only one analysis runs at a time`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val queue = LatestStateAnalysisQueue<Long>(scope, retryDelayMs = 10L) {
            val current = active.incrementAndGet()
            maximumActive.updateAndGet { previous -> maxOf(previous, current) }
            try {
                if (firstStarted.complete(Unit)) releaseFirst.await()
                true
            } finally {
                active.decrementAndGet()
            }
        }

        try {
            queue.start()
            queue.request(50L)
            firstStarted.await()
            repeat(20) { queue.request(51L + it) }
            releaseFirst.complete(Unit)
            withTimeout(2_000L) {
                while (queue.isProcessing) delay(5L)
            }
            assertEquals(1, maximumActive.get())
        } finally {
            queue.close()
            scope.cancel()
        }
    }

    @Test
    fun `replayed snapshots of one reel increment ledger only once`() = runBlocking {
        val incrementCount = AtomicInteger()
        val ledger = TransitionLedger(onIncrement = { incrementCount.incrementAndGet() })
        val reel = DetectionSnapshot(
            classification = SnapshotClassification.COMMITTED,
            fingerprint = SemanticFingerprint("creator", "caption", "audio", "Instagram"),
            platform = "Instagram"
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val completed = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val queue = LatestStateAnalysisQueue<Long>(scope) { eventTime ->
            ledger.process(reel)
            if (eventTime == 60L) releaseFirst.await()
            else completed.complete(Unit)
            true
        }

        try {
            queue.start()
            queue.request(60L)
            while (!queue.isProcessing) delay(1L)
            queue.request(61L)
            releaseFirst.complete(Unit)
            withTimeout(2_000L) { completed.await() }
            assertEquals(1, incrementCount.get())
        } finally {
            queue.close()
            scope.cancel()
        }
    }

    @Test
    fun `detector analysis gate serializes regular and strict mode paths`() = runBlocking {
        val gate = SerializedDetectorAnalysis()
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()

        val regularPath = launch(Dispatchers.Default) {
            gate.run {
                val current = active.incrementAndGet()
                maximumActive.updateAndGet { previous -> maxOf(previous, current) }
                firstStarted.complete(Unit)
                try { releaseFirst.await() } finally { active.decrementAndGet() }
            }
        }
        withTimeout(2_000L) { firstStarted.await() }
        val strictModePath = launch(Dispatchers.Default) {
            gate.run {
                val current = active.incrementAndGet()
                maximumActive.updateAndGet { previous -> maxOf(previous, current) }
                secondStarted.complete(Unit)
                active.decrementAndGet()
            }
        }

        delay(20L)
        assertFalse(secondStarted.isCompleted)
        releaseFirst.complete(Unit)
        withTimeout(2_000L) { regularPath.join(); strictModePath.join() }
        assertTrue(secondStarted.isCompleted)
        assertEquals(1, maximumActive.get())
    }
}
