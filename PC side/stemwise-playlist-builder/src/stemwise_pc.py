#!/usr/bin/env python3
"""Command-line entry point for the Stemwise playlist enrichment builder."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path
from typing import Any

from stemwise_engine.common import (
    diagnostic_code,
    exclusive_process_lock,
    load_config,
)
from stemwise_engine.pipeline import StemwisePipeline
from stemwise_engine.runtime_environment import environment_python




def parser() -> argparse.ArgumentParser:
    command_line = argparse.ArgumentParser(
        prog="stemwise-pc",
        description="Inspect private analysis state; STEMWISE_AGENT owns reviewed song publication.",
    )
    command_line.add_argument("--config", required=True, type=Path)
    commands = command_line.add_subparsers(dest="command", required=True)

    commands.add_parser("doctor", help="Report dependencies, FFmpeg, and GPU readiness")

    inventory = commands.add_parser("inventory", help="Refresh the no-audio playlist snapshot")
    inventory.add_argument("--url", help="Override config playlistUrl for this snapshot")

    commands.add_parser("status", help="Write and display the PC-only progress report")
    return command_line


def _print(value: Any) -> None:
    print(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False))


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    try:
        config_path = args.config.resolve()
        config = load_config(config_path)

        if args.command == "doctor":
            allin1_python = Path(
                os.environ.get("STEMWISE_ALLIN1_PYTHON")
                or environment_python('allin1', config_path.parent)
            )
            completed = subprocess.run(
                [
                    sys.executable,
                    "-m", "stemwise_engine.doctor",
                    "--config",
                    str(config_path),
                    "--allin1-python",
                    str(allin1_python),
                    "--deno",
                    str(config_path.parent.parent / "runtime" / "deno" / "deno"),
                ],
                check=False,
            )
            return completed.returncode

        pipeline = StemwisePipeline(config)
        lock_path = pipeline.output_dir / 'workflow.lock'
        if args.command == "inventory":
            with exclusive_process_lock(lock_path):
                snapshot = pipeline.make_inventory(args.url)
            _print(
                {
                    "playlist": snapshot["playlist"],
                    "itemCount": snapshot["itemCount"],
                    "omittedCount": snapshot["omittedCount"],
                    "inventory": str(pipeline.inventory_path),
                }
            )
            return 0
        if args.command == "status":
            _print(pipeline.status())
            return 0
        raise AssertionError(args.command)
    except KeyboardInterrupt:
        print("Interrupted; completed checkpoints remain resumable.", file=sys.stderr)
        return 130
    except Exception as error:
        print(f"stemwise-pc: {diagnostic_code(error)}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
