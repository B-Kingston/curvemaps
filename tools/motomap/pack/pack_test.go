package pack

import (
	"bytes"
	"encoding/binary"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/B-Kingston/curvemaps/tools/motomap/manifest"
	"github.com/B-Kingston/curvemaps/tools/motomap/motomap"
)

type fixture struct {
	repo, source, tiles, graph, geocoder string
}

// writeFixture lays out a minimal repository (the hashed pipeline inputs) and
// the three generated components the way the real pipeline leaves them.
func writeFixture(t *testing.T, profile string) fixture {
	t.Helper()
	dir := t.TempDir()
	repo := filepath.Join(dir, "repo")
	for rel, content := range map[string]string{
		"tools/gh/config.yml":                        "profiles: []\n",
		"tools/gh/motorcycle.json":                   "{}\n",
		"tools/gh/MotoGraphImport.java":              "class MotoGraphImport {}\n",
		"tools/tiles/build-tiles.sh":                 "#!/bin/sh\n",
		"geocoder-tool/build.gradle.kts":             "plugins {}\n",
		"geocoder-tool/src/main/kotlin/Main.kt":      "fun main() {}\n",
		"geocoder-tool/src/main/resources/words.tsv": "cafe\tfood\n",
	} {
		writeFile(t, filepath.Join(repo, rel), []byte(content))
	}
	var pbf bytes.Buffer
	header := []byte("OSMHeader-blob")
	_ = binary.Write(&pbf, binary.BigEndian, uint32(len(header)))
	pbf.Write(header)
	pbf.WriteString("rest-of-extract")
	source := filepath.Join(dir, "region.osm.pbf")
	writeFile(t, source, pbf.Bytes())

	tiles := filepath.Join(dir, "basemap.pmtiles")
	tileBytes := append([]byte("PMTiles"), 3)
	writeFile(t, tiles, append(tileBytes, make([]byte, 120)...))
	graph := filepath.Join(dir, "graph-cache")
	writeFile(t, filepath.Join(graph, "properties"), []byte("graph.profiles="+profile+"\n"))
	writeFile(t, filepath.Join(graph, "nodes"), []byte("nodes-content"))
	writeFile(t, filepath.Join(graph, "edges"), []byte("edges-content"))
	geocoder := filepath.Join(dir, "geocoder.dat")
	writeFile(t, geocoder, append([]byte(manifest.GeocoderMagic), make([]byte, 64)...))
	return fixture{repo: repo, source: source, tiles: tiles, graph: graph, geocoder: geocoder}
}

func writeFile(t *testing.T, path string, data []byte) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, data, 0o644); err != nil {
		t.Fatal(err)
	}
}

func input(f fixture, out string) Input {
	return Input{
		RegionID:     "queensland",
		RegionName:   "Queensland",
		Coverage:     "Queensland, Australia",
		SourceDate:   "260928",
		SourceURL:    "https://download.geofabrik.de/australia-oceania/australia/queensland-260928.osm.pbf",
		SourcePath:   f.source,
		TilesFile:    f.tiles,
		GraphDir:     f.graph,
		GeocoderFile: f.geocoder,
		OutDir:       out,
		RepoRoot:     f.repo,
	}
}

// A packed package must verify, carry each component byte-for-byte, and
// leave the sidecar and manifest the Android importer and release expect.
func TestPackAssemblesExistingComponents(t *testing.T) {
	f := writeFixture(t, DefaultGraphProfile)
	out := t.TempDir()
	res, err := Pack(input(f, out), nil)
	if err != nil {
		t.Fatalf("pack: %v", err)
	}
	if filepath.Base(res.ArtifactPath) != "queensland.motomap" {
		t.Fatalf("unexpected package name %s", res.ArtifactPath)
	}
	mf, err := motomap.Verify(res.ArtifactPath)
	if err != nil {
		t.Fatalf("packed package does not verify: %v", err)
	}
	for name, src := range map[string]string{manifest.ComponentTiles: f.tiles, manifest.ComponentGeocoder: f.geocoder} {
		want, _ := motomap.DigestFile(src)
		got, ok := mf.Component(name)
		if !ok || got.SHA256 != want.SHA256 || got.Bytes != want.Bytes {
			t.Fatalf("component %s differs from its source: %+v vs %+v", name, got, want)
		}
	}
	graph, _ := motomap.DigestDir(f.graph)
	if got, _ := mf.Component(manifest.ComponentGraph); got.SHA256 != graph.SHA256 {
		t.Fatalf("graph component digest %s, want %s", got.SHA256, graph.SHA256)
	}
	if mf.Compatibility.GraphProfile != DefaultGraphProfile || mf.Generator.Name != GeneratorName {
		t.Fatalf("unexpected compatibility/generator: %+v %+v", mf.Compatibility, mf.Generator)
	}
	if !strings.HasPrefix(mf.PackageID, "queensland-260928-") || mf.Source.SHA256 != res.Source.SHA256 {
		t.Fatalf("unexpected package id or source: %s %+v", mf.PackageID, mf.Source)
	}
	for _, name := range []string{"queensland.motomap.sha256", "manifest.json"} {
		if _, err := os.Stat(filepath.Join(out, name)); err != nil {
			t.Fatalf("%s missing: %v", name, err)
		}
	}
}

// Identical inputs give the same fingerprint; any changed pipeline input
// changes it.
func TestPackFingerprintFollowsContentAndPipelineInputs(t *testing.T) {
	f := writeFixture(t, DefaultGraphProfile)
	a, err := Pack(input(f, t.TempDir()), nil)
	if err != nil {
		t.Fatal(err)
	}
	b, err := Pack(input(f, t.TempDir()), nil)
	if err != nil {
		t.Fatal(err)
	}
	if a.Fingerprint != b.Fingerprint {
		t.Fatalf("same inputs, different fingerprints: %s vs %s", a.Fingerprint, b.Fingerprint)
	}
	writeFile(t, filepath.Join(f.repo, "tools/gh/config.yml"), []byte("profiles: [changed]\n"))
	c, err := Pack(input(f, t.TempDir()), nil)
	if err != nil {
		t.Fatal(err)
	}
	if c.Fingerprint == a.Fingerprint {
		t.Fatal("a changed pipeline input kept the fingerprint")
	}
}

func TestPackRejectsASourceThatIsNotThePinnedExtract(t *testing.T) {
	f := writeFixture(t, DefaultGraphProfile)
	in := input(f, t.TempDir())
	in.SourceSHA256 = strings.Repeat("0", 64)
	if _, err := Pack(in, nil); err == nil || !strings.Contains(err.Error(), "pinned SHA-256") {
		t.Fatalf("expected a pinned-hash rejection, got %v", err)
	}
}

func TestPackRejectsAGraphWithTheWrongProfile(t *testing.T) {
	f := writeFixture(t, "motorcycle|1")
	if _, err := Pack(input(f, t.TempDir()), nil); err == nil || !strings.Contains(err.Error(), "profile marker") {
		t.Fatalf("a graph with a different stored profile was packed: %v", err)
	}
}

func TestCheckGraphProfileMatchesTheWholeValue(t *testing.T) {
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, "properties"), []byte("graph.profiles="+DefaultGraphProfile+"9\n"))
	if err := CheckGraphProfile(dir, DefaultGraphProfile); err == nil {
		t.Fatal("a marker that only begins with the expected profile passed")
	}
}

func TestPackRejectsANonPBFSource(t *testing.T) {
	f := writeFixture(t, DefaultGraphProfile)
	writeFile(t, f.source, []byte("<html>index</html>"))
	if _, err := Pack(input(f, t.TempDir()), nil); err == nil {
		t.Fatal("an HTML page was accepted as the source extract")
	}
}

func TestToolHashesCoverEveryPipelineInput(t *testing.T) {
	f := writeFixture(t, DefaultGraphProfile)
	hashes, err := ToolHashes(f.repo)
	if err != nil {
		t.Fatal(err)
	}
	for _, rel := range []string{
		"tools/gh/config.yml", "tools/gh/motorcycle.json", "tools/gh/MotoGraphImport.java",
		"tools/tiles/build-tiles.sh", "geocoder-tool/build.gradle.kts",
		"geocoder-tool/src/main/kotlin/Main.kt", "geocoder-tool/src/main/resources/words.tsv",
	} {
		if len(hashes[rel]) != 64 {
			t.Fatalf("no hash for %s: %v", rel, hashes)
		}
	}
	if err := os.Remove(filepath.Join(f.repo, "tools/gh/MotoGraphImport.java")); err != nil {
		t.Fatal(err)
	}
	if _, err := ToolHashes(f.repo); err == nil {
		t.Fatal("a missing pipeline input was silently skipped")
	}
}
