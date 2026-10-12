#!/usr/bin/env bash
# Seed a Queensland fixture before a connected task launches any Activity.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
APK="app/build/outputs/apk/debug/app-debug.apk"
MAP_ARCHIVE="data/tiles/queensland.pmtiles"
FIXTURE="build/test-fixtures/queensland.motomap"
[[ -s "$APK" && -s "$MAP_ARCHIVE" ]] || { printf 'Build the debug APK and Queensland PMTiles before seeding the fixture.\n' >&2; exit 1; }
mkdir -p "$(dirname "$FIXTURE")"
python3 - "$MAP_ARCHIVE" "$FIXTURE" <<'PY'
from pathlib import Path
import sys
import zipfile
source, output = map(Path, sys.argv[1:])
with source.open('rb') as stream:
    if stream.read(8) != b'PMTiles\x03':
        raise SystemExit('Queensland fixture must be PMTiles v3')
with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_STORED) as archive:
    archive.write(source, 'tiles/basemap.pmtiles')
PY
"$ADB" install -r "$APK" >/dev/null
"$ADB" shell 'run-as com.curvemaps.app mkdir -p files/fixtures'
"$ADB" shell "run-as com.curvemaps.app sh -c 'cat > files/fixtures/queensland.motomap'" < "$FIXTURE"
