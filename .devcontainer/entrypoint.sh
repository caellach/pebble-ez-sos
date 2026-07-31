#!/bin/bash
set -euo pipefail

SDK_ROOT="/home/pebble/.local/share/pebble-sdk"

# Keep a stable SDK location if HOME differs from the image user
if [ -n "${HOME:-}" ] && [ ! -e "${HOME}/.local/share/pebble-sdk" ]; then
  mkdir -p "${HOME}/.local/share"
  ln -sf "${SDK_ROOT}" "${HOME}/.local/share/pebble-sdk"
fi

export PATH="/home/pebble/.local/bin:${SDK_ROOT}/SDKs/current/toolchain/arm-none-eabi/bin:${PATH}"

# WSLg / X11: default display if host did not inject one
if [ -z "${DISPLAY:-}" ]; then
  export DISPLAY=:0
fi

exec "$@"
