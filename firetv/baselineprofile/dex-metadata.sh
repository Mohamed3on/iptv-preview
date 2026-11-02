#!/usr/bin/env bash
# Builds the dex metadata (.dm) for an APK from the baseline profile packaged inside it: installed
# alongside the APK (as base.dm), the platform compiles the app's hot paths at install instead of at
# the next idle maintenance. Usage: dex-metadata.sh app-release.apk app-release.dm
set -euo pipefail
apk=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
out=$(cd "$(dirname "$2")" && pwd)/$(basename "$2")
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
unzip -q -j "$apk" assets/dexopt/baseline.prof assets/dexopt/baseline.profm -d "$tmp"
mv "$tmp/baseline.prof" "$tmp/primary.prof"
mv "$tmp/baseline.profm" "$tmp/primary.profm"
rm -f "$out"
(cd "$tmp" && zip -q -X "$out" primary.prof primary.profm)
