package main

import (
	"bytes"
	"strings"
	"testing"
)

func TestUsageAndUnknownCommands(t *testing.T) {
	var out, errb bytes.Buffer
	if code := run(nil, &out, &errb); code != 2 || !strings.Contains(errb.String(), "motomap pack") {
		t.Fatalf("no args: code %d, stderr %q", code, errb.String())
	}
	errb.Reset()
	if code := run([]string{"frobnicate"}, &out, &errb); code != 2 || !strings.Contains(errb.String(), "unknown command") {
		t.Fatalf("unknown command: code %d, stderr %q", code, errb.String())
	}
}

func TestPackRequiresEveryInput(t *testing.T) {
	var out, errb bytes.Buffer
	if code := run([]string{"pack", "--region-id", "queensland"}, &out, &errb); code != 2 ||
		!strings.Contains(errb.String(), "are required") {
		t.Fatalf("partial pack flags: code %d, stderr %q", code, errb.String())
	}
}

func TestVerifyReportsAMissingPackage(t *testing.T) {
	var out, errb bytes.Buffer
	if code := run([]string{"verify"}, &out, &errb); code != 2 {
		t.Fatalf("verify without --package: code %d", code)
	}
	errb.Reset()
	if code := run([]string{"verify", "--package", "/does/not/exist.motomap"}, &out, &errb); code != 1 {
		t.Fatalf("verify of a missing file: code %d, stderr %q", code, errb.String())
	}
}
