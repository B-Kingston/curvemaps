# Routing graph pipeline and the pre-compiled weighting

The GraphHopper graph is built on the desktop. Bundled builds ship it in
`assets/graph-cache`, which `GraphHopperRouter` copies to `{filesDir}/gh-cache`
on first use and loads via MMAP; map-less builds load it from an installed
`.motomap` (`regions.md`). **The app never imports a PBF on the device.**

## Rebuilding the graph

1. Put an OSM extract at `data/queensland.osm.pbf` (or set `datareader.file` in
   `tools/gh/config.yml`; relative paths resolve against the repo root, so run
   from there). Geofabrik serves region extracts three levels deep
   (`/australia-oceania/australia/queensland-YYMMDD.osm.pbf`); shallower paths
   return an HTML index with HTTP 200, so verify the bytes are a PBF. CI pins
   `OSM_EXTRACT_DATE` because the `latest` alias loops through a cached redirect.
2. Import (JDK 17, ~1 min for a state, plus ~8 min the first time for SRTM tiles):
   ```sh
   JAVA_HOME=/opt/homebrew/opt/openjdk@17
   rm -rf data/graph-cache   # the importer refuses to run over an existing graph
   $JAVA_HOME/bin/java -Xmx12g -cp tools/gh/graphhopper-web-11.0.jar tools/gh/MotoGraphImport.java import tools/gh/config.yml
   ```
   Never use the stock `-jar … import`: its graph lacks lane guidance and
   stops (preflight and `GraphAndGeocoderAssetIntegrityTest` reject it).
3. `cp -R data/graph-cache app/src/main/assets/graph-cache`, then rebuild.
4. Bump `CACHE_SEED` in `.github/workflows/ci.yml` (root `AGENTS.md`).

Sanity check: `java -jar tools/gh/graphhopper-web-11.0.jar server
tools/gh/config.yml` binds port **8080** (not 8989), then
`curl "http://localhost:8080/route?point=-27.4679,153.0281&point=-16.9203,145.7710&profile=motorcycle"`.

## Hard constraints

- `graphhopper-core` in `gradle/libs.versions.toml` MUST equal the import jar
  version in `tools/gh/` (the stored format is version-bound). Bump both and
  check Maven Central first (10.3 does not exist; 11.0 is current).
- **Osmosis is excluded from the app.** `app/build.gradle.kts` drops
  `org.openstreetmap.osmosis:osmosis-osm-binary` (LGPL-3.0, brought
  protobuf-java) from `graphhopper-core`; GraphHopper's PBF reader stays as
  unreachable dex code with targeted `-dontwarn` rules in
  `app/proguard-rules.pro`. Release builds run R8, which keeps
  `com.graphhopper.**` and Jackson's core/databind/annotations whole: loading
  a graph reads its stored profile and encoded values back through Jackson
  reflectively. Check a release build still routes after a GraphHopper bump
  (`assembleRelease`, then plan a route). Guards: `checkNoOsmosisInApp` (a `preBuild`
  dependency) and JVM `OsmosisExcludedFromAppTest`. After a GraphHopper bump,
  re-check loading without the jar (`GraphCopyTest`, `RouteCorpusTest`,
  `RegionSwitchOnDeviceTest`). Adding any LGPL/GPL dependency needs a licensing
  decision (`THIRD_PARTY.md`); never weaken the guard. `:geocoder-tool` uses
  Osmosis legitimately on the desktop and is not distributed.
- Profile name `motorcycle` matches in `config.yml`, `Profile("motorcycle")` in
  `GraphHopperRouter`, and `GHRequest.setProfile("motorcycle")`.
- `config.yml` keeps `custom_model_files: [motorcycle.json]`,
  `import.osm.ignored_highways` and the `graph.encoded_values` line (import
  fails without each). The app-side `GraphHopperConfig` must carry the same
  `import.osm.ignored_highways` or GraphHopper 11's `init()` throws
  `Missing 'import.osm.ignored_highways'`.
- **Profile hash.** `GraphHopperRouter.motorcycleProfile()` mirrors the YAML
  profile exactly, including PMap hint order (`custom_model_files` then
  `custom_model`, with `Profile(String)`'s default `custom_model` hint removed
  first) and the JAR's built-in motorcycle statements. The load-time check
  compares the stored `profiles` property against a hash of the whole hints
  PMap: the current graph stores `profiles=motorcycle|198752012`, reproduced
  only when `custom_model_files` is `List.of("motorcycle.json")`.
- `custom_model_files: [motorcycle.json]` resolves to the JAR's built-in
  `/com/graphhopper/custom_models/motorcycle.json`
  (`GraphHopper.resolveCustomModelFiles`), never
  `tools/gh/motorcycle.json`. A filesystem file with that name aborts the import
  (`Custom model file name 'motorcycle.json' is already used for built-in profiles`), so `custom_models.directory` must
  not point at a folder containing it. `tools/gh/motorcycle.json` is the
  checked-in reference copy and must stay identical to the built-in.
- **3D graph.** `config.yml` imports NASA SRTM3 elevation
  (`graph.elevation.provider: srtm`, public domain; tiles cached in
  `data/elevation/srtm`; Ramer smoothing at 5 m) and `urban_density` (`graph.urban_density.threads`). Neither
  changes the profile hash. GraphHopper refuses a graph whose dimension differs
  from its config and older `.motomap` files are 2D, so
  `routing/MotoGraphLoader.kt` reads the dimension from header int 7 of the
  `nodes` file (`graphHasElevation`, pinned by `MotoGraphLoaderTest`) and calls
  `setElevation` after `init`. Every loader (router, desktop probes) goes
  through `loadMotoGraphHopper`. 3D edge distances include the vertical
  component. The map server rewrites `graph.elevation.cache_dir`
  (`GenerateGraphConfig`). Preflight and `GraphAndGeocoderAssetIntegrityTest`
  reject a 2D graph or one without `moto_stops`/`urban_density`.

## `MotoGraphImport` (edge KV data)

`tools/gh/MotoGraphImport.java` is GraphHopper 11's `import` run through JDK
17's single-file launcher with extra way-tag parsers that write **edge KV
storage** (edge flags are 57/64 bits; KV costs ~1 MB for Queensland). It works
because `OSMReader.addEdge` runs way-tag parsers before writing the way's
`key_values` map. The profile, encoded values and custom model are untouched,
so the profile hash and pre-compiled weighting stay valid.

- `moto_lanes` per direction (format in its KDoc: `L|R:` driving side from
  `Country`, then left-to-right lanes, each `?` count-only / empty unmarked /
  subset of `lsru`). Counts come from `lanes:forward/backward`, `lanes` on
  oneways (incl. implied motorway/roundabout), or an even two-way split;
  `turn:lanes*` arrows win when their count agrees; typos/unknown values are
  never guessed. App side: `ride-guidance.md`.
- `moto_stops` (`MotoGraphImport.StopTagParser`): half stops per direction for signals,
  pedestrian lights, stop/give-way signs, mini roundabouts;
  `direction`/`traffic_signals:direction` respected; end nodes count half so a
  junction counts once. The parser must also CLEAR the key, because OSMReader
  reuses one `key_values` map per way. App side: `routing.md` (Ride factors).
- `--self-test` pins both (run by `ci.sh`).
- Changing `MotoGraphImport.java` changes graph content: bump `CACHE_SEED`.
  `tools/motomap` `ToolHashes` (and the hosted service's build allowlist) hash
  it, so the hosted service rebuilds.

## Custom-model weighting on Android (Janino does not work on ART)

GraphHopper compiles `weighting=custom` with Janino, which emits JVM bytecode
ART cannot load (`Could not create weighting for profile: 'motorcycle'` /
`Cannot compile expression: can't load this type of class file`). The compiled weighting ships as source:

- `app/src/main/java/com/graphhopper/routing/weighting/custom/MotorcycleWeightingHelper.java`
  is the Janino-generated `CustomWeightingHelper` subclass (verbatim except the
  class name; package stays `com.graphhopper.routing.weighting.custom`).
- `routing/MotorcycleWeightingFactory.kt` implements `WeightingFactory`,
  mirroring `DefaultWeightingFactory`'s `custom` branch with the pre-compiled
  helper; `GraphHopperRouter` installs it by overriding `createWeightingFactory()`.
  It is restricted to the `motorcycle` profile: non-custom weightings go to
  `DefaultWeightingFactory`, custom ones on other profiles are rejected.
- Regenerate after ANY custom-model change: `./tools/gh/generate-weighting.sh`,
  then adapt the dumped `JaninoCustomWeightingHelperSubclassN.java` (rename only).
- These change together, followed by a graph re-import (the profile hash
  changes): `tools/gh/motorcycle.json`, `GraphHopperRouter.motorcycleProfile()`,
  `tools/gh/GenerateWeighting.java`'s `motorcycleProfile()`, the generated
  helper, and the max-speed math in `MotorcycleWeightingFactory.createParameters()`.
  `generate-weighting.sh` verifies the first three agree before dumping.

Ride styles, toggles and ride factors are hints on top of this model and need
no re-import (`routing.md`).
