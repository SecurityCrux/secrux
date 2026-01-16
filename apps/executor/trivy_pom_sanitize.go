package main

import (
	"bytes"
	"context"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"net/url"
	"os"
	"path/filepath"
	"strings"
)

type pomSanitizeResult struct {
	PomsScanned          int
	PomsModified         int
	RepositoriesRemoved  int
	RepositorySanitizers int
	Errors               int
}

func prepareTrivyFilesystemScanDir(
	ctx context.Context,
	payload AssignPayload,
	sourceDir string,
) (string, func(), []string, error) {
	_ = ctx
	if !trivyConfig.SanitizePomRepositories {
		return sourceDir, func() {}, nil, nil
	}

	bannedHosts := normalizeHostList(trivyConfig.BannedMavenRepoHosts)
	if len(bannedHosts) == 0 {
		bannedHosts = normalizeHostList(defaultTrivyConfig().BannedMavenRepoHosts)
	}

	copyMode := strings.ToLower(strings.TrimSpace(trivyConfig.FilesystemCopyMode))
	if copyMode == "" {
		copyMode = "auto"
	}

	notes := []string{
		fmt.Sprintf(
			"[secrux-executor] trivy pom sanitization enabled=true copyMode=%s bannedMavenRepoHosts=%s",
			copyMode,
			strings.Join(bannedHosts, ","),
		),
	}

	shouldCopy := false
	if payload.Source != nil && payload.Source.Filesystem != nil {
		// When scanning an executor-local filesystem path, avoid mutating the original directory.
		if strings.TrimSpace(payload.Source.Filesystem.Path) != "" && strings.TrimSpace(payload.Source.Filesystem.UploadID) == "" {
			shouldCopy = copyMode == "auto" || copyMode == "always"
		} else {
			shouldCopy = copyMode == "always"
		}
	} else {
		shouldCopy = copyMode == "always"
	}
	if copyMode == "never" {
		shouldCopy = false
	}

	scanDir := sourceDir
	cleanup := func() {}
	if shouldCopy {
		tmp, err := os.MkdirTemp("", fmt.Sprintf("secrux-trivy-src-%s-", sanitizeName(payload.TaskID)))
		if err != nil {
			return "", func() {}, nil, err
		}
		chmodBestEffort(tmp, 0755)
		if err := copyDir(sourceDir, tmp); err != nil {
			_ = os.RemoveAll(tmp)
			return "", func() {}, nil, fmt.Errorf("failed to copy trivy scan source for pom sanitization: %w", err)
		}
		scanDir = tmp
		cleanup = func() { _ = os.RemoveAll(tmp) }
		notes = append(notes, fmt.Sprintf("[secrux-executor] trivy source copied for sanitization (path=%s)", scanDir))
	}

	stats := sanitizeMavenPomRepositories(scanDir, bannedHosts)
	if stats.Errors > 0 {
		notes = append(notes, fmt.Sprintf("[secrux-executor] trivy pom sanitization warnings=%d (some pom.xml files could not be processed)", stats.Errors))
	}
	if stats.RepositoriesRemoved > 0 {
		notes = append(notes, fmt.Sprintf("[secrux-executor] trivy pom sanitization removed=%d (pomsModified=%d pomsScanned=%d)", stats.RepositoriesRemoved, stats.PomsModified, stats.PomsScanned))
	} else if stats.PomsScanned > 0 {
		notes = append(notes, fmt.Sprintf("[secrux-executor] trivy pom sanitization scanned poms=%d (no banned repo urls found)", stats.PomsScanned))
	}

	return scanDir, cleanup, notes, nil
}

func normalizeHostList(hosts []string) []string {
	seen := map[string]struct{}{}
	out := make([]string, 0, len(hosts))
	for _, raw := range hosts {
		h := strings.ToLower(strings.TrimSpace(raw))
		h = strings.TrimPrefix(h, "https://")
		h = strings.TrimPrefix(h, "http://")
		h = strings.TrimPrefix(h, "//")
		h = strings.TrimSuffix(h, "/")
		if h == "" {
			continue
		}
		if _, ok := seen[h]; ok {
			continue
		}
		seen[h] = struct{}{}
		out = append(out, h)
	}
	return out
}

func copyDir(src string, dst string) error {
	src = filepath.Clean(src)
	dst = filepath.Clean(dst)
	return filepath.WalkDir(src, func(path string, entry fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		rel, err := filepath.Rel(src, path)
		if err != nil {
			return err
		}
		if rel == "." {
			return nil
		}
		target := filepath.Join(dst, rel)
		info, err := entry.Info()
		if err != nil {
			return err
		}
		mode := info.Mode()
		switch {
		case mode.IsDir():
			return os.MkdirAll(target, mode.Perm())
		case mode&os.ModeSymlink != 0:
			link, err := os.Readlink(path)
			if err != nil {
				return err
			}
			if err := os.MkdirAll(filepath.Dir(target), 0755); err != nil {
				return err
			}
			return os.Symlink(link, target)
		case mode.IsRegular():
			if err := copyFile(path, target); err != nil {
				return err
			}
			return os.Chmod(target, mode.Perm())
		default:
			return nil
		}
	})
}

func sanitizeMavenPomRepositories(rootDir string, bannedHosts []string) pomSanitizeResult {
	result := pomSanitizeResult{}
	_ = filepath.WalkDir(rootDir, func(path string, entry fs.DirEntry, err error) error {
		if err != nil {
			result.Errors++
			return nil
		}
		if entry.IsDir() {
			return nil
		}
		if !strings.EqualFold(entry.Name(), "pom.xml") {
			return nil
		}
		result.PomsScanned++
		raw, err := os.ReadFile(path)
		if err != nil {
			result.Errors++
			return nil
		}
		updated, removed, changed, err := sanitizePomXML(raw, bannedHosts)
		if err != nil {
			result.Errors++
			return nil
		}
		if !changed {
			return nil
		}
		if err := os.WriteFile(path, updated, 0644); err != nil {
			result.Errors++
			return nil
		}
		result.PomsModified++
		result.RepositoriesRemoved += removed
		return nil
	})
	return result
}

func sanitizePomXML(raw []byte, bannedHosts []string) ([]byte, int, bool, error) {
	decoder := xml.NewDecoder(bytes.NewReader(raw))
	decoder.Strict = false

	var buf bytes.Buffer
	encoder := xml.NewEncoder(&buf)

	removed := 0
	changed := false

	for {
		tok, err := decoder.Token()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return nil, 0, false, err
		}

		switch t := tok.(type) {
		case xml.StartElement:
			if t.Name.Local == "repositories" || t.Name.Local == "pluginRepositories" {
				if err := encoder.EncodeToken(t); err != nil {
					return nil, 0, false, err
				}
				dropped, err := copyRepositorySection(decoder, encoder, t.Name, bannedHosts)
				if err != nil {
					return nil, 0, false, err
				}
				if dropped > 0 {
					removed += dropped
					changed = true
				}
				continue
			}
		}
		if err := encoder.EncodeToken(tok); err != nil {
			return nil, 0, false, err
		}
	}
	if err := encoder.Flush(); err != nil {
		return nil, 0, false, err
	}
	if !changed {
		return raw, 0, false, nil
	}
	return buf.Bytes(), removed, true, nil
}

func copyRepositorySection(decoder *xml.Decoder, encoder *xml.Encoder, sectionName xml.Name, bannedHosts []string) (int, error) {
	dropped := 0
	depth := 1
	for {
		tok, err := decoder.Token()
		if err != nil {
			return dropped, err
		}
		switch t := tok.(type) {
		case xml.StartElement:
			depth++
			if t.Name.Local == "repository" {
				tokens, urlValue, err := readElementTokens(decoder, t)
				if err != nil {
					return dropped, err
				}
				depth-- // readElementTokens already consumed the end element for repository
				if isBannedRepoURL(urlValue, bannedHosts) {
					dropped++
					continue
				}
				for _, token := range tokens {
					if err := encoder.EncodeToken(token); err != nil {
						return dropped, err
					}
				}
				continue
			}
		case xml.EndElement:
			depth--
			if err := encoder.EncodeToken(t); err != nil {
				return dropped, err
			}
			if depth == 0 && t.Name.Local == sectionName.Local {
				return dropped, encoder.Flush()
			}
			continue
		}
		if err := encoder.EncodeToken(tok); err != nil {
			return dropped, err
		}
	}
}

func readElementTokens(decoder *xml.Decoder, start xml.StartElement) ([]xml.Token, string, error) {
	tokens := []xml.Token{start}
	depth := 1
	var urlValue string
	needURL := false
	for {
		tok, err := decoder.Token()
		if err != nil {
			return nil, "", err
		}
		tokens = append(tokens, tok)
		switch t := tok.(type) {
		case xml.StartElement:
			depth++
			if t.Name.Local == "url" {
				needURL = true
			}
		case xml.CharData:
			if needURL && urlValue == "" {
				urlValue = strings.TrimSpace(string(t))
			}
		case xml.EndElement:
			if t.Name.Local == "url" {
				needURL = false
			}
			depth--
			if depth == 0 && t.Name.Local == start.Name.Local {
				return tokens, urlValue, nil
			}
		}
	}
}

func isBannedRepoURL(value string, bannedHosts []string) bool {
	v := strings.ToLower(strings.TrimSpace(value))
	if v == "" {
		return false
	}
	parsed, err := url.Parse(v)
	host := v
	if err == nil && parsed.Host != "" {
		host = parsed.Host
	}
	for _, banned := range bannedHosts {
		b := strings.ToLower(strings.TrimSpace(banned))
		if b == "" {
			continue
		}
		if strings.Contains(host, b) || strings.Contains(v, b) {
			return true
		}
	}
	return false
}
