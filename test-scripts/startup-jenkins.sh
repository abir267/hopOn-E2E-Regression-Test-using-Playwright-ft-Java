#!/usr/bin/env bash
# CI entrypoint — forces headless mode, prints report paths, exits non-zero on failure.
# Usage: ./test-scripts/startup-jenkins.sh [suite-file]

set -uo pipefail

cd "$(dirname "$0")/.."

SUITE_FILE="${1:-test-suites/testng.xml}"

if [ ! -f "TestData.properties" ]; then
    echo "Missing TestData.properties. Copy TestData.properties.example and fill in real values." >&2
    exit 1
fi

# Force headless regardless of what's in TestData.properties — CI has no display.
sed -i.bak 's/^headless=.*/headless=true/' TestData.properties

mvn -B -Dsurefire.suiteXmlFiles="$SUITE_FILE" test
STATUS=$?

mv TestData.properties.bak TestData.properties

echo "----------------------------------------------------------------------"
echo "Surefire reports : $(pwd)/target/surefire-reports"
echo "Extent report     : $(pwd)/target/ExtentReport/index.html"
echo "Failure artifacts : $(pwd)/target/test-artifacts (screenshots + traces)"
echo "----------------------------------------------------------------------"

exit $STATUS
