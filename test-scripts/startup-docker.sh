#!/usr/bin/env bash
# Run the whole suite inside Docker — no local Java/Maven/browsers needed.
# Usage: ./test-scripts/startup-docker.sh

set -euo pipefail

cd "$(dirname "$0")/../Docker"

if [ ! -f "../TestData.properties" ]; then
    echo "Missing ../TestData.properties. Copy TestData.properties.example and fill in real values." >&2
    exit 1
fi

docker compose up --build
