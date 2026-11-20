package app.stemwise.ui

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.debug.UiStressLabActivity
import app.stemwise.debug.UiStressDataMode
import app.stemwise.debug.UiStressSurface
import app.stemwise.ui.player.surface.PlayerSurfaceView
import app.stemwise.ui.player.surface.PlayerSurfaceProfilePacket
import app.stemwise.ui.player.nativeplayer.FullPlayerSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Exercises actual production views with Development-only props; it does not assert visual polish. */
class UiStressLabInstrumentationTest {
    @get:Rule val compose = createAndroidComposeRule<UiStressLabActivity>()

    @Test
    fun allDatasetsRenderAtNarrowPhoneAndWideWidthsWithNormalAndDoubleText() {
        val output = File(compose.activity.cacheDir, "ui-stress-captures").apply {
            check(canonicalFile.parentFile == compose.activity.cacheDir.canonicalFile)
            if (exists()) check(deleteRecursively())
            check(mkdirs())
        }
        for (surface in UiStressSurface.entries) {
            select(surface.name)
            for (width in listOf("320 dp", "432 dp", "Wide")) {
                select(width)
                for (scale in listOf("Text 1×", "Text 2×")) {
                    select(scale)
                    for (mode in UiStressDataMode.entries) {
                        println("UI_STRESS_CASE ${surface.name}/${mode.name}/$width/$scale")
                        select(mode.label)
                        compose.waitForIdle()
                        val content = compose.onNodeWithTag("ui-stress-content")
                        content.assertIsDisplayed()
                        val bounds = content.fetchSemanticsNode().boundsInRoot
                        assertTrue("Empty viewport for $surface/$mode/$width/$scale", bounds.width > 0f && bounds.height > 0f)
                        if (width != "Wide") {
                            val density = compose.activity.resources.displayMetrics.density
                            val expected = if (width == "320 dp") 320f else 432f
                            assertEquals("Fixture did not use its requested width", expected * density, bounds.width, 1f)
                        }
                        if (mode == UiStressDataMode.DEMO || mode == UiStressDataMode.WORST) {
                            // Local artwork decoding is asynchronous; allow its normal arrival to settle.
                            SystemClock.sleep(400)
                            // Capture the compositor: Compose bitmap capture can omit native RenderNodes.
                            val image = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                            val file = File(output, "${surface.name}-${mode.name}-${width.substringBefore(' ')}-${if (scale == "Text 1×") 1 else 2}.png")
                            try {
                                file.outputStream().use { stream -> check(image.compress(Bitmap.CompressFormat.PNG, 100, stream)) }
                            } finally { image.recycle() }
                        }
                        if (mode == UiStressDataMode.LARGE && surface in setOf(UiStressSurface.Library, UiStressSurface.Activity)) {
                            content.performTouchInput { swipeUp(durationMillis = 180) }
                            compose.waitForIdle()
                            content.assertIsDisplayed()
                            val prefix = if (surface == UiStressSurface.Library) "library-artwork:" else "activity-bridge-task-"
                            val mounted = compose.onAllNodes(SemanticsMatcher("mounted fixture row") {
                                it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
                            }, useUnmergedTree = true).fetchSemanticsNodes().size
                            assertTrue("Expected a bounded, nonempty mounted subset of 1,000 rows; got $mounted", mounted in 1..999)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun playerDatasetChangesRebindTheMountedProductionView() {
        select("Player")
        select("Demo data")
        lateinit var before: PlayerSurfaceView
        var generation = 0L
        val profileField = PlayerSurfaceView::class.java.getDeclaredField("profile").apply { isAccessible = true }
        val visualsField = PlayerSurfaceView::class.java.getDeclaredField("visuals").apply { isAccessible = true }
        compose.runOnIdle {
            before = checkNotNull(player(compose.activity.window.decorView))
            generation = (profileField.get(before) as PlayerSurfaceProfilePacket).owner.generation
        }
        select("Worst case")
        compose.runOnIdle {
            val after = checkNotNull(player(compose.activity.window.decorView))
            assertSame("Preview must use the real player's rebind lifecycle", before, after)
            assertTrue((profileField.get(after) as PlayerSurfaceProfilePacket).owner.generation > generation)
            assertTrue((visualsField.get(after) as FullPlayerSnapshot).catalog[0].title.startsWith("نور الهدى"))
        }
    }

    @Test
    fun newLaunchConfigurationOverridesSavedChoicesAndSurvivesRecreation() {
        select("Library")
        select("Demo data")
        select("432 dp")
        select("Text 1×")
        val before = compose.activity
        compose.runOnUiThread {
            before.applicationContext.startActivity(android.content.Intent(before.intent)
                .setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("surface", "Player").putExtra("data", "WORST")
                .putExtra("width", "320").putExtra("textScale", "2"))
        }
        compose.waitUntil(5_000L) {
            compose.onAllNodes(hasText("Player") and role() and isSelected()).fetchSemanticsNodes().isNotEmpty()
        }
        fun assertConfiguration() {
            for (label in listOf("Player", "Worst case", "320 dp", "Text 2×")) {
                compose.onNode(hasText(label) and role()).assertIsSelected()
            }
            val density = compose.activity.resources.displayMetrics.density
            assertEquals(320f * density, compose.onNodeWithTag("ui-stress-content")
                .fetchSemanticsNode().boundsInRoot.width, 1f)
            compose.onNodeWithTag("ui-stress-native-player").assertIsDisplayed()
            compose.runOnIdle {
                val surface = checkNotNull(player(compose.activity.window.decorView))
                val visuals = PlayerSurfaceView::class.java.getDeclaredField("visuals").apply { isAccessible = true }
                val fontScale = PlayerSurfaceView::class.java.getDeclaredField("fontScale").apply { isAccessible = true }
                assertTrue((visuals.get(surface) as FullPlayerSnapshot).catalog[0].title.startsWith("نور الهدى"))
                assertEquals(2f, fontScale.getFloat(surface), 0f)
            }
            assertEquals("Player", compose.activity.getSharedPreferences("ui-stress-lab", 0).getString("surface", null))
            assertEquals("WORST", compose.activity.getSharedPreferences("ui-stress-lab", 0).getString("data", null))
            assertEquals("320", compose.activity.getSharedPreferences("ui-stress-lab", 0).getString("width", null))
            assertEquals("2.0", compose.activity.getSharedPreferences("ui-stress-lab", 0).getString("textScale", null))
        }
        assertSame("A new launch must update the existing singleTask Activity", before, compose.activity)
        assertConfiguration()
        compose.activityRule.scenario.recreate()
        assertConfiguration()
        for (label in listOf("Library", "One", "432 dp", "Text 1×")) select(label)
        compose.activityRule.scenario.recreate()
        for (label in listOf("Library", "One", "432 dp", "Text 1×")) {
            compose.onNode(hasText(label) and role()).assertIsSelected()
        }
    }

    @Test
    fun dataSelectionSurvivesRecreationWithoutLaunchingAnExternalActivity() {
        val info = compose.activity.packageManager.getActivityInfo(
            android.content.ComponentName(compose.activity, UiStressLabActivity::class.java), 0)
        assertTrue(info.exported)
        assertEquals("android.permission.DUMP", info.permission)
        select("Library")
        select("Worst case")
        compose.activityRule.scenario.recreate()
        compose.onNode(hasText("Worst case") and role()).assertIsSelected()
        compose.runOnUiThread { compose.activity.startActivity(android.content.Intent(
            android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://example.test/fixture"))) }
        compose.onNodeWithTag("ui-stress-content").assertIsDisplayed()
    }

    private fun role() = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
    private fun player(view: View): PlayerSurfaceView? = if (view is PlayerSurfaceView) view else
        (view as? ViewGroup)?.let { group ->
            (0 until group.childCount).firstNotNullOfOrNull { player(group.getChildAt(it)) }
        }
    private fun select(label: String) {
        compose.onNode(hasText(label) and role()).performScrollTo().performClick()
    }
}
