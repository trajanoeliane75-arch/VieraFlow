#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
echo "VieraFlow: compilação Android 0.2.0-rc1"
bash build.sh
