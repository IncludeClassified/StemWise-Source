"""Check the prebuilt SDK with its canonical inventory-aware verifier.

Unchanged inputs need no Go; --discover explicitly rechecks the Android closure.
"""
import argparse
from pathlib import Path
import runpy


BRIDGE = Path(__file__).resolve().parents[1] / "third_party/phone-pc-bridge"


def verify(bridge=BRIDGE, *, discover=False, go="go"):
    source = bridge / "source"
    sdk = runpy.run_path(str(source / "bridge-core/sdk_artifact.py"))
    artifact = bridge / "android"
    return sdk["verify"](source, artifact, go=go, force_discovery=discover)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--discover", action="store_true")
    parser.add_argument("--go", default="go")
    args = parser.parse_args()
    result = verify(discover=args.discover, go=args.go)
    print(f"Bridge SDK verified: {len(result['inputs'])} declared inputs" +
          (" and Android dependency closure" if args.discover else " (candidate-aware verification)"))
