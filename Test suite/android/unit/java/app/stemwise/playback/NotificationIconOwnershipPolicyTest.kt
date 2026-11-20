package app.stemwise.playback

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class NotificationIconOwnershipPolicyTest {
    @Test
    fun `media3 transport resources are app owned designer catalog geometry`() {
        val drawableRoot = File(
            projectRoot(),
            "app/src/main/res/drawable-anydpi-v21"
        )
        val expectedPathFragments = mapOf(
            "media3_icon_previous.xml" to
                "M7,6c0.55,0 1,0.45 1,1v10c0,0.55 -0.45,1 -1,1",
            "media3_icon_play.xml" to
                "M8,6.82v10.36c0,0.79 0.87,1.27 1.54,0.84",
            "media3_icon_pause.xml" to
                "M8,19c1.1,0 2,-0.9 2,-2V7",
            "media3_icon_next.xml" to
                "M7.58,16.89l5.77,-4.07c0.56,-0.4"
        )

        expectedPathFragments.forEach { (name, pathFragment) ->
            val resource = File(drawableRoot, name)
            assertTrue("Missing app-owned notification drawable $name", resource.isFile)
            val xml = resource.readText()
            assertTrue("$name lost its Designer-catalog path", pathFragment in xml)
        }
    }

    // Notification content is read from the actual worker in
    // NotificationIconOwnershipInstrumentationTest.preparationWorkerBuildsBrandedNotificationWithoutActions.

    @Test
    fun `playback test media authority follows the exact instrumentation role`() {
        val root = projectRoot()
        val testSuite = checkNotNull(root.parentFile)
            .resolve("Test suite/android/instrumentation")
        val gradle = File(root, "app/build.gradle.kts").readText()
        val manifest = File(testSuite, "AndroidManifest.xml").readText()
        val provider = File(
            testSuite,
            "java/app/stemwise/playback/PlaybackTestMediaProvider.java"
        ).readText()

        assertTrue("Test media authority is not derived from the selected target role",
            "\"\$configuredApplicationId.test.playback.media\"" in gradle)
        assertTrue("Instrumentation provider is not using the role-derived authority",
            "\${stemwisePlaybackTestMediaAuthority}" in manifest)
        assertTrue("Test media URI is not using the selected target application ID",
            "BuildConfig.APPLICATION_ID + \".test.playback.media/" in provider)
        assertFalse("The colliding deliverable authority was hard-coded in the manifest",
            "app.stemwise.test.playback.media" in manifest)
        assertFalse("The colliding deliverable URI was hard-coded in test code",
            "content://app.stemwise.test.playback.media" in provider)
    }

    private fun projectRoot(): File {
        val working = File(checkNotNull(System.getProperty("user.dir")))
        return generateSequence(working) { it.parentFile }
            .firstOrNull { File(it, "app/src/main").isDirectory }
            ?: error("Could not locate StemWise project from ${working.absolutePath}")
    }
}
