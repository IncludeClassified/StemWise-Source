// sdkmodinfo retains the first native build's module receipt without checkout paths.
package main

import (
	"bytes"
	"debug/buildinfo"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

func main() {
	binary := flag.String("binary", "", "Native Go library")
	source := flag.String("source", "", "Bridge module directory")
	output := flag.String("output", "", "Canonical module receipt")
	verify := flag.Bool("verify", false, "Verify the final library preserves the receipt")
	flag.Parse()
	if err := run(*binary, *source, *output, *verify); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func run(binary, source, output string, verify bool) error {
	info, err := buildinfo.ReadFile(binary)
	if err != nil {
		return err
	}
	if info.Path != "gobind/gobind" {
		return fmt.Errorf("Unexpected SDK native main package: %s", info.Path)
	}
	owner := false
	for _, module := range info.Deps {
		owner = owner || module.Path == "phonepcbridge"
		if replacement := module.Replace; replacement != nil && replacement.Version == "(devel)" {
			if filepath.IsAbs(replacement.Path) {
				relative, err := filepath.Rel(source, replacement.Path)
				if err != nil || relative == ".." || strings.HasPrefix(relative, ".."+string(filepath.Separator)) {
					return fmt.Errorf("SDK local replacement leaves the source module")
				}
				replacement.Path = "./" + filepath.ToSlash(relative)
			} else if replacement.Path != "./." && !strings.HasPrefix(replacement.Path, "./") {
				return fmt.Errorf("Unexpected SDK local replacement")
			}
		}
	}
	if !owner {
		return fmt.Errorf("SDK native module receipt omits phonepcbridge")
	}
	info.GoVersion = "" // Stored separately in the native binary by the Go linker.
	// Go's readers discard 16-byte frames; ASCII keeps Windows linker args valid.
	const frame = "bridge-info-v1__"
	data := []byte(frame + info.String() + frame)
	if verify {
		expected, err := os.ReadFile(output)
		if err != nil {
			return err
		}
		if !bytes.Equal(data, expected) {
			return fmt.Errorf("Final SDK native module receipt changed")
		}
		return nil
	}
	return os.WriteFile(output, data, 0600)
}
