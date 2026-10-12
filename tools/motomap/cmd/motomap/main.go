// Command motomap packs and verifies `.motomap` regional data packages.
//
//	motomap pack --region-id ID --name NAME --source PBF --tiles PMTILES \
//	    --graph DIR --geocoder FILE --out DIR [--coverage TEXT] \
//	    [--source-date YYMMDD] [--source-url URL] [--source-sha256 HEX] [--repo-root DIR]
//	motomap verify --package FILE [--json]
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"os"
	"path/filepath"

	"github.com/B-Kingston/curvemaps/tools/motomap/motomap"
	"github.com/B-Kingston/curvemaps/tools/motomap/pack"
)

const usage = `usage:
  motomap pack    assemble a verified .motomap from generated components
  motomap verify  re-read a .motomap and check every manifest claim`

func main() {
	os.Exit(run(os.Args[1:], os.Stdout, os.Stderr))
}

func run(args []string, stdout, stderr io.Writer) int {
	if len(args) == 0 {
		fmt.Fprintln(stderr, usage)
		return 2
	}
	switch args[0] {
	case "pack":
		return runPack(args[1:], stdout, stderr)
	case "verify":
		return runVerify(args[1:], stdout, stderr)
	case "-h", "--help", "help":
		fmt.Fprintln(stdout, usage)
		return 0
	default:
		fmt.Fprintf(stderr, "motomap: unknown command %q\n%s\n", args[0], usage)
		return 2
	}
}

func runPack(args []string, stdout, stderr io.Writer) int {
	fs := flag.NewFlagSet("pack", flag.ContinueOnError)
	fs.SetOutput(stderr)
	var in pack.Input
	fs.StringVar(&in.RegionID, "region-id", "", "region id, e.g. queensland")
	fs.StringVar(&in.RegionName, "name", "", "display name, e.g. Queensland")
	fs.StringVar(&in.Coverage, "coverage", "", "human coverage description")
	fs.StringVar(&in.SourceDate, "source-date", "", "extract date (YYMMDD)")
	fs.StringVar(&in.SourceURL, "source-url", "", "where the extract came from (recorded only)")
	fs.StringVar(&in.SourceSHA256, "source-sha256", "", "optional pinned SHA-256 of the extract")
	fs.StringVar(&in.SourcePath, "source", "", "OSM extract the components were generated from")
	fs.StringVar(&in.TilesFile, "tiles", "", "PMTiles v3 archive")
	fs.StringVar(&in.GraphDir, "graph", "", "GraphHopper graph directory")
	fs.StringVar(&in.GeocoderFile, "geocoder", "", "geocoder.dat")
	fs.StringVar(&in.OutDir, "out", "", "output directory")
	fs.StringVar(&in.RepoRoot, "repo-root", "", "repository root (default: detected from the working directory)")
	fs.StringVar(&in.GraphProfile, "graph-profile", pack.DefaultGraphProfile, "expected stored GraphHopper profile marker")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	if in.RegionID == "" || in.RegionName == "" || in.SourcePath == "" || in.TilesFile == "" ||
		in.GraphDir == "" || in.GeocoderFile == "" || in.OutDir == "" {
		fmt.Fprintln(stderr, "motomap pack: --region-id, --name, --source, --tiles, --graph, --geocoder and --out are required")
		return 2
	}
	if in.RepoRoot == "" {
		root, err := findRepoRoot()
		if err != nil {
			fmt.Fprintf(stderr, "motomap pack: %v (pass --repo-root)\n", err)
			return 2
		}
		in.RepoRoot = root
	}
	res, err := pack.Pack(in, func(format string, a ...any) { fmt.Fprintf(stderr, format+"\n", a...) })
	if err != nil {
		fmt.Fprintf(stderr, "motomap: pack failed: %v\n", err)
		return 1
	}
	size := "unknown size"
	if info, err := os.Stat(res.ArtifactPath); err == nil {
		size = humanBytes(info.Size())
	}
	fmt.Fprintf(stdout, "package: %s (%s)\n", res.ArtifactPath, size)
	fmt.Fprintf(stdout, "fingerprint: %s\n", res.Fingerprint)
	return 0
}

func runVerify(args []string, stdout, stderr io.Writer) int {
	fs := flag.NewFlagSet("verify", flag.ContinueOnError)
	fs.SetOutput(stderr)
	pkg := fs.String("package", "", "package to verify")
	asJSON := fs.Bool("json", false, "emit the verified manifest as JSON")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	if *pkg == "" {
		fmt.Fprintln(stderr, "motomap verify: --package is required")
		return 2
	}
	mf, err := motomap.Verify(*pkg)
	if err != nil {
		fmt.Fprintf(stderr, "motomap verify: %s: %v\n", *pkg, err)
		return 1
	}
	if *asJSON {
		enc := json.NewEncoder(stdout)
		enc.SetIndent("", "  ")
		if err := enc.Encode(mf); err != nil {
			fmt.Fprintf(stderr, "motomap verify: %v\n", err)
			return 1
		}
		return 0
	}
	fmt.Fprintf(stdout, "package OK: %s\n", *pkg)
	fmt.Fprintf(stdout, "  packageId:   %s\n", mf.PackageID)
	fmt.Fprintf(stdout, "  region:      %s (%s)\n", mf.Region.ID, mf.Region.Name)
	fmt.Fprintf(stdout, "  source:      date=%s bytes=%d sha256=%s\n", mf.Source.Date, mf.Source.Bytes, mf.Source.SHA256)
	fmt.Fprintf(stdout, "  generatedAt: %s\n", mf.GeneratedAt)
	fmt.Fprintf(stdout, "  generator:   %s %s fingerprint=%s\n",
		mf.Generator.Name, mf.Generator.Version, mf.Generator.PipelineFingerprint)
	fmt.Fprintf(stdout, "  compatibility: schema=%d graphProfile=%s geocoderMagic=%q tilesFormat=%s\n",
		mf.Compatibility.PackageSchema, mf.Compatibility.GraphProfile,
		mf.Compatibility.GeocoderMagic, mf.Compatibility.TilesFormat)
	for _, c := range mf.Components {
		if c.Kind == "directory" {
			fmt.Fprintf(stdout, "  component %-8s %s (%d files, %d bytes, sha256 %s)\n",
				c.Name, c.Format, len(c.Files), c.Bytes, c.SHA256)
			continue
		}
		fmt.Fprintf(stdout, "  component %-8s %s (%d bytes, sha256 %s)\n", c.Name, c.Format, c.Bytes, c.SHA256)
	}
	return 0
}

// findRepoRoot walks up from the working directory to the checkout that holds
// the pinned pipeline inputs.
func findRepoRoot() (string, error) {
	dir, err := os.Getwd()
	if err != nil {
		return "", err
	}
	for {
		if _, err := os.Stat(filepath.Join(dir, "tools", "gh", "config.yml")); err == nil {
			return dir, nil
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			return "", fmt.Errorf("no repository with tools/gh/config.yml above the working directory")
		}
		dir = parent
	}
}

func humanBytes(n int64) string {
	const unit = 1024
	if n < unit {
		return fmt.Sprintf("%d B", n)
	}
	div, exp := int64(unit), 0
	for m := n / unit; m >= unit; m /= unit {
		div *= unit
		exp++
	}
	return fmt.Sprintf("%.1f %ciB", float64(n)/float64(div), "KMGTPE"[exp])
}
