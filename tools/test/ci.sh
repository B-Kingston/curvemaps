#!/usr/bin/env bash
# Run the cheap, emulator-free verification tier.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"

JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export JAVA_HOME

tools/test/preflight.sh
GH_JAR="tools/gh/graphhopper-web-11.0.jar"
[[ -f "$GH_JAR" ]] || { printf 'ERROR: missing %s (download the pinned GraphHopper web jar)\n' "$GH_JAR" >&2; exit 1; }
"$JAVA_HOME/bin/java" -cp "$GH_JAR" tools/gh/MotoGraphImport.java --self-test
# Screenshot gallery tool: registry ↔ visual.py labels, classification, page output.
python3 -m unittest tools/test/test_gallery.py
JAVA_HOME="$JAVA_HOME" ./gradlew :geocoder-tool:test :app:testDebugUnitTest :app:assembleDebug

# The distributed artifacts: a map-less APK plus the one Queensland package
# that replaces its bundled data (needs Go for the `motomap pack` command).
# Rebuild the default (bundled) debug APK afterwards: the instrumented tiers
# and `tools/android.sh` expect it at the usual path.
if command -v go >/dev/null 2>&1; then
    tools/package/build-release-assets.sh
    JAVA_HOME="$JAVA_HOME" ./gradlew :app:assembleDebug
else
    printf 'NOTE: Go not found; skipping the release-asset (map-less APK + .motomap) check.\n'
fi

# The Go modules; run their suites when Go is available (the -race runs live
# in .github/workflows/ci.yml).
if command -v go >/dev/null 2>&1; then
    (cd tools/motomap && go vet ./... && go test ./...)
else
    printf 'NOTE: Go not found; skipping the Go suites.\n'
fi

printf 'Light CI tier passed.\n'
