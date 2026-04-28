#!/usr/bin/env bash
# check-e2e-coverage.sh
#
# Reads the scoverage XML report produced by `sbt coverageE2e` and fails
# if statement coverage is below E2E_COVERAGE_THRESHOLD (default 70%).
#
# Usage:
#   ./scripts/check-e2e-coverage.sh           # uses default threshold 70
#   E2E_COVERAGE_THRESHOLD=80 ./scripts/check-e2e-coverage.sh

set -euo pipefail

THRESHOLD="${E2E_COVERAGE_THRESHOLD:-70}"
REPORT_XML="backend/http_server/target/e2e-scoverage/scoverage.xml"
REPORT_HTML="backend/http_server/target/e2e-scoverage-report/index.html"

if [ ! -f "$REPORT_XML" ]; then
  echo "ERROR: E2E coverage report not found at $REPORT_XML"
  echo "       Run 'sbt coverageE2e' first (from the backend/http_server/ directory)."
  exit 1
fi

# Extract statement-rate from the root <scoverage> element attribute
RATE=$(grep -oE 'statement-rate="[0-9.]+"' "$REPORT_XML" | head -1 | grep -oE '[0-9.]+')

if [ -z "$RATE" ]; then
  echo "ERROR: Could not parse statement-rate from $REPORT_XML"
  exit 1
fi

# Convert decimal rate (e.g. 0.857) to integer percentage (85)
COVERAGE_PCT=$(awk "BEGIN { printf \"%d\", $RATE * 100 }")

echo "E2E statement coverage: ${COVERAGE_PCT}%  (threshold: ${THRESHOLD}%)"
echo "Full HTML report:       $REPORT_HTML"

if [ "$COVERAGE_PCT" -lt "$THRESHOLD" ]; then
  echo "FAILED: coverage ${COVERAGE_PCT}% is below threshold ${THRESHOLD}%"
  exit 1
fi

echo "PASSED"
