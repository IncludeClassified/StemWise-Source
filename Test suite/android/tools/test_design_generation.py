import importlib.util
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / "StemWise/tools/generate-stemwise-design.py"


class DesignGenerationTest(unittest.TestCase):
    def test_design_sources_contain_only_current_icon_inputs_and_license(self):
        directory = ROOT / "StemWise/design-system"
        self.assertEqual(
            {"stemwise-icons.json", "source-part-icons.svg", "Bravura-LICENSE.txt"},
            {str(path.relative_to(directory)) for path in directory.rglob("*") if path.is_file()},
        )

    def test_checked_in_icons_regenerate_without_a_design_handoff(self):
        result = subprocess.run(
            [sys.executable, str(SCRIPT), "--check"], capture_output=True, text=True
        )
        self.assertEqual(0, result.returncode, result.stderr)

    def check_catalog(self, change, *, rejection=None):
        spec = importlib.util.spec_from_file_location("design_generator", SCRIPT)
        generator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(generator)
        work = ROOT / "Test suite/work"
        work.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=work) as directory:
            catalog = Path(directory) / "icons.json"
            data = json.loads(generator.CATALOG.read_text(encoding="utf-8"))
            change(data)
            catalog.write_text(json.dumps(data, indent=4) + "\n ", encoding="utf-8")
            output = Path(directory) / "app/src/main/java/app/stemwise/ui/design/generated/output.kt"
            output.parent.mkdir(parents=True)
            output.write_bytes(b"existing generated output")
            resources = output.parents[6] / "res/drawable"
            resources.mkdir(parents=True)
            (resources / "ic_launcher_foreground.xml").write_text("<vector/>", encoding="utf-8")
            with patch.object(generator, "CATALOG", catalog), patch.object(
                generator, "OUTPUT", output
            ), patch.object(sys, "argv", [str(SCRIPT)]):
                if rejection is not None:
                    with self.assertRaisesRegex(ValueError, rejection):
                        generator.main()
                    self.assertEqual(b"existing generated output", output.read_bytes())
                    return
                self.assertEqual(0, generator.main())
            return output.read_text(encoding="utf-8")

    def test_harmless_catalog_formatting_regenerates(self):
        self.assertIn("PLAYER_PLAY", self.check_catalog(lambda _: None))

    def test_valid_catalog_extension_regenerates(self):
        def extend(data):
            role = copy.deepcopy(data["roles"][0])
            role.update(role="library.additional_icon", family="additional-family", master="additional-master")
            data["roles"].append(role)
        self.assertIn("LIBRARY_ADDITIONAL_ICON", self.check_catalog(extend))

    def test_invalid_geometry_preserves_generated_output(self):
        self.check_catalog(lambda data: data["roles"][0]["layers"][0].update(element="unsupported"),
                           rejection="Unsupported element")

    def test_missing_required_role_preserves_generated_output(self):
        self.check_catalog(lambda data: data.update(roles=[r for r in data["roles"] if r["role"] != "player.play"]),
                           rejection="Missing app glyph roles")

    def test_competing_density_launcher_is_rejected_before_generation(self):
        spec = importlib.util.spec_from_file_location("design_generator", SCRIPT)
        generator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(generator)
        work = ROOT / "Test suite/work"
        work.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=work) as directory:
            output = Path(directory) / "app/src/main/java/app/stemwise/ui/design/generated/output.kt"
            output.parent.mkdir(parents=True)
            resources = output.parents[6] / "res"
            (resources / "drawable").mkdir(parents=True)
            (resources / "drawable/ic_launcher_foreground.xml").write_text("<vector/>", encoding="utf-8")
            (resources / "drawable-xhdpi").mkdir()
            (resources / "drawable-xhdpi/ic_launcher_foreground.png").write_bytes(b"competing bitmap")
            with patch.object(generator, "OUTPUT", output), patch.object(sys, "argv", [str(SCRIPT)]):
                with self.assertRaisesRegex(ValueError, "Competing launcher"):
                    generator.main()
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
