#!/usr/bin/env bash
# Assemble the Queensland `.motomap` from the already-generated pipeline
# outputs (tiles, graph, geocoder) — the same bytes the APK tests use — so a
# release ships ONE data file instead of a bare PMTiles archive.
#
#   tools/package/pack-queensland.sh [OUT_DIR]      (default: data/package)
#
# Inputs (all produced by the pinned pipeline; see AGENTS.md):
#   data/queensland.osm.pbf               source extract (hash recorded)
#   data/tiles/queensland.pmtiles         tools/tiles/build-tiles.sh
#   data/graph-cache                      tools/gh/MotoGraphImport.java
#   app/src/main/assets/geocoder/geocoder.dat   :geocoder-tool
# The manifest records the extract's date and URL. They come from
# OSM_EXTRACT_DATE (env, else the value pinned in .github/workflows/ci.yml — the
# single source of truth the pipeline downloads with), never a second copy.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"
OUT_DIR="${1:-data/package}"
case "$OUT_DIR" in /*) ;; *) OUT_DIR="$REPO_ROOT/$OUT_DIR" ;; esac

for f in data/queensland.osm.pbf data/tiles/queensland.pmtiles data/graph-cache \
         app/src/main/assets/geocoder/geocoder.dat; do
    [[ -e "$f" ]] || { printf 'ERROR: missing %s (run the data pipeline first)\n' "$f" >&2; exit 1; }
done

OSM_EXTRACT_DATE="${OSM_EXTRACT_DATE:-$(sed -n 's/^ *OSM_EXTRACT_DATE: *"\([0-9]*\)".*/\1/p' .github/workflows/ci.yml | head -n1)}"
[[ "$OSM_EXTRACT_DATE" =~ ^[0-9]{6}$ ]] || { printf 'ERROR: no OSM_EXTRACT_DATE (set it, or pin it in ci.yml)\n' >&2; exit 1; }

cd tools/motomap
go run ./cmd/motomap pack \
    --repo-root "$REPO_ROOT" \
    --region-id queensland \
    --name Queensland \
    --coverage "Queensland, Australia" \
    --source-date "$OSM_EXTRACT_DATE" \
    --source-url "https://download.geofabrik.de/australia-oceania/australia/queensland-${OSM_EXTRACT_DATE}.osm.pbf" \
    --source "$REPO_ROOT/data/queensland.osm.pbf" \
    --tiles "$REPO_ROOT/data/tiles/queensland.pmtiles" \
    --graph "$REPO_ROOT/data/graph-cache" \
    --geocoder "$REPO_ROOT/app/src/main/assets/geocoder/geocoder.dat" \
    --out "$OUT_DIR"
