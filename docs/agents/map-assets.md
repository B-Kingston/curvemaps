# Map assets: tiles, style assets, geocoder, `.motomap` packing, CI caching

Generated, gitignored outputs: `data/`, `app/src/main/assets/graph-cache/`,
`app/src/main/assets/geocoder/`, `app/src/main/assets/glyphs/`,
`app/src/main/assets/sprites/`, `tools/gh/*.jar`, `*.osm.pbf`. A fresh clone
has none of them until the pipeline runs (`graph-pipeline.md` for the graph).

## CI caching and data freshness

CI downloads a dated OSM extract (PBF magic verified), builds tiles, imports
the graph, builds the geocoder index, fetches style assets, then runs
`tools/test/ci.sh`. Ordinary pushes restore the generated-data cache when the
key is unchanged, so they do not pick up a newer extract. The extract also has
its own cache, keyed by `OSM_EXTRACT_DATE` alone and restored on every run, so
a pipeline change rebuilds from it without downloading it again.

Pin a first-of-month extract (`YYMM01`). Geofabrik deletes daily files after
about a week and first-of-month ones after a few months. A pin it has deleted
fails CI as soon as the extract cache is cold (a new date, or a week without
CI runs).

Whenever the source data or any pipeline input changes (new extract,
GraphHopper or Planetiler version, `tools/gh/config.yml`,
`tools/gh/motorcycle.json`, `tools/gh/MotoGraphImport.java`,
`tools/tiles/build-tiles.sh`, geocoder source/format, or a deliberate refresh),
update `OSM_EXTRACT_DATE` (when changing the extract) and bump `CACHE_SEED` in
`.github/workflows/ci.yml` in the same commit (`v1` → `v2`). CI cannot see
changes to ignored `data/` files. A pipeline input change also rebuilds every
hosted package (`regions.md`); a new CI extract alone does not, because the
service picks its own extracts.

## Release artifacts

`tools/package/build-release-assets.sh` writes
`build/release/{curvemaps.apk,queensland.motomap,queensland.motomap.sha256}`
(map-less APK plus one data file). `ci.sh` runs it and re-assembles the bundled
debug APK afterwards. A `v*` tag publishes exactly those three files as a
prerelease (no bare PMTiles).

`tools/package/pack-queensland.sh [OUT]` assembles `queensland.motomap` from the
pipeline outputs with `motomap pack` (`tools/motomap/pack`: checks the graph's
exact `profiles=` marker, pins the source SHA-256, fingerprints by component
digests plus `ToolHashes`, verifies the result). The extract date comes from
`OSM_EXTRACT_DATE`. Packing changes no pipeline input, so no `CACHE_SEED` bump.

`tools/motomap` (standalone Go module, stdlib only) is the `.motomap` contract
shared with the hosted map service: `manifest/` + `motomap/`
(write/verify) mirror `region/RegionPackage.kt`; a schema change updates both
plus `tools/motomap/README.md`. Its `go test -race` runs in the CI `go-tests`
job.

## Basemap tiles (PMTiles)

```sh
tools/tiles/build-tiles.sh   # data/queensland.osm.pbf → data/tiles/queensland.pmtiles
```

- Pins Planetiler `0.10.2` (OpenMapTiles profile, `--output` PMTiles), which
  needs JDK 21: found with `java_home -v 21`, override with `TILE_JAVA_HOME`.
  The Android build still needs JDK 17. `--download` fetches Natural Earth and
  water polygons into `data/sources/`.
- The PMTiles file is a packing input, not a deliverable. The APK never
  contains it. On the phone it lives in the active install, or (legacy, bundled
  builds only) `{filesDir}/tiles/basemap.pmtiles` from Load map file; a failed
  replacement never destroys the installed map. MapLibre reads it with
  `pmtiles://file://` byte-range reads.
- `app/src/main/assets/style.json` is the local style: no remote style, glyph or
  sprite URL. Its only runtime token is `{tiles_path}` (replaced in
  RouteScreen's `loadOfflineStyle`).
- The map must show `© OpenMapTiles.org © OpenStreetMap contributors`.

## Glyphs and sprites

Noto glyph PBFs (3 fontstacks) and the OSM Bright sprite set ship in APK assets
and are read through `asset://` URLs baked into `style.json`
(`asset://glyphs/{fontstack}/{range}.pbf`, `asset://sprites/sprite`).

```sh
tools/style/fetch-style-assets.sh            # fetch what's missing + install into app assets
tools/style/fetch-style-assets.sh --force    # re-download everything
tools/style/fetch-style-assets.sh --verify   # drift-check data/style/ vs the stored manifest
```

- Glyphs: Noto Sans Regular/Bold/Italic (SIL OFL 1.1), from the font-glyphs
  GitHub Pages mirror `https://orangemug.github.io/font-glyphs/glyphs/{fontstack}/{range}.pbf`.
  `fonts.openmaptiles.org` serves an HTML page for every path since 2025
  (`openmaptiles/fonts#26`). Sprites: OSM Bright from the `osm-bright-gl-style`
  gh-pages branch at a resolved commit SHA (CC BY 4.0, covered by the map
  attribution).
- Glyph content is unversioned: `data/style/style-assets.sha256` is the drift
  manifest (`--verify`).
- Fontstack directory names must match `text-font` in `style.json` exactly
  (literal spaces; the AssetManager source also percent-decodes `%20`). Ranges `0-255` and `256-511` are required for every
  fontstack; a 404 on a higher range is a warning. Every icon referenced in
  `style.json` must exist in `sprite.json` (`data/style/icon-names.txt`; script check plus
  `StyleAndSpritesIntegrityTest.referencedIconNamesAllExistInTheSpriteSheet`).
- `file://` does NOT work for glyph/sprite requests on maplibre-android (the
  generic local file source returns NotFound); use `asset://`. PMTiles still
  uses `pmtiles://file://` through its own file source.

## Geocoder index (offline place search)

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :geocoder-tool:run --args="data/queensland.osm.pbf app/src/main/assets/geocoder"
# inspect:
... :geocoder-tool:run --args="--inspect <pbf> app/src/main/assets/geocoder/geocoder.dat brisbane ann cafe fuel qld"
```

- `:geocoder-tool` is a plain JVM module. It reads the PBF via `osmosis-pbf`
  (`crosby.binary.osmosis.OsmosisReader`; `osmosis-osm-binary` has no 0.49.x),
  classifies POI/STREET/LOCALITY features and writes `geocoder.dat`, a
  little-endian inverted index whose format is fixed in
  `tools/geocoder/README.md`. Change the format only with that doc and the
  reader (`geocoding/GeocoderIndex`, `SearchEngine`).
- **Two copies to keep in sync** (`SearchText` normalization is shared):
  `geocoder-tool/src/main/kotlin/com/curvemaps/geocoder/tool/Text.kt` ↔
  `app/src/main/java/com/curvemaps/app/geocoding/Text.kt` (normalization and
  tokenization, pure Kotlin), and `geocoder-tool/src/main/resources/poi_keywords.tsv`
  ↔ the embedded fallback in `PoiKeywords.kt`. `--check-sync` verifies both.
- `:geocoder-tool` deliberately has no `jvmToolchain(17)`: Gradle resolves the
  Homebrew `JAVA_HOME` to the keg root (no `lib/jmods`), which breaks Kotlin
  ("No class roots"). It uses `jvmTarget = 17` on the daemon JVM, like `:app`.
- App side: `RoutePlanSearchField` (`SearchField.kt`, 300 ms debounce, results
  dropdown); `GeocodeSearchController` lazy-loads the index (copied to
  `{filesDir}/geocoder/`, mmapped) on the first search. Typing `lat,lon` (or
  `lat lon`) yields a COORDINATE result, and `PointParser` resolves it on
  START. Ranking constants are ported from Organic Maps' `ranking_info.cpp`.
