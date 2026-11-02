#!/usr/bin/env bash
# Regenerates the app's baseline profile (app/src/main/generated/baselineProfiles/) on a
# Gradle-managed emulator, against the local fixture server. Commit the result.
# Pass "benchmark" to measure the list with and without the profile instead (indicative only).
set -euo pipefail
cd "$(dirname "$0")/.."

bun baselineprofile/fixture-server.ts &
server=$!
trap 'kill $server 2>/dev/null' EXIT
for _ in $(seq 60); do curl -sf -o /dev/null localhost:8791/api/app/channels && break; sleep 1; done

if [ "${1:-}" = benchmark ]; then
  ./gradlew :baselineprofile:tvApi34BenchmarkReleaseAndroidTest
else
  ./gradlew :app:generateBaselineProfile
fi
