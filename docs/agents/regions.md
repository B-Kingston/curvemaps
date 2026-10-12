# Regional packages, Load map file, the hosted map service

A `.motomap` is a versioned ZIP64 archive of vector tiles + GraphHopper graph +
geocoder index. Format and pack/verify tool: `tools/motomap`
(`map-assets.md`). Packages are built and hosted by the CurveMaps map service.

## The hosted service (app-relevant facts)

- **Public origin** `https://maps.curvemaps.app`: package downloads, the
  catalog and health. With the builder offline the catalog still answers and
  health reports `generation.enabled=false`.
- ~21 curated regions are prebuilt and refreshed nightly (01:00–06:00
  Brisbane); anything else Geofabrik publishes builds on request up to the
  size cap.
- **The service runs this repo's pipeline** (`tools/tiles`, `tools/gh/*`,
  `:geocoder-tool`, `tools/motomap` via `go.mod` replace), and its fingerprints
  hash those inputs (`pack.ToolHashes`).
  - A pipeline input change: commit with the `CACHE_SEED` bump, then redeploy
    the service.
    Every curated package rebuilds over the next nights and riders
    see **Update** (a full re-download each), so do not change inputs casually.
  - A `tools/motomap` schema change ships in the app no later than the server
    produces it (the importer rejects unknown fields).
  - A weighting/profile change makes older packages unloadable: rebuild the
    hosted set before releasing such an app.
- **Paid-access hooks (no billing yet):** catalog entries carry `tier`
  (`free`/`pro`); the Worker answers HTTP 402
  `{"error":"CurveMaps Pro required","tier":"pro"}` under its `PRO_POLICY`; the
  app treats 402 as a non-transient, explained failure. **Never DRM `.motomap`
  files**: the ODbL forbids technical restrictions on derived databases unless
  an unrestricted copy is offered. Paid value stays on the server.
- **API the app uses** (`region/net/RegionServerClient.kt`), same origin as the
  configured server: `GET /api/v1/health`, `GET /api/v1/csrf`,
  `GET /api/v1/catalog`, `GET /api/v1/extracts?q=|lat=&lon=`,
  `POST /api/v1/builds` (`{"regionId"}` or `{"extractId"}`, CSRF header +
  cookie), `GET /api/v1/builds/{id}`, resumable
  `GET /api/v1/artifacts/{region}/{fingerprint}/{region}.motomap[.sha256]`
  (Range/If-Range/ETag).

## Fixed server and the offline guarantee

- The app always uses `BuildConfig.DEFAULT_MAP_SERVER` (`-Pmoto.mapServer`,
  default `https://maps.curvemaps.app`, empty = none) via
  `RegionSettings.defaultServer`. No address field, custom server or insecure
  switch; the old `server_address` pref is dropped on start. Debug builds keep
  a test-only override (`RegionSettings.serverAddress` setter +
  `allowInsecureLocal`, private/loopback HTTP only) for loopback suites.
- Nothing contacts the server until the rider taps **Browse maps**
  (`checkServer`); after one successful connect, `RegionSettings.autoConnect`
  refreshes the catalog when Maps opens (`onMapsOpened`).
- `INTERNET` is for downloads only. All network APIs and URL schemes live in
  `app/src/main/java/com/curvemaps/app/region/net/`. `OfflineGuaranteeTest`
  fails if any other production source contains `HttpURLConnection`,
  `java.net.*` socket/URL APIs, `http://` or `https://`, if an offline package
  imports `region.net`, or if the release manifest enables cleartext. Debug may
  allow plain HTTP to private/loopback addresses (`LocalNetworkPolicy`).

## On-device storage (`region/RegionRegistry.kt`)

- `{filesDir}/regions`: `installs.json`, `active.json` (monotonic generation),
  immutable `installs/<region>-<fingerprint12>/` directories. Activation
  rewrites only the active record; a corrupt active install falls back to
  bundled Queensland.
- `InstalledRegion.bytes` is the real on-disk footprint, recomputed on every
  `list()`; `list(withSizes = false)` skips the walk for decisions.
- `registry.install` refuses to replace a registered copy sharing its directory
  (12-character fingerprint prefix) instead of deleting it.
- `RouteScreen` keys `GraphHopperRouter`, `GeocodeSearchController` and the map
  style on `(installId, generation)`. Saved rides are untouched; out-of-region
  points get an honest message (`describeRoutingFailure`).

## `RegionManager` flows

- **Download & install** (`downloadAndInstall`): resumable private download →
  SHA-256 check → `registry.install` → activate (`INSTALLING`). Needs free
  space ≥ 2.2× the package. Mid-ride it installs but sets `pendingActivation`
  (persisted in `regions/pending-activation.json`); `setRideActive(false)`
  activates it under the same `mapChanges` lock.
- **Save a copy to Files** (`startDownload`, `Download package <id>`): the
  older copy-to-SAF path, followed by an explicit import.
- **Builds the rider asked for** install themselves on completion: a catalog
  region's **Build this map**, or a **Find a region** result
  (`searchExtracts`/`searchNearMe` → `GET /api/v1/extracts`,
  `requestExtractBuild` → `POST /api/v1/builds {"extractId"}`). **Near me** uses
  the last fix and appears only when location is already granted. **Stop
  waiting** (`stopWaiting`) stops polling locally; the server keeps building.
  Browse maps re-attaches to a build still running.
- An interrupted one-tap download resumes without a picker
  (`InterruptedDownload.installMode`, `resumeInstall`). A terminal build job is
  informational: the row keeps offering Download or Retry (`RegionUiModel`).
  Failed/cancelled downloads keep their staged partial; a completed staged file
  from the old private flow is offered for save/import (`RegionDownloadStore`).
- **Updates, one copy per region:** with the catalog loaded, each installed row
  compares its `pipelineFingerprint` with the catalog artifact's
  (`RegionUiModel.installedUpdate`/`updateAvailable`): equal → "Up to date";
  different with a `sourceDate` not older → **Update** (`Update <name> map`;
  catalog row `Update map <id>`); otherwise why. Before the catalog loads: one
  **Check for updates** (`Check for map updates`).
- **`settleInstall`** (every install path: download, Load map file, offline
  import, recovered package): a package for a region already on the device
  replaces it. The new copy is active if the old one was active or the pending
  activation, if it came from Load map file, or if the device has no usable map
  (`RegionDataset.dataPresent` false); otherwise it stays inactive. A copy being
  deleted is never replaced; installing that very copy again cancels its
  deletion.
- **Pruning** (`pruneSuperseded`): deletes older copies
  (`RegionUiModel.supersededInstalls`/`keptCopy`: the highest
  `InstalledRegion.installSequence` per region wins, a local counter, never the
  clock; never the active copy or a pending removal; `installSequence` 0 copies
  are kept until the region is installed again) once `RegionReaders` show them
  drained, each through `RegionRegistry.removeIfUnchanged(installId,
  installSequence)` so a racing reinstall keeps its copy. Every deletion path
  uses it. A throwing deletion lands in `MapsState.failedRemovals` and is
  listed again ("A newer copy replaced this one. Delete it to free the
  space.", no Activate). A replaced copy waiting to go is hidden
  (`visibleInstalls`); the active older copy's REPLACED line says it "takes
  over when this ride ends" only when the newer copy is the pending activation.
- **Deleting** (`requestRemove`): an inactive map goes directly; the active map
  first switches to the bundled dataset (generation bump) and is deleted after
  `onDatasetReadersReleased` confirms `GraphHopperRouter.closeAsync()` drained
  (a persisted pending removal finishes on the next start). Refused mid-ride;
  never touches a `.motomap` the rider saved in Files.

## Transfer reliability (`region/net/RegionHttpClient.kt`, `region/RegionDownloadReliability.kt`)

- ETag and size are persisted in `onResponse` the moment the server answers,
  so an interrupted transfer always resumes; partials resume with `Range` (plus
  `If-Range` when the ETag is known); a 206 for the wrong offset discards the
  partial.
- `RegionHttpException.transient`: drops, stalls, 5xx, 408/429 and truncation
  retry (`DownloadRetryPolicy`: backoff 2–30 s, `Retry-After` honoured, the
  counter resets after progress, 5 stalled tries then manual Resume keeping the
  partial). 4xx, TLS trust and redirects surface at once with an actionable
  message. API GETs retry one transient failure.
- A finished download is SHA-256-verified against the catalog/job digest
  (`VERIFYING`) before anything reaches the rider's file; a mismatch discards
  it. Server-supplied URLs must stay on the configured origin. Job polling
  backs off while unreachable and stops with a message on 404.

## Load map file and the map-less build

- **Load map file** (`RouteScreen` picker) sniffs bytes (`region/MapFileKind`),
  never names: `PACKAGE` (ZIP) → `RegionManager.loadPackageAndActivate`
  (install + activate, refused mid-ride, errors thrown to the caller);
  `PMTILES` v3 → legacy `OfflineTileStore.import` + bundled activation, refused
  when `!RegionDatasets.bundledAvailable`; anything else → a clear error.
- A map-less build with nothing installed resolves to `RegionDataset.dataPresent
  = false` ("No map installed"): `MissingMapCard` ("Map package needed", Choose
  map file + Open Maps), routing fails with `NO_MAP_INSTALLED_MESSAGE`, Maps
  hides the bundled row. Upgrading from a bundled build leaves its old
  `gh-cache`/`geocoder` files unused on disk.

## Maps screen

- Material 3 (`Maps.dc.html`, `MapsCatalog.dc.html`, `DeleteMap.dc.html`): a
  header (Back, "Maps", the one-line purpose, the `Active map <name>` node), then
  one card per job on a 16 dp gap and 20 dp padding: **Map service** (filled
  card, cloud tile, Connected / Not connected pill, **Browse maps** / **Refresh
  maps** `Button`, and **Check for updates** `TextButton` while installed maps
  exist and the catalog is not loaded), the transfer in progress (a
  secondary-container card with `LinearWavyProgressIndicator`, percentage and
  **Cancel download**; the warning container while a dropped connection
  retries), **Find a region** (a 56 dp pill field with the Search icon button
  and the **Near me** tonal button inside it), **Available regions**, saved /
  recovered / interrupted package cards, **Installed maps** (`ListItem` rows with
  an Active capsule, Up to date check, delete `IconButton`, **Update** `Button`
  and **Activate** tonal button) and the outlined **Offline import** card. The
  catalog stays inline in this screen (the mockup draws it as its own screen).
  Delete is an `AlertDialog` with `RegionUiModel.deleteConfirmation` split into
  paragraphs, Cancel (`TextButton`) and an error-coloured Delete. "It installs
  itself when it's ready." is a dismissible `Snackbar` (`Dismiss hint`) while a
  followed build runs. Status colours are `CurveMapsTheme.colors` success /
  warning and the M3 error roles. The service summary node
  `Map service <name>` is the screen marker tests use; the active map is one
  node `Active map <name>`. The planner toolbar is hidden while the page is open
  (`MapsSettingsTest` pins that).
- Labels: `Browse maps`/`Refresh maps`, `Download and install <id>`,
  `Build map <id>`, `Retry package <id>`, `Stop waiting for <id>`,
  `Filter regions`, `Clear region filter`, `Region search`, `Search regions`,
  `Regions near me`, `Get map <id>`, `Check for map updates`, `Dismiss error`,
  `Dismiss message`, `Dismiss hint`. A catalog row's one-tap action is an icon
  button (download, cloud download for a build, refresh to retry) described by
  `Download and install <id>` etc.; an update reads **Update**.
- **Catalog grouping** (`region/CatalogGrouping.kt`): by the first segment of
  the Geofabrik `coverage` path, a section per middle segment after standalone
  regions, alphabetical, path-less regions last under "Other regions".
  Continents are collapsible (`"<name>, N regions"`), closed unless they hold a
  followed build or an installed region, or the catalog has one group. Over 8
  regions a filter field matches every word across name/id/coverage and opens
  every match. Rows: name (PRO badge), size, readable data date
  (`RegionUiModel.catalogSubtitle`), "Installed on this device", the one-tap
  action, "Save a copy to Files".

## Tests

- Go: `tools/motomap` (`go test -race`: writer/verifier incl.
  tampered/duplicate/zip-slip entries, pack rules), `pack_test.go`.
- JVM: `RegionPackageTest`, `RegionArchiveImporterTest` (hostile archives + the
  committed Monaco package), `RegionRegistryTest` (installed bytes, install
  numbers, `removeIfUnchanged` vs a reinstall, active copy never removed,
  prefix collision), `RegionUiModelTest`, `MapUpdateModelTest` (update decision,
  newest copy by install number, failed deletions; randomized invariants),
  `RegionDownloadStoreTest`, `RegionPendingRemovalStoreTest`,
  `RegionPackageTransferTest`, `RegionDownloadNamingTest`,
  `VisualMapsScenariosTest`, `RegionHttpClientTest` (socket server:
  Range/If-Range/416/truncation/cancel), `RegionServerClientTest`,
  `LocalNetworkPolicyTest`, `RoutingFailureMessageTest`, `CatalogGroupingTest`,
  `MapFileKindTest`, `OfflineGuaranteeTest`.
- Instrumented: `RegionSwitchOnDeviceTest` (real Monaco package: routing,
  geocoder, fallback, corruption), `MapsDownloadFlowOnDeviceTest` (loopback
  downloads, saves, imports, recovery, removals and drains, mid-ride refusal and
  deferral, one-tap install, updates and pruning across restarts, no contact
  with the default service on a fresh install), `MapsSettingsScreenTest`,
  `MapsSettingsTest`, `LoadMapFileOnDeviceTest`, `MissingMapPromptTest` (both
  flavours, no skips), `QueenslandPackageOnDeviceTest` (package vs bundled
  parity; `tools/test/seed-package-fixture.sh`), `SettingsMenuTest`.
- Fuzz `OpenMapsSettings` (`UiTarget.MAPS_SETTINGS`), the Maps oracle, the
  `maps-settings.json` seed.
- `visual.py maps-settings` captures the service before Browse, the 22-region
  `catalog-world` preview (collapsed, Europe expanded, a "cali" filter), update
  rows, and debug-preview connected / install / build / search / installing /
  download / import / delete / deferred states without a server or SAF picker.
  `visual.py first-run [--bundled]` captures the no-map card, Maps and Back.
