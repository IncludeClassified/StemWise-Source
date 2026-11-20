package app.stemwise.playback

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.JUnitCore
import org.junit.runners.BlockJUnit4ClassRunner
import org.junit.runners.model.FrameworkMethod

@app.stemwise.qualification.ManagedAudioTest
class PlaybackInstrumentationIsolationRuleTest {
    @Test
    fun rejectedDiscoveryInvokesNeitherSetupNorTeardown() {
        LifecycleFixture.events.clear()
        LifecycleFixture.admitted = false
        val result = JUnitCore().run(lifecycleRunner())
        assertEquals(1, result.runCount)
        assertEquals(0, result.failureCount)
        assertEquals(emptyList<String>(), LifecycleFixture.events)
    }

    @Test
    fun admittedSetupFailureStillRunsTeardown() {
        LifecycleFixture.events.clear()
        LifecycleFixture.admitted = true
        val result = JUnitCore().run(lifecycleRunner())
        assertEquals(1, result.failureCount)
        assertEquals(listOf("setup", "cleanup"), LifecycleFixture.events)
        assertFalse(LifecycleFixture.setupResourceRetained)
    }

    private fun lifecycleRunner() = object : BlockJUnit4ClassRunner(LifecycleFixture::class.java) {
        override fun computeTestMethods(): List<FrameworkMethod> =
            listOf(FrameworkMethod(LifecycleFixture::class.java.getMethod("body")))
    }

    class LifecycleFixture {
        @get:Rule
        val target: org.junit.rules.TestRule = DisposablePlaybackTargetRule { assumeTrue(admitted) }

        @Before
        fun setup() {
            events += "setup"
            setupResourceRetained = true
            error("admitted setup failed")
        }

        @After
        fun cleanup() {
            events += "cleanup"
            setupResourceRetained = false
        }

        fun body() { error("setup must fail first") }

        companion object {
            var admitted = false
            var setupResourceRetained = false
            val events = mutableListOf<String>()
        }
    }
}
