#!/bin/sh
# Starts the route rater on http://127.0.0.1:${RATER_PORT:-8765}. Ctrl-C stops it.
# See tools/rater/README.md.
cd "$(dirname "$0")/../.." || exit 1
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export RATER=1
./gradlew :app:testDebugUnitTest --tests 'com.curvemaps.app.rater.RouteRaterServer' --rerun -i 2>&1 \
  | grep --line-buffered -E 'RATER|Exception|^e: |FAILED|BUILD' \
  | grep --line-buffered -v 'resolved during configuration time'
