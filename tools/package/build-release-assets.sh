#!/usr/bin/env bash
# Build the two files a release ships, and nothing else:
#
#   build/release/curvemaps.apk              the app, with NO map data inside
#   build/release/queensland.motomap         the map: tiles + routing graph + geocoder
#   build/release/queensland.motomap.sha256  distribution checksum
#
# The APK is the debug-signed build with -Pmoto.bundleData=false (no
# graph-cache, no geocoder index; see app/build.gradle.kts), so one install plus
# ONE data file replaces the old APK + separate PMTiles pair. Requires the
# generated pipeline outputs (tools/test/preflight.sh checks them) and Go.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export JAVA_HOME
OUT="build/release"
APK="app/build/outputs/apk/debug/app-debug.apk"

rm -rf "$OUT"
mkdir -p "$OUT"

./gradlew :app:assembleDebug -Pmoto.bundleData=false

# The distributed APK must not carry map data: that is the whole point.
LISTING="$(unzip -l "$APK")"
if grep -Eq 'assets/(graph-cache|geocoder)/' <<<"$LISTING"; then
    printf 'ERROR: %s still contains bundled map data (graph-cache/geocoder).\n' "$APK" >&2
    exit 1
fi
grep -q 'assets/style.json' <<<"$LISTING" || { printf 'ERROR: %s has no style.json\n' "$APK" >&2; exit 1; }
cp "$APK" "$OUT/curvemaps.apk"

tools/package/pack-queensland.sh "$OUT"
rm -f "$OUT/manifest.json"

# One data file, accepted as-is by Load map file: its header must say so.
[[ "$(head -c 4 "$OUT/queensland.motomap" | xxd -p)" == "504b0304" ]] ||
    { printf 'ERROR: queensland.motomap is not a ZIP-container package\n' >&2; exit 1; }
printf 'Release assets in %s:\n' "$OUT"
ls -l "$OUT"
