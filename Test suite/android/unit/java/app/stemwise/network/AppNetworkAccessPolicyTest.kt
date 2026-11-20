package app.stemwise.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test

class AppNetworkAccessPolicyTest {
    @Test
    fun policyCancellationPreservesTheExactBlockingWorkersRetirementFailure() = runBlocking {
        val access = FakeNetworkAccess(ALLOWED)
        val started = CompletableDeferred<Unit>()
        val released = java.util.concurrent.CountDownLatch(1)
        val fatal = app.stemwise.playback.engine.WorkerRetirementException("TOOL_PROCESS_RETIREMENT_FAILED")
        val operation = async {
            runCatching {
                access.whileUnmetered {
                    app.stemwise.integration.runCancellableBlockingOperation(cancelExact = { released.countDown() }) {
                        started.complete(Unit)
                        try {
                            check(released.await(TEST_HANG_GUARD_MS, java.util.concurrent.TimeUnit.MILLISECONDS))
                        } catch (_: InterruptedException) {
                            check(released.count == 0L)
                        }
                        throw fatal
                    }
                }
            }
        }
        try {
            withTimeout(TEST_HANG_GUARD_MS) { started.await() }
            access.mutableState.value = ALLOWED.copy(activeNetworkMetered = true)
            val failure = withTimeout(TEST_HANG_GUARD_MS) { operation.await().exceptionOrNull() }
            assertTrue(failure is app.stemwise.playback.engine.WorkerRetirementException)
            // Coroutine stack recovery may copy the exception while retaining its exact cause.
            assertSame(fatal, generateSequence(failure) { it.cause }.lastOrNull())
        } finally {
            released.countDown()
            operation.cancelAndJoin()
        }
    }

    @Test
    fun osUnmeteredCellularIsStillForbidden() {
        assertFalse(AppOwnedNetworkPolicy.allowsNetwork(ALLOWED.copy(
            activeNetworkWifi = false, activeNetworkCellular = true
        )))
    }

    @Test
    fun wifiCellularVpnAndUnknownTransportAreForbidden() {
        for (state in listOf(
            ALLOWED.copy(activeNetworkCellular = true),
            ALLOWED.copy(activeNetworkVpn = true),
            ALLOWED.copy(activeNetworkWifi = false)
        )) assertFalse("unexpected admission for $state", AppOwnedNetworkPolicy.allowsNetwork(state))
    }

    @Test
    fun policyTruthTableAllowsOnlyValidatedActiveUnmeteredNetworks() {
        for (active in listOf(false, true)) {
            for (validated in listOf(false, true)) {
                for (metered in listOf(false, true)) {
                    val state = AppNetworkState(hasActiveNetwork = active,
                        activeNetworkValidated = validated, activeNetworkMetered = metered,
                        activeNetworkWifi = true)
                    assertEquals("unexpected decision for $state", active && validated && !metered,
                        AppOwnedNetworkPolicy.allowsNetwork(state))
                }
            }
        }
    }

    @Test
    fun aPolicyChangeCancelsTheExactRunningOperationBeforeReturningTheWaitingError() =
        runBlocking {
            val access = FakeNetworkAccess(ALLOWED)
            val started = CompletableDeferred<Unit>()
            val exactCancellation = CompletableDeferred<Unit>()
            val operation = async {
                runCatching {
                    access.whileUnmetered {
                        started.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            exactCancellation.complete(Unit)
                        }
                    }
                }
            }

            try {
                withTimeout(TEST_HANG_GUARD_MS) { started.await() }
                access.mutableState.value = ALLOWED.copy(activeNetworkMetered = true)

                val error = withTimeout(TEST_HANG_GUARD_MS) {
                    operation.await().exceptionOrNull()
                }
                withTimeout(TEST_HANG_GUARD_MS) { exactCancellation.await() }
                assertTrue(error is UnmeteredNetworkRequiredException)
                assertTrue((error as UnmeteredNetworkRequiredException).operationStarted)
                assertEquals(WAITING_FOR_UNMETERED_NETWORK, error.message)
            } finally {
                operation.cancelAndJoin()
            }
        }

    @Test
    fun aBlockedLaunchNeverEntersTheNetworkOperation() = runBlocking {
        val access = FakeNetworkAccess(
            ALLOWED.copy(
                activeNetworkMetered = true,
            )
        )
        var entered = false

        val error = runCatching {
            access.whileUnmetered {
                entered = true
            }
        }.exceptionOrNull()

        assertFalse(entered)
        assertTrue(error is UnmeteredNetworkRequiredException)
        assertFalse((error as UnmeteredNetworkRequiredException).operationStarted)
    }

    @Test
    fun aDifferentWifiIdentityCancelsTheOldLeaseWithoutSpendingRetryState() = runBlocking {
        val access = FakeNetworkAccess(ALLOWED.copy(activeNetworkHandle = 101L))
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val operation = async {
            runCatching {
                access.whileUnmetered {
                    entered.complete(Unit)
                    try { awaitCancellation() } finally { stopped.complete(Unit) }
                }
            }
        }
        try {
            withTimeout(TEST_HANG_GUARD_MS) { entered.await() }
            access.mutableState.value = ALLOWED.copy(activeNetworkHandle = 102L)
            val failure = withTimeout(TEST_HANG_GUARD_MS) { operation.await().exceptionOrNull() }
            withTimeout(TEST_HANG_GUARD_MS) { stopped.await() }
            assertTrue(failure is UnmeteredNetworkRequiredException)
        } finally { operation.cancelAndJoin() }
    }

    private class FakeNetworkAccess(initial: AppNetworkState) : AppNetworkAccess {
        val mutableState = MutableStateFlow(initial)
        override val state: StateFlow<AppNetworkState> = mutableState
        override fun currentState(): AppNetworkState = mutableState.value
    }

    private companion object {
        const val TEST_HANG_GUARD_MS = 5_000L
        val ALLOWED = AppNetworkState(
            hasActiveNetwork = true,
            activeNetworkValidated = true,
            activeNetworkMetered = false,
            activeNetworkWifi = true
        )
    }
}
