"""Run the model-free lyric-engine regression subset from the public snapshot."""
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
MODULES = (
    "test_vocal_span_recovery", "test_role_vocal_span_recovery",
    "test_role_phrase_overlap", "test_role_repeat_boundaries",
    "test_held_repeat_release", "test_lead_vowel_observer",
    "test_arabic_coda_release", "test_phrase_final_release", "test_phrase_final_coda",
)

def run(directory, modules):
    return subprocess.call(
        [sys.executable, "-B", "Test suite/pc/run_tests.py",
         "--directory", f"Test suite/pc/{directory}", *modules],
        cwd=ROOT,
    )


if __name__ == "__main__":
    raise SystemExit(run("alignment", MODULES) or
                     run("unit", ("test_public_provider_text",)))
