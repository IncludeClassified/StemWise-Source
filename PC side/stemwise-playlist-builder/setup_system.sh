#!/usr/bin/env bash
set -Eeuo pipefail

if [[ "$(id -u)" != "0" ]]; then
  printf '[stemwise setup] setup_system.sh must run as root.\n' >&2
  exit 2
fi

printf '[stemwise setup] Installing FFmpeg, build tools, and audio libraries.\n'
apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
  ca-certificates curl ffmpeg git python3-venv build-essential cmake ninja-build pkg-config \
  libsndfile1 libsndfile1-dev libfftw3-dev libsamplerate0-dev espeak-ng
