from __future__ import annotations

import importlib.util
from pathlib import Path
import unittest
import tempfile
import sys


SUITE_ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = SUITE_ROOT / "device" / "device_inventory.py"
SPEC = importlib.util.spec_from_file_location("stemwise_device_inventory", MODULE_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("Could not load the Device inventory validator.")
device_inventory = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(device_inventory)


class DeviceInventoryTest(unittest.TestCase):
    def test_audio_isolation_metadata_preserves_real_network_and_scopes_the_live_ui_method(self):
        rows = device_inventory.build_accounting()["methods"]
        self.assertTrue(all(type(row["requiresAudioIsolation"]) is bool for row in rows))
        for owner in ("app.stemwise.playback.SongTrimPlaybackInstrumentationTest",
                      "app.stemwise.playback.engine.AudioTrackStartupInstrumentationTest",
                      "app.stemwise.playback.engine.AudioTrackAcousticInstrumentationTest",
                      "app.stemwise.ui.TuningSpeedAvailabilityInstrumentationTest"):
            selected = [row for row in rows if row["class"] == owner]
            self.assertTrue(selected, owner)
            self.assertTrue(all(row["requiresAudioIsolation"] for row in selected), owner)
        for owner in ("app.stemwise.network.WifiProcessBindingInstrumentationTest",
                      "app.stemwise.integration.CurrentVerifiedYtDlpUpdaterInstrumentationTest",
                      "app.stemwise.integration.ManualVerifiedYtDlpUpdaterInstrumentationTest",
                      "app.stemwise.ui.design.GlyphRendererParityInstrumentationTest"):
            selected = [row for row in rows if row["class"] == owner]
            self.assertTrue(selected, owner)
            self.assertFalse(any(row["requiresAudioIsolation"] for row in selected), owner)
        native_ui = [row for row in rows if row["class"] ==
                     "app.stemwise.ui.player.surface.DesignSystemNativePlayerInstrumentationTest"]
        self.assertEqual(["realHeldBTracksCursorAndEveryFastCrossingWrapsWithinTheOutputServiceBudget"],
                         [row["id"].split("#")[1] for row in native_ui if row["requiresAudioIsolation"]])

    def test_every_instrumentation_method_has_one_disjoint_lane(self) -> None:
        accounting = device_inventory.build_accounting()
        self.assertEqual("PASS", accounting["status"])
        self.assertGreater(accounting["methodCount"], 0)
        self.assertEqual(0, accounting["unassignedCount"])
        self.assertEqual(0, accounting["unintendedDuplicateCount"])
        ids = [row["id"] for row in accounting["methods"]]
        self.assertEqual(len(ids), len(set(ids)))

    def test_suite_wrappers_and_entries_are_source_exact_and_nonoverlapping(self) -> None:
        accounting = device_inventory.build_accounting()
        children = [row["child"] for row in accounting["suiteEntries"]]
        self.assertEqual(len(children), len(set(children)))
        self.assertEqual(3, accounting["wrapperCount"])
        self.assertEqual(38, accounting["suiteEntryCount"])

    def test_special_workflows_are_never_implicit_ordinary_discovery(self) -> None:
        accounting = device_inventory.build_accounting()
        by_id = {row["id"]: row for row in accounting["methods"]}
        self.assertEqual(
            "lifecycle-phase",
            by_id[
                "app.stemwise.data.TesterPhysicalLibraryLifecycleInstrumentationTest#"
                "phaseTwoRestartsRecoversIsolatesDeletesAndCleans"
            ]["lane"],
        )
        self.assertEqual(
            "fatal-two-process",
            by_id[
                "app.stemwise.playback.NativeFatalProcessRetirementInstrumentationTest#"
                "fatalThenRecentsRemovalRetiresProcess"
            ]["lane"],
        )

    def test_intentional_fault_is_isolated_and_glyph_parity_remains_ordinary(self):
        accounting=device_inventory.build_accounting()
        selector='app.stemwise.playback.engine.NativeProcessFaultInstrumentationTest#processFaultEvidenceSurvivesRuntimeReplacement'
        ordinary=device_inventory.selectors_for_lane(accounting,'ordinary')
        self.assertNotIn(selector.split('#')[0],ordinary)
        self.assertEqual([selector],device_inventory.selectors_for_lane(accounting,'native-process-fault'))
        self.assertIn('app.stemwise.ui.design.GlyphRendererParityInstrumentationTest',ordinary)

    def test_fault_guards_and_required_ordinary_class_fail_closed(self):
        from copy import deepcopy
        from unittest.mock import patch
        original=device_inventory.load_manifest()
        for mutation in ('missing-action','missing-guard','false-guard','glyph-excluded'):
            manifest=deepcopy(original)
            if mutation=='missing-action':
                del manifest['nativeProcessFault']
            elif mutation=='missing-guard':
                del manifest['nativeProcessFault']['arguments']['stemwiseIntentionalNativeFault']
            elif mutation=='false-guard':
                manifest['nativeProcessFault']['arguments']['stemwiseDisposableTarget']='false'
            else:
                accounting=device_inventory.build_accounting()
                manifest['deliberateExclusions'] += [{'id':row['id'],'reason':'isolated regression fault'}
                    for row in accounting['methods'] if row['class']=='app.stemwise.ui.design.GlyphRendererParityInstrumentationTest']
            with self.subTest(mutation=mutation), patch.object(device_inventory,'load_manifest',return_value=manifest):
                with self.assertRaises(device_inventory.InventoryError):
                    device_inventory.build_accounting()

    def test_new_ordinary_regressions_cannot_be_silently_excluded(self):
        from copy import deepcopy
        from unittest.mock import patch
        original = device_inventory.load_manifest()
        accounting = device_inventory.build_accounting()
        ordinary_classes = (
            'app.stemwise.integration.SourceAcknowledgementStoreInstrumentationTest',
            'app.stemwise.data.CurrentSourcePackageInstrumentationTest',
            'app.stemwise.artwork.ArtworkDiskCacheRevisionInstrumentationTest',
            'app.stemwise.data.BackendSafetyInstrumentationTest',
            'app.stemwise.ui.LyricsDiscreteInputInstrumentationTest',
            'app.stemwise.playback.NotificationIconOwnershipInstrumentationTest',
        )
        for owner in ordinary_classes:
            manifest = deepcopy(original)
            rows = [row for row in accounting['methods'] if row['class'] == owner]
            self.assertTrue(rows, owner)
            self.assertTrue(all(row['lane'] == 'ordinary' for row in rows), owner)
            manifest['deliberateExclusions'] += [
                {'id': row['id'], 'reason': 'isolated exclusion fault'} for row in rows]
            with self.subTest(owner=owner), patch.object(device_inventory, 'load_manifest', return_value=manifest):
                with self.assertRaisesRegex(device_inventory.InventoryError, 'Required ordinary class'):
                    device_inventory.build_accounting()

    def test_new_methods_retain_their_ordinary_and_explicit_suite_selectors(self):
        accounting = device_inventory.build_accounting()
        by_id = {row['id']: row for row in accounting['methods']}
        ordinary = device_inventory.selectors_for_lane(accounting, 'ordinary')
        self.assertIn('app.stemwise.ui.LyricsDiscreteInputInstrumentationTest', ordinary)
        self.assertEqual(6, sum(row['class'] == 'app.stemwise.ui.LyricsDiscreteInputInstrumentationTest'
                                for row in accounting['methods']))
        backend = ('app.stemwise.data.BackendSafetyInstrumentationTest#'
                   'interruptedOriginalResumesOnlyItsExactJournaledBytesAndPreservesReplacements')
        self.assertEqual('ordinary', by_id[backend]['lane'])
        for method, wrapper in (
                ('app.stemwise.playback.PlayerScrubOwnershipInstrumentationTest#'
                 'metadataRebindRetiresTheExactScrubEvenAfterItsUiFlagClears',
                 'app.stemwise.qualification.UnifiedPlayerPressureSuite'),
                ('app.stemwise.ui.PlayerVisualPolicyInstrumentationTest#'
                 'playbackStatusHasOneProgressOwnerAndErrorHasOneAssertiveAnnouncement',
                 'app.stemwise.qualification.AppActionSurfaceSuite'),
                ('app.stemwise.ui.player.surface.PlayerSurfaceSemanticsAccessibilityTest#'
                 'measuredNativeGeometryOwnsExpandedRtlAndCompactSemanticBounds',
                 'app.stemwise.qualification.AppActionSurfaceSuite'),
                ('app.stemwise.playback.QueueIntegrationInstrumentationTest#'
                 'delayedSourcePendingEndCannotRestartAnOwnerSupersededByManualSelection',
                 'app.stemwise.qualification.UnifiedPlayerPressureSuite')):
            self.assertEqual('explicit-suite', by_id[method]['lane'])
            self.assertEqual(wrapper, by_id[method]['wrapper'])

    def test_missing_required_ordinary_source_and_unknown_special_class_fail_closed(self):
        from unittest.mock import patch
        original = device_inventory.discover_tests()
        for owner in device_inventory.load_manifest()['ordinaryRequiredClasses']:
            tests = {name: methods for name, methods in original.items() if name != owner}
            with self.subTest(owner=owner), patch.object(device_inventory, 'discover_tests', return_value=tests):
                with self.assertRaisesRegex(device_inventory.InventoryError, 'Required ordinary class'):
                    device_inventory.build_accounting()
        tests = dict(original, **{'app.stemwise.UnknownProcessFaultInstrumentationTest': ['unknown']})
        with patch.object(device_inventory, 'discover_tests', return_value=tests):
            with self.assertRaisesRegex(device_inventory.InventoryError, 'implicitly assigned ordinary'):
                device_inventory.build_accounting()

    def test_current_runtime_update_dispatch_retains_its_isolated_process_runner(self):
        sys.path.insert(0, str(SUITE_ROOT/'pc'))
        from powershell_fixture import native_path, run_script
        module = native_path(SUITE_ROOT/'device/Device-Lanes.ps1').replace("'", "''")
        with tempfile.TemporaryDirectory(dir=SUITE_ROOT/'work') as name:
            script = Path(name)/'runtime-lane.ps1'
            script.write_text("$ErrorActionPreference='Stop'\n. '"+module+"'\n"+r'''
$DevelopmentPackage='app.stemwise.development'
$script:Seen=[Collections.Generic.List[string]]::new()
$script:Stops=0
$script:Installs=0
function Resolve-Adb {'fixture-adb'}
function Reset-DisposableDevicePackages {}
function Install-QualificationInstrumentation { $script:Installs++ }
function Invoke-AdbChecked($Adb,$Arguments) {
 if (($Arguments -join ',') -cne "shell,am,force-stop,--user,0,$DevelopmentPackage") {throw 'Missing exact process boundary'}
 $script:Stops++
}
function Invoke-DirectInstrumentation($Adb,$Selector,$Component) {
 if ($Component -cne "$DevelopmentPackage.test/app.stemwise.integration.IsolatedUpdaterInstrumentationRunner") {throw 'Lost isolated runner'}
 if ($Selector -cnotlike 'app.stemwise.integration.CurrentVerifiedYtDlpUpdaterInstrumentationTest#*') {throw 'Wrong current runtime test'}
 $script:Seen.Add($Selector)
}
function Register-PassingInstrumentationMethod($Selector) {
 if ($script:Seen[-1] -cne $Selector) {throw 'Acknowledged a different step'}
}
Invoke-CurrentRuntimeUpdateDeviceLane
if ($script:Installs -ne 1 -or $script:Seen.Count -ne 9 -or $script:Stops -ne 9 -or @($script:Seen | Select-Object -Unique).Count -ne 9) {throw 'Current workflow lost a phase'}
''', encoding='utf-8-sig')
            result = run_script(script, timeout=15)
            self.assertEqual(result.returncode, 0, result.stdout+result.stderr)

    def test_current_network_update_workflow_is_never_ordinary_discovery(self):
        accounting = device_inventory.build_accounting()
        name = 'app.stemwise.integration.CurrentVerifiedYtDlpUpdaterInstrumentationTest'
        methods = [row for row in accounting['methods'] if row['class'] == name]
        self.assertEqual(9, len(methods))
        self.assertTrue(all(row['excluded'] for row in methods))
        self.assertNotIn(name, device_inventory.selectors_for_lane(accounting, 'ordinary'))

    def test_acoustic_diagnostic_is_optional_and_still_discoverable(self):
        accounting = device_inventory.build_accounting()
        name = 'app.stemwise.playback.engine.AudioTrackAcousticInstrumentationTest'
        selectors = {
            name + '#builtInInputIdsAndConfigurationChangesAreValidatedWithoutCapture',
            name + '#actualSpeakerCapturePreservesTheEnginePrefixAndFollowingSequence',
        }
        rows = [row for row in accounting['methods'] if row['class'] == name]
        self.assertEqual(selectors, {row['id'] for row in rows})
        self.assertTrue(all(row['lane'] == 'optional' for row in rows))
        self.assertNotIn(name, device_inventory.selectors_for_lane(accounting, 'ordinary'))
        stress = 'app.stemwise.ui.UiStressLabInstrumentationTest'
        stress_selectors = {stress + '#' + method for method in (
            'allDatasetsRenderAtNarrowPhoneAndWideWidthsWithNormalAndDoubleText',
            'dataSelectionSurvivesRecreationWithoutLaunchingAnExternalActivity',
            'newLaunchConfigurationOverridesSavedChoicesAndSurvivesRecreation',
            'playerDatasetChangesRebindTheMountedProductionView')}
        self.assertEqual(selectors | stress_selectors,
                         set(device_inventory.selectors_for_lane(accounting, 'optional')))



if __name__ == "__main__":
    unittest.main()
