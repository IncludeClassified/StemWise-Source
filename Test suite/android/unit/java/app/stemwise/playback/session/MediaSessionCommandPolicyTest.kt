package app.stemwise.playback.session

import app.stemwise.playback.mediaSessionControllerIsAllowed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class MediaSessionCommandPolicyTest {
    @Test
    fun exportedSessionAdmitsOnlyTheAppOrTrustedMediaControllers() {
        assertTrue(mediaSessionControllerIsAllowed("app.stemwise", "app.stemwise", false))
        assertTrue(mediaSessionControllerIsAllowed("app.stemwise", "android", true))
        assertTrue(mediaSessionControllerIsAllowed("app.stemwise", "com.android.auto", true))
        assertFalse(mediaSessionControllerIsAllowed("app.stemwise", "other.installed.app", false))
    }

    @Test
    fun lifecycleDeliversTaskRemovalAndCloseAtMostOnce() {
        val lifecycle = SessionFacadeLifecycle()

        assertFalse(lifecycle.isClosed())
        assertTrue(lifecycle.tryDeliverTaskRemoval())
        assertFalse(lifecycle.tryDeliverTaskRemoval())
        assertTrue(lifecycle.tryClose())
        assertTrue(lifecycle.isClosed())
        assertFalse(lifecycle.tryClose())
        assertFalse(lifecycle.tryDeliverTaskRemoval())
    }

}
