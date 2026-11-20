package app.stemwise.qualification

import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.data.CanonicalProjectionPromotionTest
import app.stemwise.data.CurrentSourceAuthorityPersistenceTest
import app.stemwise.data.StemwisePreferencesIsolationTest
import app.stemwise.data.StorageRefreshInstrumentationTest
import app.stemwise.enrichment.EnrichmentStrictJsonParserInstrumentationTest
import app.stemwise.enrichment.GoldenEnrichmentImportInstrumentationTest
import app.stemwise.enrichment.OriginalEnrichmentImportInstrumentationTest
import app.stemwise.enrichment.EnrichmentPublicationRecoveryInstrumentationTest
import app.stemwise.playback.engine.MediaCodecTrackDecoderInstrumentationTest
import app.stemwise.playback.engine.NativeLaneIngressInstrumentationTest
import app.stemwise.playback.engine.NativeManagedLoopIntentJniInstrumentationTest
import app.stemwise.playback.engine.NativePlaybackRuntimeInstrumentationTest
import app.stemwise.ui.player.surface.PlayerSurfaceArtworkLoaderQosInstrumentationTest
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.junit.runners.Suite

/**
 * Explicit isolated data/provider/decoder/native-JNI/artwork/lyrics partition.
 *
 * No child appears in [UnifiedPlayerPressureSuite]. The wrapper skips unless selected explicitly,
 * preventing broad discovery from executing each child both directly and through this suite.
 * NativeFatalProcessRetirementInstrumentationTest remains a separate two-process fatal workflow.
 * The native-runtime children own and assert their local output diagnostics, but no trustworthy
 * partition-wide observer spans every test-created output. Aggregate process audio-health coverage
 * for this partition is therefore BLOCKED; do not attach [ProcessAudioHealthRunListener] and do not
 * report an aggregate audio-health pass.
 *
 * Run with:
 * `adb shell am instrument -w -e class app.stemwise.qualification.IsolatedDataEngineSurfaceSuite`
 * ` app.stemwise.development.test/`
 * `app.stemwise.qualification.DevelopmentStandardInstrumentationRunner`
 */
@RunWith(Suite::class)
@Suite.SuiteClasses(
    CanonicalProjectionPromotionTest::class,
    CurrentSourceAuthorityPersistenceTest::class,
    StemwisePreferencesIsolationTest::class,
    StorageRefreshInstrumentationTest::class,
    EnrichmentStrictJsonParserInstrumentationTest::class,
    GoldenEnrichmentImportInstrumentationTest::class,
    OriginalEnrichmentImportInstrumentationTest::class,
    EnrichmentPublicationRecoveryInstrumentationTest::class,
    MediaCodecTrackDecoderInstrumentationTest::class,
    NativeLaneIngressInstrumentationTest::class,
    NativeManagedLoopIntentJniInstrumentationTest::class,
    NativePlaybackRuntimeInstrumentationTest::class,
    PlayerSurfaceArtworkLoaderQosInstrumentationTest::class
)
class IsolatedDataEngineSurfaceSuite {
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
                "IsolatedDataEngineSurfaceSuite must be selected explicitly with -e class; " +
                    "its children are independently discoverable.",
                explicitlySelected
            )
            FocusedSuiteMethodRunListener.begin(InstrumentationRegistry.getArguments(), SUITE_CLASS_NAME)
        }

        @AfterClass
        @JvmStatic
        fun requireFocusedMethodsComplete() {
            FocusedSuiteMethodRunListener.requireComplete()
        }

        @AfterClass
        @JvmStatic
        fun requireKnownFixtureResidueFree() {
            StandardInstrumentationResidue.assertKnownFixturesAbsent()
        }

        private const val SUITE_CLASS_NAME =
            "app.stemwise.qualification.IsolatedDataEngineSurfaceSuite"
    }
}
