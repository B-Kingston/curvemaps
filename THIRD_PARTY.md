# Third-party software and data

CurveMaps is source-available under the PolyForm Shield License 1.0.0
(`LICENSE`); the official app is distributed under the owner's own terms. It
ships or uses the components below, each under its own licence. That
distribution must ship their notices with the app: Apache-2.0 and BSD licences require the licence text and any NOTICE
file in binary distributions. The app ships them on its **Open-source
licences** screen (settings card → Open-source licences): the library list is
generated at build time from each variant's resolved dependency graph (the
AboutLibraries Gradle plugin, build-time only), with the licence texts, the
data/font/style entries below and the upstream copyright notices checked in
under `app/licences/`, and every NOTICE/LICENSE file inside the shipped jars
collected automatically. `ReleaseLicenceCoverageTest` fails the JVM suite when
a release dependency is missing from the screen, lacks licence text or a
required copyright notice, or carries a licence that non-copyleft distribution
cannot honour (see `docs/agents/licences.md`). The tables below were
inventoried from `releaseRuntimeClasspath` on 2026-10-06 and match the screen's
release list on 2026-10-07.

**No LGPL code in the APK.** GraphHopper declares the LGPL-3.0 Osmosis OSM
binary (PBF) jar (`org.openstreetmap.osmosis:osmosis-osm-binary`, which brings
protobuf-java) for its OSM PBF reader. The app never reads PBF files on the
device (graphs are imported on the desktop and only loaded on the phone), so
`app/build.gradle.kts` excludes the jar from the GraphHopper dependency, and
neither it nor protobuf-java ships. Guards: the `checkNoOsmosisInApp` task
runs before every APK build and fails if any Osmosis/OSM-binary module is on
the debug or release runtime classpath, and the JVM suite
`OsmosisExcludedFromAppTest` fails if those classes are loadable or packaged
in any jar on the app's classpath. Routing was re-proven on device without
it. Re-adding it needs a licensing decision first.

## App runtime libraries

| Component | Licence |
|---|---|
| AndroidX, Jetpack Compose, Material 3 | Apache-2.0 |
| Kotlin stdlib, kotlinx.coroutines, kotlinx.serialization | Apache-2.0 |
| MapLibre Native Android SDK, MapLibre gestures | BSD-2-Clause |
| MapLibre GeoJSON and Turf for Android | Apache-2.0 |
| GraphHopper core and web-api (routing, embedded, offline) | Apache-2.0 |
| Jackson (core, databind, annotations, dataformat-xml), Woodstox | Apache-2.0 |
| Stax2 API | BSD-2-Clause |
| HPPC, Guava listenablefuture, Gson, OkHttp, Okio, Timber, Commons IO, Apache XML Graphics Commons, JetBrains annotations, JSpecify | Apache-2.0 |
| Janino and commons-compiler | BSD-3-Clause |
| JTS Topology Suite | EPL-2.0 or EDL-1.0 (used under EDL-1.0, BSD-3-Clause style) |
| osm-legal-default-speeds | BSD-3-Clause |
| SLF4J (api, android) | MIT |
| Public Suffix List (`publicsuffixes.gz` inside OkHttp; its NOTICE is shown on the screen) | MPL-2.0 |

## Desktop tools in this repository

| Component | Licence |
|---|---|
| Osmosis (PBF reader for `:geocoder-tool`; desktop only, never in the APK) | Public domain / LGPL-3.0 (binary format) |
| Planetiler (tile build, fetched at build time) | Apache-2.0 |
| GraphHopper web jar (graph import, fetched at build time) | Apache-2.0 |
| AboutLibraries Gradle plugin (licence-screen data, build time only; nothing ships) | Apache-2.0 |

## Data, fonts and style assets

| Asset | Licence |
|---|---|
| OpenStreetMap data (graph, tiles, geocoder index, `.motomap` packages) | ODbL 1.0, with attribution "© OpenStreetMap contributors" |
| OpenMapTiles schema and style derivation | CC BY 4.0 / BSD-3-Clause, with attribution "© OpenMapTiles.org" |
| OSM Bright sprites | CC BY 4.0 |
| Noto Sans glyphs | SIL OFL 1.1 |
| Material Symbols Rounded icons (google/material-design-icons, converted to vector drawables `ms_*` by `tools/icons/fetch-symbols.sh`) | Apache-2.0 |

The data licences apply whatever licence the code is under. The ODbL's
share-alike covers derived databases: anyone who receives a `.motomap` may
share it under the ODbL, and the ODbL forbids technical measures that restrict
derived databases unless an unrestricted copy is also offered. `.motomap`
packages are therefore never DRM-locked; the hosted service charges, if at
all, for building and delivering them.
