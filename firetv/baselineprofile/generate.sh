#!/usr/bin/env bash
# Regenerates the app's baseline profile (app/src/main/generated/baselineProfiles/) on a
# Gradle-managed emulator, against the local fixture server. Commit the result.
set -euo pipefail
cd "$(dirname "$0")/.."

bun baselineprofile/fixture-server.ts &
server=$!
trap 'kill $server 2>/dev/null' EXIT
for _ in $(seq 60); do curl -sf -o /dev/null localhost:8791/api/app/channels && break; sleep 1; done

./gradlew :app:generateBaselineProfile
