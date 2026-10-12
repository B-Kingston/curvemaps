#!/usr/bin/env bash
# Seed the real Queensland `.motomap` (built by tools/package/pack-queensland.sh
# from the same tiles/graph/geocoder the APK tests use) into the app's private
# files/fixtures before QueenslandPackageOnDeviceTest runs.
#
#   ANDROID_SERIAL=<serial> tools/test/seed-package-fixture.sh
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
APK="app/build/outputs/apk/debug/app-debug.apk"
PACKAGE="data/package/queensland.motomap"
[[ -s "$APK" ]] || { printf 'Build the debug APK first.\n' >&2; exit 1; }
tools/package/pack-queensland.sh >/dev/null
[[ -s "$PACKAGE" ]] || { printf 'pack-queensland.sh produced no %s\n' "$PACKAGE" >&2; exit 1; }
"$ADB" install -r "$APK" >/dev/null
"$ADB" shell 'run-as com.curvemaps.app mkdir -p files/fixtures'
"$ADB" shell "run-as com.curvemaps.app sh -c 'cat > files/fixtures/queensland-package.motomap'" < "$PACKAGE"
printf 'Seeded files/fixtures/queensland-package.motomap (%s bytes)\n' "$(wc -c < "$PACKAGE" | tr -d ' ')"
