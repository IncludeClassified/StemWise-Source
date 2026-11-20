#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UV_BIN="$PROJECT_DIR/.tools/uv"
BOOTSTRAP_PYTHON="$PROJECT_DIR/.tools/bootstrap/bin/python"
[[ -x "$BOOTSTRAP_PYTHON" ]] || { echo "Run the main setup first" >&2; exit 1; }
VENV_PYTHON="$PROJECT_DIR/../$("$BOOTSTRAP_PYTHON" -m stemwise_engine.runtime_layout --environment allin1)"
VENV="$(dirname "$(dirname "$VENV_PYTHON")")"

if [[ -L "$VENV" ]]; then
  echo "Refusing to clear a symlinked All-In-One environment: $VENV" >&2
  exit 1
fi

"$BOOTSTRAP_PYTHON" -m stemwise_engine.environment_locks allin1 --check-inputs
"$BOOTSTRAP_PYTHON" -m stemwise_engine.environment_bootstrap allin1
