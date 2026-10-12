# Open-source licences screen

CurveMaps is source-available (PolyForm Shield, not copyleft) and the APK is
distributed under the owner's terms, so it carries the notices its components
require and may contain no copyleft code. Settings card → **Open-source licences** (`OpenSourceLicencesScreen.kt`)
lists map data, fonts and icons first (OpenStreetMap ODbL, OpenMapTiles CC BY
4.0 + BSD-3-Clause, OSM Bright CC BY 4.0, Noto OFL 1.1, Material Symbols
Apache-2.0), then every runtime
library with its licence; a row opens the full text(s) and notices. Everything
is read from the APK (`OfflineGuaranteeTest` still passes: URLs are data).
No graph/tile rebuild or `CACHE_SEED` bump is ever needed here.

## Where the data comes from

- The AboutLibraries Gradle plugin (`com.mikepenz.aboutlibraries.plugin.android`,
  build time only; no runtime library ships) resolves each variant's
  dependency graph into `res/raw/aboutlibraries.json`, so the list cannot drift
  (release lists the Vulkan MapLibre artifact, debug the OpenGL one and its
  debug tooling).
- `offlineMode = true`: every licence text and the data/font/style entries are
  checked in under `app/licences/` (`licenses/*.json`, `libraries/*.json` with
  `"tag": "data"`).
- `collect<Variant>LicenceNotices` (`app/build.gradle.kts`) writes
  `assets/licences/notices.json`: every NOTICE/LICENSE/COPYING file inside the
  variant's shipped jars (full Apache-2.0 copies skipped) plus checked-in
  copyright notices in `app/licences/notices/*.json` for BSD/MIT/EDL components
  whose jars carry none (MapLibre, Janino, JTS, osm-legal-default-speeds,
  slf4j-android).
- The UI parses both with org.json (`licences/LicenceCatalog.kt`, pure;
  `LicenceCatalogLoader` reads the files).

## Keeping it in sync

- An ordinary dependency change needs nothing.
- A licence without text fails `ReleaseLicenceCoverageTest` with its key: add
  `app/licences/licenses/<name>.json` (`hash` = that key; `name`/`url` exactly
  as the POM gives them for a POM-named licence keyed by an MD5; `content`).
- A BSD/MIT/EDL library without a copyright notice in its jar needs
  `app/licences/notices/<name>.json` (`modules`, `name`, `text`).
- The Material Symbols drawables (`ms_*`, `tools/icons/fetch-symbols.sh`) are
  such an entry: `app/licences/libraries/material-symbols.json`, Apache-2.0.
- A new map data/font/style/icon source needs `app/licences/libraries/<name>.json`
  and an entry in `LicenceCatalogParser.DATA_ORDER`.
- Never add an exclusion to hide a dependency. No LGPL is allowed (the Osmosis
  jar is excluded, `graph-pipeline.md`);
  `ReleaseLicenceCoverageTest.everyLicenceAllowsClosedDistribution` fails if it
  returns.

## Navigation

The header (`SettingsScreenHeader`, 48 dp Back) is pinned above list and
reader; the planner toolbar is hidden while the page is open, so the page uses
the full width. Below it, 48 dp filter chips
(`LicenceFilter`: All, Map data, Fonts, Libraries; Fonts covers Noto and
Material Symbols, any other data entry is Map data) sit outside the list, so
every clickable node inside `Open-source licence list` is a licence row. The
list is two grouped sections (data, then "Software libraries (N)") of M3
`SettingsRow`s with a symbol, name, subtitle (`listSubtitle`: coordinates on one
line with a middle ellipsis so the version stays visible, or a data entry's first
sentence on two), the licence tag under the text and a chevron
(`licences/LicenceListModel.kt`, JVM `LicenceListModelTest`). A hairline under the
chips shows where the scrolled list is cut. The reader shows markdown headings
(`##`) as headings and joins hard-wrapped lines into flowing paragraphs
(`licenceBlocks`, JVM `LicenceBlocksTest`). Back closes a text, then the list. The screen's `BackHandler` is disabled while the settings menu is open
over it; opening it closes Maps (one full-screen surface at a time). Test seam
`OssLicencesStateKey` (`loading`/`list`/`detail:<id>`/`error`); descriptions
`Open-source licence list`, `Licence text for <name>`, rows
`<name>, <licences>`.

## Tests

JVM `licences/LicenceCatalogTest` and `licences/ReleaseLicenceCoverageTest`
(reads the REAL release outputs: every test task depends on
`prepareLibraryDefinitionsRelease` and `collectReleaseLicenceNotices`; fails
when a shipped module is unlisted or a listed one unshipped, a licence has no
text or is not closed-distribution compatible (LGPL only for the Osmosis open
item, EPL only beside JTS's EDL), a data entry is missing, or a BSD/MIT/EDL
library has no notice). Instrumented `OpenSourceLicencesTest`, plus
`SettingsMenuTest`, `AccessibilityTest`, `FontScaleTest` (320 dp at 2x),
`CommonScreenSizeTest`; fuzz `OpenLicences` (`UiTarget.OSS_LICENCES`) with the
`Licences*` oracles and `oss-licences.json`. `visual.py oss-licences` captures
`oss-licences-*`.
