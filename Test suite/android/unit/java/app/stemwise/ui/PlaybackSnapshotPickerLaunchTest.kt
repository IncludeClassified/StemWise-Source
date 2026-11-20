package app.stemwise.ui

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class PlaybackSnapshotPickerLaunchTest {
    @Test fun cancellationAfterAdmissionExcludesTheActualLaunchAndNewOwnersStillLaunch() {
        val picker = PlaybackSnapshotPickerLaunch()
        val admitted = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val launches = AtomicInteger()
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val effect = Thread {
            try {
                admitted.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
                picker.launch { launches.incrementAndGet() }
            } catch (error: Throwable) { failure.set(error) }
        }
        try {
            effect.start()
            assertTrue(admitted.await(5, TimeUnit.SECONDS))
            picker.cancel()
            resume.countDown()
            effect.join(5_000)
            assertFalse(effect.isAlive)
            failure.get()?.let { throw it }
            assertEquals(0, launches.get())
            assertTrue(PlaybackSnapshotPickerLaunch().launch { launches.incrementAndGet() })
            assertEquals(1, launches.get())
        } finally {
            resume.countDown()
            effect.join(5_000)
        }
    }

    @Test fun cancellationAfterOpeningCannotLaunchAgain() {
        val picker = PlaybackSnapshotPickerLaunch()
        var launches = 0
        assertTrue(picker.launch { launches++ })
        picker.cancel()
        assertFalse(picker.launch { launches++ })
        assertEquals(1, launches)
    }
}
