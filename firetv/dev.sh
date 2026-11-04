#!/usr/bin/env bash
# The local loop: build the release APK, install it on the stick with its dex metadata (never a bare
# `adb install`: without the .dm the app runs interpreted), and reopen the app. ~1-2 min, no CI.
# Usage: ./dev.sh  (STICK=ip:port to override the device)
set -euo pipefail
cd "$(dirname "$0")"
adb=~/Library/Android/sdk/platform-tools/adb
stick=${STICK:-192.168.178.25:5555}
# `adb` calls can hang on the stick; cap each one.
a() { perl -e "alarm($1); exec @ARGV" -- "${@:2}"; }

# Usually connected already; a connect that hangs isn't fatal, the install below is the real check.
a 10 "$adb" connect "$stick" >/dev/null 2>&1 || true
./gradlew -q assembleRelease
out=app/build/outputs/apk/release
baselineprofile/dex-metadata.sh "$out/app-release.apk" "$out/app-release.dm" # the .dm name must match the APK's
a 120 "$adb" -s "$stick" install-multiple -r "$out/app-release.apk" "$out/app-release.dm"
a 15 "$adb" -s "$stick" shell am start -n com.mohamed3on.iptv/.MainActivity >/dev/null
a 15 "$adb" -s "$stick" shell dumpsys package com.mohamed3on.iptv | grep -m1 versionName
