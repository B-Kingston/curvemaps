// Package pack assembles a verified `.motomap` from components the pinned data
// pipeline already generated (PMTiles basemap, GraphHopper graph, geocoder
// index). The app's CI uses it to ship one data file without rerunning the
// pipeline; the hosted map service reuses ToolHashes so both sides hash the
// same generation inputs.
package pack

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"github.com/B-Kingston/curvemaps/tools/motomap/manifest"
	"github.com/B-Kingston/curvemaps/tools/motomap/motomap"
)

// DefaultGraphProfile is the stored GraphHopper profile marker the app's
// pre-compiled weighting helper was generated for (see AGENTS.md).
const DefaultGraphProfile = "motorcycle|198752012"

// Version is recorded as the manifest generator version and keys the packed
// fingerprint. Bump it when the packing rules change.
const Version = "1"

// GeneratorName is the manifest generator name for packed packages.
const GeneratorName = "motomap"

// Input names already-generated components and the region they cover.
type Input struct {
	RegionID     string
	RegionName   string
	Coverage     string
	SourceDate   string // YYMMDD (or YYYY-MM-DD) of the OSM extract
	SourceURL    string // where the extract came from (recorded, never fetched)
	SourceSHA256 string // optional pin; the extract must match it when set
	SourcePath   string // the OSM extract the components were generated from
	TilesFile    string // PMTiles v3 archive
	GraphDir     string // GraphHopper graph directory
	GeocoderFile string // geocoder.dat
	OutDir       string
	RepoRoot     string // repository root, for ToolHashes
	GraphProfile string // defaults to DefaultGraphProfile
}

// Source describes the hashed OSM extract.
type Source struct {
	Path   string
	SHA256 string
	Bytes  int64
}

// Result locates the written package.
type Result struct {
	Fingerprint  string
	ArtifactPath string
	SidecarPath  string
	Manifest     manifest.Manifest
	Source       Source
}

// fingerprintInput keys a packed package by what it actually contains: the
// content digest of every component plus the pipeline inputs.
type fingerprintInput struct {
	PipelineVersion string            `json:"pipelineVersion"`
	PackageSchema   int               `json:"packageSchema"`
	RegionID        string            `json:"regionId"`
	SourceDate      string            `json:"sourceDate"`
	SourceSHA256    string            `json:"sourceSha256"`
	GraphProfile    string            `json:"graphProfile"`
	ToolHashes      map[string]string `json:"toolHashes"`
	Components      map[string]string `json:"components"`
}

// Pack assembles and verifies a package from existing components, writing it
// to OutDir as <region>.motomap with its sidecar and manifest.json.
func Pack(in Input, logf func(string, ...any)) (Result, error) {
	if in.RegionID == "" || in.RegionName == "" {
		return Result{}, fmt.Errorf("pack: region id and name are required")
	}
	if in.OutDir == "" {
		return Result{}, fmt.Errorf("pack: output directory is required")
	}
	profile := in.GraphProfile
	if profile == "" {
		profile = DefaultGraphProfile
	}
	source, err := StatSource(in.SourcePath)
	if err != nil {
		return Result{}, err
	}
	if in.SourceSHA256 != "" && in.SourceSHA256 != source.SHA256 {
		return Result{}, fmt.Errorf("pack: %s does not match the pinned SHA-256 for %s", in.SourcePath, in.RegionID)
	}
	if err := CheckGraphProfile(in.GraphDir, profile); err != nil {
		return Result{}, err
	}
	tiles, err := motomap.BuildComponent(manifest.ComponentTiles, in.TilesFile, manifest.FormatPMTilesV3, "")
	if err != nil {
		return Result{}, err
	}
	graph, err := motomap.BuildComponent(manifest.ComponentGraph, in.GraphDir, manifest.FormatGraphDir, profile)
	if err != nil {
		return Result{}, err
	}
	geocoder, err := motomap.BuildComponent(manifest.ComponentGeocoder, in.GeocoderFile, manifest.FormatGeocoder, "")
	if err != nil {
		return Result{}, err
	}
	toolHashes, err := ToolHashes(in.RepoRoot)
	if err != nil {
		return Result{}, err
	}
	doc, err := json.Marshal(fingerprintInput{
		PipelineVersion: Version,
		PackageSchema:   manifest.SchemaVersion,
		RegionID:        in.RegionID,
		SourceDate:      in.SourceDate,
		SourceSHA256:    source.SHA256,
		GraphProfile:    profile,
		ToolHashes:      toolHashes,
		Components: map[string]string{
			manifest.ComponentTiles:    tiles.SHA256,
			manifest.ComponentGraph:    graph.SHA256,
			manifest.ComponentGeocoder: geocoder.SHA256,
		},
	})
	if err != nil {
		return Result{}, err
	}
	fingerprint := motomap.DigestString(string(doc))

	mf := manifest.Manifest{
		SchemaVersion: manifest.SchemaVersion,
		PackageID:     fmt.Sprintf("%s-%s-%s", in.RegionID, SanitizeDate(in.SourceDate), fingerprint[:12]),
		Region:        manifest.Region{ID: in.RegionID, Name: in.RegionName, Coverage: in.Coverage},
		Source: manifest.Source{
			Date:   in.SourceDate,
			URL:    in.SourceURL,
			SHA256: source.SHA256,
			Bytes:  source.Bytes,
		},
		GeneratedAt: time.Now().UTC().Format(time.RFC3339),
		Generator: manifest.Generator{
			Name:                GeneratorName,
			Version:             Version,
			PipelineFingerprint: fingerprint,
		},
		Components: []manifest.Component{tiles, graph, geocoder},
		Compatibility: manifest.Compatibility{
			PackageSchema: manifest.SchemaVersion,
			GraphProfile:  profile,
			GeocoderMagic: manifest.GeocoderMagic,
			TilesFormat:   manifest.FormatPMTilesV3,
		},
		Attribution: manifest.Attribution,
	}
	if err := os.MkdirAll(in.OutDir, 0o755); err != nil {
		return Result{}, err
	}
	pkgPath := filepath.Join(in.OutDir, in.RegionID+manifest.PackageFileExtension)
	if err := motomap.Write(pkgPath, mf, map[string]string{
		manifest.ComponentTiles:    in.TilesFile,
		manifest.ComponentGraph:    in.GraphDir,
		manifest.ComponentGeocoder: in.GeocoderFile,
	}, logf); err != nil {
		return Result{}, err
	}
	if _, err := motomap.Verify(pkgPath); err != nil {
		_ = os.Remove(pkgPath)
		return Result{}, fmt.Errorf("package verification failed: %w", err)
	}
	if _, err := motomap.WriteSidecar(pkgPath); err != nil {
		return Result{}, err
	}
	manifestDoc, err := mf.MarshalCanonical()
	if err != nil {
		return Result{}, err
	}
	if err := os.WriteFile(filepath.Join(in.OutDir, "manifest.json"), manifestDoc, 0o644); err != nil {
		return Result{}, err
	}
	return Result{
		Fingerprint:  fingerprint,
		ArtifactPath: pkgPath,
		SidecarPath:  pkgPath + ".sha256",
		Manifest:     mf,
		Source:       source,
	}, nil
}

// ToolHashes hashes every generation input in the repository that changes
// package content. The hosted map service keys its artifact cache on the
// same map, so a pipeline change can never be served from a stale cache.
func ToolHashes(repoRoot string) (map[string]string, error) {
	paths := []string{
		"tools/gh/config.yml",
		"tools/gh/motorcycle.json",
		"tools/gh/MotoGraphImport.java",
		"tools/tiles/build-tiles.sh",
		"geocoder-tool/build.gradle.kts",
	}
	hashes := map[string]string{}
	for _, rel := range paths {
		d, err := motomap.DigestFile(filepath.Join(repoRoot, rel))
		if err != nil {
			return nil, fmt.Errorf("hash pipeline input %s: %w", rel, err)
		}
		hashes[rel] = d.SHA256
	}
	srcRoot := filepath.Join(repoRoot, "geocoder-tool/src")
	var files []string
	err := filepath.WalkDir(srcRoot, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if d.IsDir() {
			return nil
		}
		rel, err := filepath.Rel(repoRoot, p)
		if err != nil {
			return err
		}
		files = append(files, filepath.ToSlash(rel))
		return nil
	})
	if err != nil {
		return nil, fmt.Errorf("walk geocoder-tool sources: %w", err)
	}
	sort.Strings(files)
	for _, rel := range files {
		d, err := motomap.DigestFile(filepath.Join(repoRoot, rel))
		if err != nil {
			return nil, fmt.Errorf("hash pipeline input %s: %w", rel, err)
		}
		hashes[rel] = d.SHA256
	}
	return hashes, nil
}

// CheckGraphProfile requires the graph to carry the exact profile marker the
// app's pre-compiled weighting helper was generated for.
func CheckGraphProfile(graphDir, profile string) error {
	props, err := os.ReadFile(filepath.Join(graphDir, "properties"))
	if err != nil {
		return fmt.Errorf("graph has no properties file: %w", err)
	}
	for _, line := range strings.Split(string(props), "\n") {
		// GraphHopper writes `graph.profiles=<name>|<hash>`; match the whole
		// value so a marker that merely begins with ours cannot pass.
		line = strings.TrimSpace(line)
		if line == "profiles="+profile || strings.HasSuffix(line, ".profiles="+profile) {
			return nil
		}
	}
	return fmt.Errorf("graph profile marker does not match: want profiles=%s", profile)
}

// StatSource checks the PBF magic and hashes an OSM extract.
func StatSource(path string) (Source, error) {
	f, err := os.Open(path)
	if err != nil {
		return Source{}, fmt.Errorf("open source extract: %w", err)
	}
	defer f.Close()
	head := make([]byte, 64*1024)
	n, err := io.ReadFull(f, head)
	if err != nil && err != io.ErrUnexpectedEOF && err != io.EOF {
		return Source{}, err
	}
	if err := motomap.CheckPBFMagic(head[:n]); err != nil {
		return Source{}, fmt.Errorf("%s: %w", path, err)
	}
	if _, err := f.Seek(0, io.SeekStart); err != nil {
		return Source{}, err
	}
	h := sha256.New()
	size, err := io.Copy(h, f)
	if err != nil {
		return Source{}, err
	}
	return Source{Path: path, SHA256: hex.EncodeToString(h.Sum(nil)), Bytes: size}, nil
}

// SanitizeDate strips separators so a date fits in a package id.
func SanitizeDate(date string) string {
	return strings.ReplaceAll(date, "-", "")
}
