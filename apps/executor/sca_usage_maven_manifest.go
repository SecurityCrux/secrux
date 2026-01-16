package main

import (
	"bufio"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
)

func buildMavenManifestUsageEntries(scanRoot string, mavenPkgs []mavenUsagePackage) ([]scaUsageIndexEntry, int, error) {
	if len(mavenPkgs) == 0 {
		return nil, 0, nil
	}

	keyLowerToKey := make(map[string]string, len(mavenPkgs))
	for _, pkg := range mavenPkgs {
		key := strings.TrimSpace(pkg.key)
		if key == "" {
			continue
		}
		keyLowerToKey[strings.ToLower(key)] = key
	}
	if len(keyLowerToKey) == 0 {
		return nil, 0, nil
	}

	isManifest := func(path string) bool {
		base := strings.ToLower(filepath.Base(path))
		switch base {
		case "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts":
			return true
		default:
			return false
		}
	}

	skipDir := func(name string) bool {
		switch name {
		case ".git", ".svn", ".hg", ".idea", ".vscode", ".gradle":
			return true
		case "node_modules", "vendor", "target", "build", "dist", "out", ".next":
			return true
		default:
			return false
		}
	}

	scannedFiles := 0
	entries := make([]scaUsageIndexEntry, 0, 32)

	walkErr := filepath.WalkDir(scanRoot, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		if d.IsDir() {
			if skipDir(d.Name()) {
				return fs.SkipDir
			}
			return nil
		}
		if !isManifest(path) {
			return nil
		}

		f, openErr := os.Open(path)
		if openErr != nil {
			return nil
		}
		defer f.Close()
		scannedFiles++

		rel, _ := filepath.Rel(scanRoot, path)
		rel = filepath.ToSlash(rel)

		base := strings.ToLower(filepath.Base(path))
		if base == "pom.xml" {
			pomEntries := scanPomForMavenDeps(f, rel, keyLowerToKey)
			entries = append(entries, pomEntries...)
			return nil
		}

		gradleEntries := scanGradleForMavenDeps(f, rel, keyLowerToKey)
		entries = append(entries, gradleEntries...)
		return nil
	})
	if walkErr != nil {
		return nil, scannedFiles, walkErr
	}
	return entries, scannedFiles, nil
}

func scanPomForMavenDeps(f *os.File, rel string, keyLowerToKey map[string]string) []scaUsageIndexEntry {
	_, _ = f.Seek(0, 0)
	scanner := bufio.NewScanner(f)
	buf := make([]byte, 0, 64*1024)
	scanner.Buffer(buf, 256*1024)

	var lastGroup string
	var lastGroupLine int
	entries := make([]scaUsageIndexEntry, 0, 16)
	lineNo := 0
	for scanner.Scan() {
		lineNo++
		raw := scanner.Text()
		trimmed := strings.TrimSpace(raw)
		if trimmed == "" {
			continue
		}
		if g := extractXmlTagValue(trimmed, "groupId"); g != "" {
			lastGroup = g
			lastGroupLine = lineNo
			continue
		}
		if a := extractXmlTagValue(trimmed, "artifactId"); a != "" && lastGroup != "" {
			keyLower := strings.ToLower(strings.TrimSpace(lastGroup) + ":" + strings.TrimSpace(a))
			if key, ok := keyLowerToKey[keyLower]; ok {
				entries = append(entries, scaUsageIndexEntry{
					Ecosystem:  "maven",
					Key:        key,
					File:       rel,
					Line:       lineNo,
					Kind:       "manifest",
					Snippet:    strings.TrimSpace(raw),
					Language:   "xml",
					Symbol:     key,
					StartLine:  lastGroupLine,
					EndLine:    lineNo,
					StartCol:   1,
					EndCol:     1,
					Confidence: 0.7,
				})
			}
			continue
		}
	}
	return entries
}

func scanGradleForMavenDeps(f *os.File, rel string, keyLowerToKey map[string]string) []scaUsageIndexEntry {
	_, _ = f.Seek(0, 0)
	scanner := bufio.NewScanner(f)
	buf := make([]byte, 0, 64*1024)
	scanner.Buffer(buf, 256*1024)

	entries := make([]scaUsageIndexEntry, 0, 16)
	lineNo := 0
	for scanner.Scan() {
		lineNo++
		raw := scanner.Text()
		lower := strings.ToLower(raw)
		for keyLower, key := range keyLowerToKey {
			if strings.Contains(lower, keyLower) {
				entries = append(entries, scaUsageIndexEntry{
					Ecosystem:  "maven",
					Key:        key,
					File:       rel,
					Line:       lineNo,
					Kind:       "manifest",
					Snippet:    strings.TrimSpace(raw),
					Language:   "gradle",
					Symbol:     key,
					StartLine:  lineNo,
					EndLine:    lineNo,
					StartCol:   1,
					EndCol:     1,
					Confidence: 0.75,
				})
			}
		}
	}
	return entries
}

func extractXmlTagValue(line string, tag string) string {
	open := "<" + tag + ">"
	close := "</" + tag + ">"
	start := strings.Index(line, open)
	if start == -1 {
		return ""
	}
	start += len(open)
	end := strings.Index(line[start:], close)
	if end == -1 {
		return ""
	}
	value := strings.TrimSpace(line[start : start+end])
	return value
}
