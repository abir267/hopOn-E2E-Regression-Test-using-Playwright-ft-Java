#!/usr/bin/env bash
# Run the suite locally with Maven.
# Usage:
#   ./test-scripts/startup-local.sh                     # full suite (test-suites/testng.xml)
#   ./test-scripts/startup-local.sh test-suites/smoke.xml  # a specific suite file

set -euo pipefail

cd "$(dirname "$0")/.."

SUITE_FILE="${1:-test-suites/testng.xml}"

if [ ! -f "TestData.properties" ]; then
    echo "Missing TestData.properties. Copy TestData.properties.example and fill in real values." >&2
    exit 1
fi

mvn -Dsurefire.suiteXmlFiles="$SUITE_FILE" test
