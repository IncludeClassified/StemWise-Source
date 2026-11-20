package app.stemwise.playback

import app.stemwise.data.RootAuthority
import app.stemwise.playback.engine.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class AutomaticQueuePrewarmOwnerTest {
    @Test fun nonDefaultTargetWarmsItsOriginalWithoutAuthorizingNeutralAutomaticAudio() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sink = Sink()
        try {
            AutomaticQueuePrewarmOwner(scope, sink).update(request().copy(automaticSongIds = emptySet()))
            assertNull("Edited controls cannot become a neutral automatic handoff", sink.plans.last())
            assertEquals(next, sink.predictions.last()?.identity)
        } finally { scope.cancel() }
    }

    @Test fun sameAudioMetadataRefreshKeepsTheFlightAndPublishesLatestMetadata() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val sink = Sink()
        var verifications = 0
        sink.verifier = { identity ->
            verifications++; entered.complete(Unit)
            withTimeout(2_000) { release.await() }
            item(identity, "verified-old")
        }
        val owner = AutomaticQueuePrewarmOwner(scope, sink)
        val request = request()
        try {
            owner.update(request)
            withTimeout(2_000) { entered.await() }
            val invalidations = sink.plans.size
            sink.latest = "new title and cover"
            owner.update(request.copy())
            assertEquals(invalidations, sink.plans.size)
            assertEquals(1, sink.metadataRefreshes)
            release.complete(Unit)
            withTimeout(2_000) { while (sink.plans.lastOrNull() !is NativeEngineAutomaticEndPlan.NextOwner) yield() }
            val plan = sink.plans.last() as NativeEngineAutomaticEndPlan.NextOwner
            assertEquals(next, plan.item.identity)
            assertEquals("new title and cover", plan.item.metadata.payloadKey)
            assertEquals(1, verifications)
        } finally { release.complete(Unit); scope.cancel() }
    }

    @Test fun changedRootRetiresOldFlightAndLateVerificationCannotPublishIt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>()
        val oldFinished = CompletableDeferred<Unit>()
        val sink = Sink()
        var calls = 0
        sink.verifier = { identity ->
            if (++calls == 1) withContext(NonCancellable) {
                withTimeout(2_000) { release.await() }
                oldFinished.complete(Unit)
            }
            item(identity, "verified")
        }
        val owner = AutomaticQueuePrewarmOwner(scope, sink)
        try {
            owner.update(request())
            owner.update(request().copy(root = RootAuthority("B", "content://B", 2)))
            val accepted = sink.plans.last()
            assertTrue(accepted is NativeEngineAutomaticEndPlan.NextOwner)
            val count = sink.plans.size
            release.complete(Unit)
            withTimeout(2_000) { oldFinished.await() }; yield()
            assertEquals(count, sink.plans.size)
            assertSame(accepted, sink.plans.last())
        } finally { release.complete(Unit); scope.cancel() }
    }

    @Test fun verifierCannotSubstituteDifferentAudioForTheFrozenCandidate() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sink = Sink().apply { verifier = { item(it.copy(audioRevision = "changed"), "wrong") } }
        try {
            AutomaticQueuePrewarmOwner(scope, sink).update(request())
            assertNull(sink.plans.last())
        } finally { scope.cancel() }
    }

    @Test fun availabilityRefreshRetriesTheSameUnavailableAudioWithoutDiscardingTheRequest() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sink = Sink()
        var available = false
        var calls = 0
        sink.verifier = { identity -> calls++; if (available) item(identity, "verified") else null }
        val owner = AutomaticQueuePrewarmOwner(scope, sink)
        try {
            owner.update(request())
            assertNull(sink.plans.last())
            owner.update(request(), availabilityRefreshed = false)
            assertEquals(1, calls)
            available = true
            owner.update(request())
            assertEquals(2, calls)
            owner.update(request())
            assertEquals(2, calls)
            assertTrue(sink.plans.last() is NativeEngineAutomaticEndPlan.NextOwner)
        } finally { scope.cancel() }
    }

    private class Sink : AutomaticQueuePrewarmSink {
        var latest = "current"
        var metadataRefreshes = 0
        val plans = mutableListOf<NativeEngineAutomaticEndPlan?>()
        val predictions = mutableListOf<NativeEngineQueueItem?>()
        var verifier: suspend (EngineAudioIdentity) -> NativeEngineQueueItem? = { item(it, "verified") }
        override suspend fun verify(identity: EngineAudioIdentity) = verifier(identity)
        override fun stillCurrent(request: AutomaticQueuePrewarmRequest) = true
        override fun refreshMetadata() { metadataRefreshes++ }
        override fun refreshed(item: NativeEngineQueueItem) = item.copy(metadata = item.metadata.copy(payloadKey = latest))
        override fun publish(plan: NativeEngineAutomaticEndPlan?) { plans += plan }
        override fun predict(item: NativeEngineQueueItem?) { predictions += item }
    }

    companion object {
        private val current = EngineAudioIdentity("A", "audio-A", "content://A", 10_000)
        private val next = EngineAudioIdentity("B", "audio-B", "content://B", 10_000)
        private fun request() = AutomaticQueuePrewarmRequest(1, EngineSelectionToken(1), current,
            QueueDecision.Move("B", 1), null, RootAuthority("root", "content://root", 1), listOf("B" to next))
        private fun item(identity: EngineAudioIdentity, payload: String) = NativeEngineQueueItem(identity,
            NativeEngineMetadata(identity.songId, identity.audioRevision, 1, payload))
    }
}
