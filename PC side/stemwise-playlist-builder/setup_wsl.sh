#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AGENT_ROOT="$(cd "$PROJECT_DIR/.." && pwd)"
# Fail before bootstrap/system environment changes when the approved private wheel is absent.
python3 "$PROJECT_DIR/src/environment_locks.py" main --check-artifacts
python3 "$PROJECT_DIR/src/environment_locks.py" main --prepare-artifacts
TOOLS_DIR="$PROJECT_DIR/.tools"
UV_BIN="$TOOLS_DIR/uv"
BOOTSTRAP_PYTHON="$TOOLS_DIR/bootstrap/bin/python"
if [[ ! -x "$BOOTSTRAP_PYTHON" ]]; then
  python3 -m venv "$TOOLS_DIR/bootstrap"
fi
"$BOOTSTRAP_PYTHON" -m pip install --require-hashes --no-deps -r "$AGENT_ROOT/bootstrap-requirements.txt"
"$BOOTSTRAP_PYTHON" -m pip install --no-deps --no-build-isolation --editable "$AGENT_ROOT"
UV_VERSION="$("$BOOTSTRAP_PYTHON" -m stemwise_engine.model_registry --asset bootstrap UV_VERSION)"
UV_ARCHIVE_SHA256="$("$BOOTSTRAP_PYTHON" -m stemwise_engine.model_registry --asset bootstrap UV_ARCHIVE_SHA256)"
CORE_PYTHON="$AGENT_ROOT/$("$BOOTSTRAP_PYTHON" -m stemwise_engine.runtime_layout --environment main)"
ALLIN1_PYTHON="$AGENT_ROOT/$("$BOOTSTRAP_PYTHON" -m stemwise_engine.runtime_layout --environment allin1)"
DENO_VERSION="$("$BOOTSTRAP_PYTHON" -m stemwise_engine.model_registry --asset doctor EXPECTED_DENO_VERSION)"
DENO_ZIP_SHA256="$("$BOOTSTRAP_PYTHON" -m stemwise_engine.model_registry --asset bootstrap DENO_ZIP_SHA256)"
DENO_BINARY_SHA256="$("$BOOTSTRAP_PYTHON" -m stemwise_engine.model_registry --asset doctor EXPECTED_DENO_SHA256)"
DENO_BIN="$AGENT_ROOT/$("$BOOTSTRAP_PYTHON" -m stemwise_engine.runtime_layout --environment-variable STEMWISE_DENO)"
DENO_DIR="$(dirname "$DENO_BIN")"
LAYOUT="$AGENT_ROOT/runtime-layout.json"
PYTHON_VERSION="$("$BOOTSTRAP_PYTHON" -m stemwise_engine.model_registry --profile main python)"
PYTHON_RELATIVE="$("$BOOTSTRAP_PYTHON" -m stemwise_engine.runtime_layout --pc-root "$AGENT_ROOT" --python-field executable)"
export UV_PYTHON_INSTALL_DIR="$AGENT_ROOT/$("$BOOTSTRAP_PYTHON" -m stemwise_engine.runtime_layout --pc-root "$AGENT_ROOT" --python-field managedInstallDirectory)"
export UV_CACHE_DIR="$AGENT_ROOT/$("$BOOTSTRAP_PYTHON" -m stemwise_engine.runtime_layout --environment-variable UV_CACHE_DIR)"
export TORCH_HOME="$AGENT_ROOT/$("$BOOTSTRAP_PYTHON" -m stemwise_engine.runtime_layout --environment-variable TORCH_HOME)"
export HF_HOME="$AGENT_ROOT/$("$BOOTSTRAP_PYTHON" -m stemwise_engine.runtime_layout --environment-variable HF_HOME)"

log() { printf '[stemwise setup] %s\n' "$*"; }

install_deno() (
  mkdir -p "$DENO_DIR"
  if [[ -x "$DENO_BIN" ]] && [[ "$(sha256sum "$DENO_BIN" | cut -d' ' -f1)" == "$DENO_BINARY_SHA256" ]]; then
    log "Pinned Deno $DENO_VERSION is already installed."
    return
  fi

  log "Installing pinned Deno $DENO_VERSION for yt-dlp JavaScript challenges."
  local tmp_dir
  tmp_dir="$(mktemp -d)"
  trap 'rm -rf -- "$tmp_dir"' EXIT
  curl -fsSL \
    "https://github.com/denoland/deno/releases/download/v${DENO_VERSION}/deno-x86_64-unknown-linux-gnu.zip" \
    -o "$tmp_dir/deno.zip"
  printf '%s  %s\n' "$DENO_ZIP_SHA256" "$tmp_dir/deno.zip" | sha256sum -c -
  "$CANONICAL_PYTHON" -m zipfile -e "$tmp_dir/deno.zip" "$tmp_dir/extracted"
  install -m 0755 "$tmp_dir/extracted/deno" "$DENO_BIN"
  [[ "$(sha256sum "$DENO_BIN" | cut -d' ' -f1)" == "$DENO_BINARY_SHA256" ]]
  rm -rf -- "$tmp_dir"
  trap - EXIT
)

if [[ "${STEMWISE_SKIP_APT:-0}" != "1" ]]; then
  log 'System prerequisites require root. Invoke setup through stemwise.ps1.'
  exit 2
fi

mkdir -p "$TOOLS_DIR"
if [[ ! -x "$UV_BIN" ]]; then
  log 'Installing the pinned uv bootstrap binary locally to this project.'
  tmp_dir="$(mktemp -d)"
  trap 'rm -rf -- "$tmp_dir"' EXIT
  curl -fsSL "https://github.com/astral-sh/uv/releases/download/${UV_VERSION}/uv-x86_64-unknown-linux-gnu.tar.gz" \
    -o "$tmp_dir/uv.tar.gz"
  printf '%s  %s\n' "$UV_ARCHIVE_SHA256" "$tmp_dir/uv.tar.gz" | sha256sum -c -
  tar -xzf "$tmp_dir/uv.tar.gz" -C "$tmp_dir"
  install -m 0755 "$tmp_dir/uv-x86_64-unknown-linux-gnu/uv" "$UV_BIN"
  rm -rf -- "$tmp_dir"
  trap - EXIT
fi

if [[ "$("$UV_BIN" --version)" != "uv $UV_VERSION" ]]; then
  log "Local uv differs from pinned $UV_VERSION. Preserve it and remove or replace $UV_BIN, then rerun setup."
  exit 2
fi

log "Installing isolated CPython $PYTHON_VERSION (the shared supported version)."
"$UV_BIN" python install "$PYTHON_VERSION"
MANAGED_PYTHON="$("$UV_BIN" python find --managed-python "$PYTHON_VERSION")"
CANONICAL_PYTHON="$AGENT_ROOT/$PYTHON_RELATIVE"
if [[ ! -e "$CANONICAL_PYTHON" ]]; then
  canonical_home="$(dirname "$(dirname "$CANONICAL_PYTHON")")"
  if [[ -e "$canonical_home" || -L "$canonical_home" ]]; then
    log "Incomplete canonical Python directory; preserve it and repair explicitly: $canonical_home"
    exit 2
  fi
  ln -s "$(dirname "$(dirname "$MANAGED_PYTHON")")" "$canonical_home"
fi
[[ "$("$CANONICAL_PYTHON" --version)" == "Python $PYTHON_VERSION" ]] || {
  log "Canonical Python version differs; existing runtime was preserved."; exit 2;
}


log 'Creating the main analysis environment.'
"$BOOTSTRAP_PYTHON" -m stemwise_engine.environment_locks main --check-inputs
"$BOOTSTRAP_PYTHON" -m stemwise_engine.environment_locks allin1 --check-inputs
install_deno
"$BOOTSTRAP_PYTHON" -m stemwise_engine.environment_bootstrap main

log 'Creating an isolated All-In-One environment to avoid dependency conflicts.'
bash "$PROJECT_DIR/setup_allin1_cpu.sh"

# These independently retained numerical environments are not rebuilt by main setup.
# Bind their existing interpreters to this checkout without resolving numerical dependencies.
for retained_python in "$AGENT_ROOT/runtime/espnet-202511-owsm/.venv/bin/python" \
                       "$AGENT_ROOT/runtime/nemo-speech-3.0.0/.venv/bin/python"; do
  if [[ -x "$retained_python" ]]; then
    "$BOOTSTRAP_PYTHON" -m stemwise_engine.runtime_environment \
      timeout 120 flock --exclusive --wait 30 "$PROJECT_DIR/stemwise-heavy-worker.lock" \
      "$UV_BIN" pip install --python "$retained_python" --no-deps --no-build-isolation --editable "$AGENT_ROOT"
  fi
done

log 'Running import and GPU smoke checks.'
"$BOOTSTRAP_PYTHON" -m stemwise_engine.runtime_environment "$CORE_PYTHON" -B -m stemwise_engine.doctor \
  --allin1-python "$ALLIN1_PYTHON" \
  --deno "$DENO_BIN"

log 'Setup complete. Run: .\\stemwise.ps1 inventory'
