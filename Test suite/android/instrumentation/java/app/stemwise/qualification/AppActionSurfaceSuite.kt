package app.stemwise.qualification

import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.playback.session.StableMediaSessionPlayerInstrumentationTest
import app.stemwise.ui.CompactPlayerProductionIntegrationTest
import app.stemwise.ui.DesignSystemComposeInstrumentationTest
import app.stemwise.ui.PlayerVisualPolicyInstrumentationTest
import app.stemwise.ui.LibraryInteractionTest
import app.stemwise.ui.MetronomeAccessibilityInstrumentationTest
import app.stemwise.ui.PlayerGestureIsolationTest
import app.stemwise.ui.PlayerLyricsScrollIsolationTest
import app.stemwise.ui.PlayerLyricsViewportTest
import app.stemwise.ui.PlayerNavigationVisibilityTest
import app.stemwise.ui.QualificationEvidenceTest
import app.stemwise.ui.RetainedLyricsComposePressureTest
import app.stemwise.ui.player.surface.DesignSystemNativePlayerInstrumentationTest
import app.stemwise.ui.player.surface.NativePlayerGestureInstrumentationTest
import app.stemwise.ui.player.surface.NativePlayerRenderingInstrumentationTest
import app.stemwise.ui.player.surface.PlayerSurfaceSemanticsAccessibilityTest
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.junit.runners.Suite

/**
 * One explicit entry point for the existing app-owned action and visual-interaction tests.
 *
 * This suite deliberately stays on the one supplied disposable tablet. It does not contain a
 * device matrix, Bluetooth routing, live credentials, paid service calls, or owner data. The
 * recorded UX run remains separate because functional callbacks cannot judge motion, clarity,
 * hierarchy, feedback, or overall feel by themselves.
 */
@RunWith(Suite::class)
@Suite.SuiteClasses(
    DesignSystemComposeInstrumentationTest::class,
    PlayerVisualPolicyInstrumentationTest::class,
    DesignSystemNativePlayerInstrumentationTest::class,
    NativePlayerGestureInstrumentationTest::class,
    NativePlayerRenderingInstrumentationTest::class,
    LibraryInteractionTest::class,
    CompactPlayerProductionIntegrationTest::class,
    PlayerGestureIsolationTest::class,
    PlayerNavigationVisibilityTest::class,
    PlayerSurfaceSemanticsAccessibilityTest::class,
    MetronomeAccessibilityInstrumentationTest::class,
    PlayerLyricsScrollIsolationTest::class,
    PlayerLyricsViewportTest::class,
    RetainedLyricsComposePressureTest::class,
    QualificationEvidenceTest::class,
    StableMediaSessionPlayerInstrumentationTest::class
)
class AppActionSurfaceSuite {
    companion object {
        @BeforeClass
        @JvmStatic
        fun requireExplicitSelection() {
            StandardInstrumentationTargetGuard.requireCurrentTarget()
            val explicitlySelected = InstrumentationRegistry.getArguments()
                .getString("class")
                ?.split(',')
                ?.map { value -> value.trim().substringBefore('#') }
                ?.any { value -> value == SUITE_CLASS_NAME } == true
            assumeTrue(
                "AppActionSurfaceSuite must be selected explicitly with -e class; " +
                    "its child tests are independently discoverable.",
                explicitlySelected
            )
        }

        private const val SUITE_CLASS_NAME =
            "app.stemwise.qualification.AppActionSurfaceSuite"
    }
}
