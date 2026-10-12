# motomap: the `.motomap` package format and pack tool

A `.motomap` file is a complete regional data package for the CurveMaps app:
the vector basemap, the GraphHopper routing graph, and the offline geocoder
index in one versioned, self-verifying archive. This module is the public
contract for that format. The Android importer
(`app/src/main/java/com/curvemaps/app/region/RegionPackage.kt` and
`RegionArchiveImporter.kt`) and this Go writer read the same manifest. Any
schema change must bump `manifest.SchemaVersion` and update both sides and this
document.

```sh
cd tools/motomap
go run ./cmd/motomap pack --region-id queensland --name Queensland \
    --source ../../data/queensland.osm.pbf \
    --tiles ../../data/tiles/queensland.pmtiles \
    --graph ../../data/graph-cache \
    --geocoder ../../app/src/main/assets/geocoder/geocoder.dat \
    --source-date 260928 --out ../../data/package
go run ./cmd/motomap verify --package ../../data/package/queensland.motomap
```

`tools/package/pack-queensland.sh` wraps `pack` for CI and release assets.

## Archive layout

A ZIP64 archive:

```
manifest.json                 schema, region, source, component digests, compatibility
tiles/basemap.pmtiles         PMTiles v3 vector basemap
graph/…                       GraphHopper graph cache (MMAP-ready)
geocoder/geocoder.dat         offline inverted place-search index
```

`manifest.json` (schema 1) records:
- region identity
- source date, URL, size and SHA-256
- the generator name, version and fingerprint
- per-file lengths and SHA-256 for every component file
- compatibility markers: the graph profile signature (`motorcycle|198752012`), the geocoder format magic (`OMGEO01\n`) and the tiles format (`pmtiles-v3`)
- the OSM and OpenMapTiles attribution

Both sides reject unknown fields, unsafe or duplicate paths, missing or extra
components, and wrong compatibility markers. `verify` re-reads every file and
checks every manifest claim.

## Packing rules

- `pack` refuses a source that isn't an OSM PBF, and refuses a source that doesn't match `--source-sha256` when one is pinned.
- It refuses a graph whose stored profile marker isn't exactly the expected one. The app's pre-compiled weighting helper only loads that profile.
- The package fingerprint covers the component digests, the source hash, and `ToolHashes`. `ToolHashes` hashes every pinned pipeline input: the GraphHopper config and model, the lane-aware importer, the tile script, and the geocoder sources.

## Licence

Part of CurveMaps (PolyForm Shield License 1.0.0; see `LICENSE` at the
repository root). The hosted map service uses this module to build packages.
