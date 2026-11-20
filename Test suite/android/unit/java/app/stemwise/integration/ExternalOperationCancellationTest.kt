package app.stemwise.integration

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalOperationCancellationTest {
    @Test fun retirementFailureSurvivesExternalAndCoroutineCancellationSettlement() = runBlocking {
        for (mode in listOf("none", "external", "coroutine")) {
            val entered = CompletableDeferred<Unit>()
            val observed = CompletableDeferred<Throwable>()
            val release = CountDownLatch(1)
            val executor = externalOperationExecutor("retirement-$mode")
            val external = ExternalOperationCancellation()
            val failure = app.stemwise.playback.engine.WorkerRetirementException("TOOL_PROCESS_RETIREMENT_FAILED")
            val owner = launch {
                try {
                    runCancellableBlockingOperation(cancelExact = {}, dispatcher = executor::execute,
                        externalCancellation = external) {
                        entered.complete(Unit)
                        try { check(release.await(2, TimeUnit.SECONDS)) } catch (_: InterruptedException) { }
                        throw failure
                    }
                    error("Retirement failure became success")
                } catch (error: Throwable) { observed.complete(error) }
            }
            try {
                withTimeout(TEST_HANG_GUARD_MS) { entered.await() }
                when (mode) {
                    "external" -> external.cancel()
                    "coroutine" -> owner.cancel()
                }
                release.countDown()
                assertTrue(withTimeout(TEST_HANG_GUARD_MS) { observed.await() } === failure)
                withTimeout(TEST_HANG_GUARD_MS) { owner.join() }
            } finally {
                release.countDown()
                owner.cancelAndJoin()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun providerMediaStreamsCannotBypassTheCancellableDescriptorBoundary() {
        val production = File(projectRoot(), "app/src/main/java")
        val files = listOf(production, File(projectRoot(), "audio-engine/src/main/java"))
            .flatMap { it.walkTopDown().filter { file -> file.isFile && file.extension == "kt" }.toList() }
        files.forEach { file ->
            val source = file.readText(Charsets.UTF_8)
            assertFalse("Direct provider stream in ${file.name}",
                Regex("contentResolver\\.open(?:Input|Output)Stream").containsMatchIn(source))
            assertFalse("Context media source bypass in ${file.name}",
                Regex("setDataSource\\((?:context|applicationContext),").containsMatchIn(source))
        }
        assertEquals(setOf("app/src/main/java/app/stemwise/integration/ExternalOperationCancellation.kt",
            "audio-engine/src/main/java/app/stemwise/integration/ProviderAssetOpening.kt"),
            files.filter { "openAssetFileDescriptor(" in it.readText(Charsets.UTF_8) }
                .map { it.relativeTo(projectRoot()).invariantSeparatorsPath }.toSet())
        val playlist = File(production, "app/stemwise/data/PlaylistRepository.kt").readText(Charsets.UTF_8)
        assertFalse("fun acquireOriginal" in playlist)
    }

    private fun projectRoot(): File {
        val working = File(checkNotNull(System.getProperty("user.dir")))
        return generateSequence(working) { it.parentFile }
            .firstOrNull { File(it, "app/src/main").isDirectory }
            ?: error("Could not locate StemWise project from ${working.absolutePath}")
    }

    @Test
    fun cancellationBeforeDispatchPreventsTheBlockingOperationFromStarting() = runBlocking {
        withTimeout(TEST_HANG_GUARD_MS) {
            var queued: Runnable? = null
            val operationRan = AtomicBoolean(false)
            val exactCancellationCount = AtomicInteger(0)
            val owner = launch(start = CoroutineStart.UNDISPATCHED) {
                runCancellableBlockingOperation(
                    cancelExact = { exactCancellationCount.incrementAndGet() },
                    dispatcher = { queued = it }
                ) {
                    operationRan.set(true)
                }
            }

            try {
                assertNotNull(queued)
                owner.cancelAndJoin()
                queued!!.run()

                assertFalse(operationRan.get())
                assertEquals(1, exactCancellationCount.get())
            } finally {
                owner.cancelAndJoin()
            }
        }
    }

    @Test
    fun cancelledQueuedOperationReleasesItsExternalCancellationAttachment() = runBlocking {
        withTimeout(TEST_HANG_GUARD_MS) {
            val cancellation = ExternalOperationCancellation()
            var queued: Runnable? = null
            val owner = launch(start = CoroutineStart.UNDISPATCHED) {
                runCancellableBlockingOperation(
                    cancelExact = {},
                    dispatcher = { queued = it },
                    externalCancellation = cancellation
                ) { error("cancelled operation started") }
            }
            owner.cancelAndJoin()
            queued!!.run()

            val replacementStop: () -> Unit = {}
            assertTrue(cancellation.attach(replacementStop))
            cancellation.detach(replacementStop)
        }
    }

    @Test
    fun alreadyCancelledCoroutineDoesNotRetainAnExternalCancellationAttachment() = runBlocking {
        val cancellation = ExternalOperationCancellation()
        val owner = launch(start = CoroutineStart.UNDISPATCHED) {
            coroutineContext[kotlinx.coroutines.Job]!!.cancel()
            runCancellableBlockingOperation(
                cancelExact = {},
                dispatcher = { it.run() },
                externalCancellation = cancellation
            ) { error("cancelled operation started") }
        }
        owner.join()
        val replacementStop: () -> Unit = {}
        assertTrue(cancellation.attach(replacementStop))
        cancellation.detach(replacementStop)
    }

    @Test
    fun ownerCancellationBeforeDispatchStopsTheQueuedExternalOperation() = runBlocking {
        withTimeout(TEST_HANG_GUARD_MS) {
            var queued: Runnable? = null
            val operationRan = AtomicBoolean(false)
            val exactCancellationCount = AtomicInteger(0)
            val observedFailure = CompletableDeferred<Throwable>()
            val cancellation = ExternalOperationCancellation()
            val owner = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    runCancellableBlockingOperation(
                        cancelExact = { exactCancellationCount.incrementAndGet() },
                        dispatcher = { queued = it },
                        externalCancellation = cancellation
                    ) {
                        operationRan.set(true)
                    }
                } catch (error: Throwable) {
                    observedFailure.complete(error)
                }
            }

            try {
                assertNotNull(queued)
                assertTrue(cancellation.cancel())
                assertFalse(cancellation.cancel())
                assertTrue(observedFailure.await() is CancellationException)
                queued!!.run()
                owner.join()

                assertFalse(operationRan.get())
                assertEquals(1, exactCancellationCount.get())
            } finally {
                owner.cancelAndJoin()
            }
        }
    }

    @Test
    fun finishedAttemptCannotCancelItsReplacementOwner() {
        val registry = ExternalOperationAttemptRegistry()
        val first = registry.prepare("source-song")
        registry.finish(first)
        val replacement = registry.prepare("source-song")

        assertTrue(registry.cancel("source-song"))
        assertTrue(runCatching { replacement.cancellation.requireActive() }
            .exceptionOrNull() is CancellationException)
        assertTrue(runCatching { first.cancellation.requireActive() }.isSuccess)
        registry.finish(replacement)
        assertFalse(registry.cancel("source-song"))
    }

    @Test
    fun cancellationInterruptsTheRunningThreadAndRemainsStructuredCancellation() = runBlocking {
        val executor = externalOperationExecutor("interrupt")
        val entered = CompletableDeferred<Unit>()
        val interrupted = CompletableDeferred<Unit>()
        val observedFailure = CompletableDeferred<Throwable>()
        val exactCancellationCount = AtomicInteger(0)
        val neverReleaseNormally = CountDownLatch(1)
        val owner = launch {
            try {
                runCancellableBlockingOperation(
                    cancelExact = { exactCancellationCount.incrementAndGet() },
                    dispatcher = executor::execute
                ) {
                    entered.complete(Unit)
                    try {
                        neverReleaseNormally.await()
                    } catch (interruption: InterruptedException) {
                        interrupted.complete(Unit)
                        throw interruption
                    }
                }
            } catch (error: Throwable) {
                observedFailure.complete(error)
                throw error
            }
        }

        try {
            withTimeout(TEST_HANG_GUARD_MS) { entered.await() }
            owner.cancel(CancellationException("network constraint lost"))
            withTimeout(TEST_HANG_GUARD_MS) { owner.join() }
            withTimeout(TEST_HANG_GUARD_MS) { interrupted.await() }

            assertEquals(1, exactCancellationCount.get())
            assertTrue(
                withTimeout(TEST_HANG_GUARD_MS) { observedFailure.await() } is CancellationException
            )
        } finally {
            neverReleaseNormally.countDown()
            owner.cancelAndJoin()
            shutdownAndAssertTerminated(executor)
        }
    }

    @Test
    fun cancellationRetiresWorkerWithoutLeakingItsInterruptOrClearingAnExistingOne() = runBlocking {
        for (previouslyInterrupted in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            val nextWorkInterrupt = CompletableDeferred<Boolean>()
            val observedFailure = CompletableDeferred<Throwable>()
            val release = AtomicBoolean(false)
            val exactCancellationCount = AtomicInteger(0)
            val cancellation = ExternalOperationCancellation()
            val callerInterruptPreserved = AtomicBoolean(false)
            var worker: Thread? = null
            val owner = launch {
                try {
                    runCancellableBlockingOperation(
                        cancelExact = { exactCancellationCount.incrementAndGet() },
                        externalCancellation = cancellation,
                        dispatcher = { command ->
                            worker = Thread({
                                if (previouslyInterrupted) Thread.currentThread().interrupt()
                                command.run()
                                // The next work runs directly: an executor must not clear the
                                // interrupt and conceal what the helper returned to its dispatcher.
                                nextWorkInterrupt.complete(Thread.currentThread().isInterrupted)
                            }, "stemwise-external-operation-test-interrupt-retirement").apply {
                                isDaemon = true
                                start()
                            }
                        }
                    ) {
                        entered.complete(Unit)
                        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TEST_HANG_GUARD_MS)
                        while (!release.get() && System.nanoTime() < deadline) Thread.yield()
                        check(release.get()) { "test did not release its owned worker" }
                    }
                } catch (error: Throwable) {
                    observedFailure.complete(error)
                    throw error
                }
            }
            var cancellingCaller: Thread? = null
            try {
                withTimeout(TEST_HANG_GUARD_MS) { entered.await() }
                cancellingCaller = Thread({
                    Thread.currentThread().interrupt()
                    try {
                        cancellation.cancel()
                        callerInterruptPreserved.set(Thread.currentThread().isInterrupted)
                    } finally { release.set(true) }
                }, "stemwise-external-operation-test-cancelling-caller").apply {
                    isDaemon = true
                    start()
                }
                withTimeout(TEST_HANG_GUARD_MS) { owner.join() }
                assertTrue(withTimeout(TEST_HANG_GUARD_MS) { observedFailure.await() } is CancellationException)
                assertTrue(owner.isCancelled)
                assertEquals(1, exactCancellationCount.get())
                assertTrue("cancellation cleared its caller's interrupt", callerInterruptPreserved.get())
                assertEquals("worker entry interrupt=$previouslyInterrupted", previouslyInterrupted,
                    withTimeout(TEST_HANG_GUARD_MS) { nextWorkInterrupt.await() })
            } finally {
                release.set(true)
                owner.cancelAndJoin()
                cancellingCaller?.join(TEST_THREAD_SHUTDOWN_MS)
                worker?.join(TEST_THREAD_SHUTDOWN_MS)
                assertFalse("cancelling caller did not retire", cancellingCaller?.isAlive == true)
                assertFalse("operation worker did not retire", worker?.isAlive == true)
            }
        }
    }

    @Test
    fun successfulOperationDoesNotClearAnInterruptItIntroduced() = runBlocking {
        val nextWorkInterrupt = CompletableDeferred<Boolean>()
        var worker: Thread? = null
        try {
            val result = withTimeout(TEST_HANG_GUARD_MS) {
                runCancellableBlockingOperation(
                    cancelExact = { error("successful operation was cancelled") },
                    dispatcher = { command ->
                        worker = Thread({
                            command.run()
                            nextWorkInterrupt.complete(Thread.currentThread().isInterrupted)
                        }, "stemwise-external-operation-test-unowned-interrupt").apply {
                            isDaemon = true
                            start()
                        }
                    }
                ) {
                    Thread.currentThread().interrupt()
                    "completed"
                }
            }
            assertEquals("completed", result)
            assertTrue(withTimeout(TEST_HANG_GUARD_MS) { nextWorkInterrupt.await() })
        } finally {
            worker?.join(TEST_THREAD_SHUTDOWN_MS)
            assertFalse("operation worker did not retire", worker?.isAlive == true)
        }
    }

    @Test
    fun cancellationInsideTheExternalRegistrationGapCannotOrphanTheOperation() = runBlocking {
        val executor = externalOperationExecutor("registration-gap")
        val enteredRegistrationGap = CompletableDeferred<Unit>()
        val interruptedCleanup = CompletableDeferred<Unit>()
        val externalResourceRegistered = AtomicBoolean(false)
        val exactDestroyAttempts = AtomicInteger(0)
        val exactDestroyHits = AtomicInteger(0)
        val registrationGate = CountDownLatch(1)
        val owner = launch {
            runCancellableBlockingOperation(
                cancelExact = {
                    exactDestroyAttempts.incrementAndGet()
                    if (externalResourceRegistered.get()) {
                        exactDestroyHits.incrementAndGet()
                    }
                },
                dispatcher = executor::execute
            ) {
                enteredRegistrationGap.complete(Unit)
                try {
                    registrationGate.await()
                    externalResourceRegistered.set(true)
                } catch (interruption: InterruptedException) {
                    // Mirrors yt-dlp 0.18.1: if cancellation lands after ProcessBuilder.start but
                    // before its ID map publication, the interrupted runner destroys that Process.
                    interruptedCleanup.complete(Unit)
                    throw interruption
                }
            }
        }

        try {
            withTimeout(TEST_HANG_GUARD_MS) { enteredRegistrationGap.await() }
            owner.cancel(CancellationException("worker stopped before process registration"))
            withTimeout(TEST_HANG_GUARD_MS) { owner.join() }
            withTimeout(TEST_HANG_GUARD_MS) { interruptedCleanup.await() }

            assertEquals(1, exactDestroyAttempts.get())
            assertEquals(0, exactDestroyHits.get())
            assertFalse(externalResourceRegistered.get())
        } finally {
            registrationGate.countDown()
            owner.cancelAndJoin()
            shutdownAndAssertTerminated(executor)
        }
    }

    @Test
    fun cancellingAnOkHttpWaitCancelsThatExactCallAndPreservesCancellation() = runBlocking {
        val call = RecordingCall()
        val untouchedCall = RecordingCall()
        val observedFailure = CompletableDeferred<Throwable>()
        val owner = launch {
            try {
                call.executeCancellable { "unreachable" }
            } catch (error: Throwable) {
                observedFailure.complete(error)
                throw error
            }
        }

        try {
            withTimeout(TEST_HANG_GUARD_MS) { call.enqueued.await() }
            owner.cancel(CancellationException("data saver stopped the worker"))
            withTimeout(TEST_HANG_GUARD_MS) { owner.join() }

            assertEquals(1, call.cancelCount.get())
            assertEquals(0, untouchedCall.cancelCount.get())
            assertTrue(
                withTimeout(TEST_HANG_GUARD_MS) { observedFailure.await() } is CancellationException
            )
        } finally {
            owner.cancelAndJoin()
        }
    }

    @Test
    fun cancelledOkHttpConsumerUnwindsBeforeItsOwnerCanFinish() = runBlocking {
        val call = DeliveringCall()
        val consumerEntered = CompletableDeferred<Unit>()
        val releaseConsumer = CountDownLatch(1)
        val owner = launch {
            call.executeCancellable { response ->
                response.body!!.source()
                consumerEntered.complete(Unit)
                releaseConsumer.await()
            }
        }

        try {
            withTimeout(TEST_HANG_GUARD_MS) { consumerEntered.await() }
            owner.cancel(CancellationException("stop download"))
            withTimeout(TEST_HANG_GUARD_MS) {
                while (call.cancelCount.get() == 0 || !call.responseClosed.get()) {
                    kotlinx.coroutines.yield()
                }
            }
            assertFalse(owner.isCompleted)

            releaseConsumer.countDown()
            withTimeout(TEST_HANG_GUARD_MS) { owner.join() }

            assertEquals(1, call.cancelCount.get())
            assertTrue(call.responseClosed.get())
        } finally {
            releaseConsumer.countDown()
            owner.cancelAndJoin()
        }
    }

    @Test
    fun cancellationWaitsForAnInterruptIgnoringConsumerToUnwind() = runBlocking {
        val executor = externalOperationExecutor("quiescence")
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val unwound = AtomicBoolean(false)
        val owner = launch {
            runCancellableBlockingOperation(
                cancelExact = { stopped.complete(Unit) }, dispatcher = executor::execute
            ) {
                try {
                    entered.complete(Unit)
                    while (release.count > 0) {
                        try { release.await() } catch (_: InterruptedException) { }
                    }
                } finally { unwound.set(true) }
            }
        }
        try {
            withTimeout(TEST_HANG_GUARD_MS) { entered.await() }
            owner.cancel()
            withTimeout(TEST_HANG_GUARD_MS) { stopped.await() }
            kotlinx.coroutines.yield()
            assertFalse(owner.isCompleted)
            assertFalse(unwound.get())
            release.countDown()
            withTimeout(TEST_HANG_GUARD_MS) { owner.join() }
            assertTrue(unwound.get())
        } finally {
            release.countDown()
            owner.cancelAndJoin()
            shutdownAndAssertTerminated(executor)
        }
    }

    private fun externalOperationExecutor(scenario: String): ExecutorService =
        Executors.newSingleThreadExecutor { command ->
            Thread(command, "stemwise-external-operation-test-$scenario").apply {
                isDaemon = true
            }
        }

    private fun shutdownAndAssertTerminated(executor: ExecutorService) {
        executor.shutdownNow()
        assertTrue(
            "test-owned external-operation thread did not terminate",
            executor.awaitTermination(TEST_THREAD_SHUTDOWN_MS, TimeUnit.MILLISECONDS)
        )
    }

    private class RecordingCall : Call {
        val enqueued = CompletableDeferred<Unit>()
        val cancelCount = AtomicInteger(0)
        private val request = Request.Builder().url("https://mvsep.com/test").build()
        private var executed = false
        private var cancelled = false

        override fun request(): Request = request

        override fun execute(): Response = error("Synchronous execution is not expected")

        override fun enqueue(responseCallback: Callback) {
            executed = true
            enqueued.complete(Unit)
        }

        override fun cancel() {
            cancelled = true
            cancelCount.incrementAndGet()
        }

        override fun isExecuted(): Boolean = executed

        override fun isCanceled(): Boolean = cancelled

        override fun timeout(): Timeout = Timeout.NONE

        override fun clone(): Call = RecordingCall()
    }

    private class DeliveringCall : Call {
        val cancelCount = AtomicInteger(0)
        val responseClosed = AtomicBoolean(false)
        private val request = Request.Builder().url("https://mvsep.com/test").build()
        private var executed = false
        private var cancelled = false

        override fun request(): Request = request
        override fun execute(): Response = error("Synchronous execution is not expected")

        override fun enqueue(responseCallback: Callback) {
            executed = true
            val source = object : ForwardingSource(Buffer()) {
                override fun close() {
                    responseClosed.set(true)
                    super.close()
                }
            }.buffer()
            val body = object : ResponseBody() {
                override fun contentType() = null
                override fun contentLength() = -1L
                override fun source(): BufferedSource = source
            }
            responseCallback.onResponse(
                this,
                Response.Builder()
                    .request(request)
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body)
                    .build()
            )
        }

        override fun cancel() {
            cancelled = true
            cancelCount.incrementAndGet()
        }

        override fun isExecuted(): Boolean = executed
        override fun isCanceled(): Boolean = cancelled
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = DeliveringCall()
    }

    private companion object {
        const val TEST_HANG_GUARD_MS = 5_000L
        const val TEST_THREAD_SHUTDOWN_MS = 2_000L
    }
}
