package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

func buildScaUsageIndex(scanRoot string, vulnPayload string) (string, int, error) {
	pkgs, err := extractUsagePackagesFromTrivy(vulnPayload, 200)
	if err != nil {
		return "", 0, err
	}
	if len(pkgs) == 0 {
		return "", 0, nil
	}

	mavenPkgs := extractMavenUsagePackages(pkgs)
	pkgsForTokens := make([]scaUsagePackage, 0, len(pkgs))
	for _, pkg := range pkgs {
		if strings.EqualFold(pkg.ecosystem, "maven") {
			continue
		}
		pkgsForTokens = append(pkgsForTokens, pkg)
	}

	tokenToPkgs := make(map[string][]int)
	var tokens []string
	for i, pkg := range pkgsForTokens {
		for _, token := range pkg.tokens {
			token = strings.TrimSpace(strings.ToLower(token))
			if token == "" {
				continue
			}
			if _, ok := tokenToPkgs[token]; !ok {
				tokens = append(tokens, token)
			}
			tokenToPkgs[token] = append(tokenToPkgs[token], i)
		}
	}

	perKeyCount := make(map[string]int)
	maxPerKey := 20
	maxTotal := 2000
	maxFiles := 5000
	scannedFiles := 0
	entries := make([]scaUsageIndexEntry, 0, 128)

	if len(tokens) > 0 {
		sort.Slice(tokens, func(i, j int) bool { return len(tokens[i]) > len(tokens[j]) })
		if len(tokens) > 400 {
			tokens = tokens[:400]
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

	isRelevantFile := func(path string) bool {
		base := strings.ToLower(filepath.Base(path))
		switch base {
		case "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "package.json", "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "go.mod", "go.sum", "requirements.txt", "poetry.lock", "cargo.toml", "cargo.lock", "gemfile":
			return true
		}
		ext := strings.ToLower(filepath.Ext(base))
		switch ext {
		case ".java", ".kt", ".kts", ".go", ".js", ".jsx", ".ts", ".tsx", ".py", ".rb", ".php", ".cs", ".rs", ".xml", ".yml", ".yaml":
			return true
		default:
			return false
		}
	}

	kindForLine := func(path string, line string) string {
		base := strings.ToLower(filepath.Base(path))
		if base == "pom.xml" || strings.HasSuffix(base, ".gradle") || strings.HasSuffix(base, ".gradle.kts") || base == "package.json" || strings.HasSuffix(base, ".lock") || base == "go.mod" || base == "requirements.txt" {
			return "manifest"
		}
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "import ") || strings.HasPrefix(trimmed, "import\t") {
			return "import"
		}
		if strings.Contains(trimmed, "require(") || strings.Contains(trimmed, "from \"") || strings.Contains(trimmed, "from '") {
			return "import"
		}
		return "code"
	}

	if len(tokens) > 0 {
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
			if len(entries) >= maxTotal || scannedFiles >= maxFiles {
				return nil
			}
			if !isRelevantFile(path) {
				return nil
			}
			info, err := d.Info()
			if err == nil {
				if info.Size() > 2*1024*1024 {
					return nil
				}
			}

			f, err := os.Open(path)
			if err != nil {
				return nil
			}
			defer f.Close()

			scannedFiles++
			rel, _ := filepath.Rel(scanRoot, path)
			rel = filepath.ToSlash(rel)

			scanner := bufio.NewScanner(f)
			buf := make([]byte, 0, 64*1024)
			scanner.Buffer(buf, 256*1024)
			lineNo := 0
			for scanner.Scan() {
				if len(entries) >= maxTotal {
					break
				}
				lineNo++
				raw := scanner.Text()
				if strings.TrimSpace(raw) == "" {
					continue
				}
				lower := strings.ToLower(raw)
				for _, token := range tokens {
					if token == "" {
						continue
					}
					if !strings.Contains(lower, token) {
						continue
					}
					for _, pkgIdx := range tokenToPkgs[token] {
						pkg := pkgsForTokens[pkgIdx]
						if perKeyCount[pkg.key] >= maxPerKey {
							continue
						}
						perKeyCount[pkg.key]++
						snippet := strings.TrimSpace(raw)
						if len(snippet) > 400 {
							snippet = snippet[:400]
						}
						entries = append(entries, scaUsageIndexEntry{
							Ecosystem: pkg.ecosystem,
							Key:       pkg.key,
							File:      rel,
							Line:      lineNo,
							Kind:      kindForLine(path, raw),
							Snippet:   snippet,
						})
						if len(entries) >= maxTotal {
							break
						}
					}
				}
			}
			return nil
		})
		if walkErr != nil {
			return "", 0, walkErr
		}
	}

	manifestEntries, manifestFiles, manifestErr := buildMavenManifestUsageEntries(scanRoot, mavenPkgs)
	scannedFiles += manifestFiles
	if manifestErr != nil {
		return "", 0, manifestErr
	}
	for _, entry := range manifestEntries {
		if len(entries) >= maxTotal {
			break
		}
		if perKeyCount[entry.Key] >= maxPerKey {
			continue
		}
		perKeyCount[entry.Key]++
		entries = append(entries, entry)
	}

	javaEntries, javaFiles, javaErr := buildMavenJavaUsageEntries(scanRoot, mavenPkgs)
	scannedFiles += javaFiles
	if javaErr != nil {
		return "", 0, javaErr
	}
	for _, entry := range javaEntries {
		if len(entries) >= maxTotal {
			break
		}
		if perKeyCount[entry.Key] >= maxPerKey {
			continue
		}
		perKeyCount[entry.Key]++
		entries = append(entries, entry)
	}

	if len(entries) == 0 {
		return "", 0, nil
	}

	index := scaUsageIndex{
		GeneratedAt:  time.Now().UTC().Format(time.RFC3339),
		ScannedFiles: scannedFiles,
		Entries:      entries,
	}
	out, err := json.Marshal(index)
	if err != nil {
		return "", 0, err
	}
	return string(out), len(entries), nil
}

func extractMavenUsagePackages(pkgs []scaUsagePackage) []mavenUsagePackage {
	seen := make(map[string]struct{})
	out := make([]mavenUsagePackage, 0, 32)
	for _, pkg := range pkgs {
		if !strings.EqualFold(pkg.ecosystem, "maven") {
			continue
		}
		key := strings.TrimSpace(pkg.key)
		if key == "" {
			continue
		}
		lower := strings.ToLower(key)
		if _, ok := seen[lower]; ok {
			continue
		}
		seen[lower] = struct{}{}

		group, artifact := parseMavenCoords(key)
		if group == "" || artifact == "" {
			continue
		}
		out = append(out, mavenUsagePackage{
			key:       key,
			groupID:   group,
			artifact:  artifact,
			ecosystem: pkg.ecosystem,
		})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].key < out[j].key })
	return out
}

func parseMavenCoords(key string) (string, string) {
	key = strings.TrimSpace(key)
	if key == "" {
		return "", ""
	}
	if strings.HasPrefix(strings.ToLower(key), "pkg:maven/") {
		coords := strings.TrimPrefix(strings.ToLower(key), "pkg:maven/")
		coords = strings.Split(coords, "@")[0]
		parts := strings.Split(coords, "/")
		if len(parts) >= 2 {
			return strings.TrimSpace(parts[0]), strings.TrimSpace(parts[1])
		}
		return "", ""
	}
	parts := strings.Split(key, ":")
	if len(parts) >= 2 {
		return strings.TrimSpace(parts[0]), strings.TrimSpace(parts[1])
	}
	return "", ""
}

func extractUsagePackagesFromTrivy(vulnPayload string, limit int) ([]scaUsagePackage, error) {
	var root map[string]any
	if err := json.Unmarshal([]byte(vulnPayload), &root); err != nil {
		return nil, err
	}
	results, ok := root["Results"].([]any)
	if !ok {
		return nil, nil
	}
	seen := make(map[string]*scaUsagePackage)
	for _, resultAny := range results {
		result, ok := resultAny.(map[string]any)
		if !ok {
			continue
		}
		vulns, ok := result["Vulnerabilities"].([]any)
		if !ok {
			continue
		}
		for _, vulnAny := range vulns {
			vuln, ok := vulnAny.(map[string]any)
			if !ok {
				continue
			}
			pkgName, _ := vuln["PkgName"].(string)
			pkgName = strings.TrimSpace(pkgName)
			purl := extractPurl(vuln)
			ecosystem := detectEcosystem(purl, result)
			key := pkgName
			if key == "" {
				key = strings.TrimSpace(purl)
			}
			if key == "" {
				continue
			}
			keyLower := strings.ToLower(key)
			if _, exists := seen[keyLower]; !exists && len(seen) >= limit {
				continue
			}
			tokens := deriveUsageTokens(ecosystem, pkgName, purl)
			if len(tokens) == 0 {
				continue
			}
			if existing, ok := seen[keyLower]; ok {
				existing.tokens = mergeTokens(existing.tokens, tokens)
				continue
			}
			seen[keyLower] = &scaUsagePackage{
				ecosystem: ecosystem,
				key:       key,
				tokens:    tokens,
			}
		}
	}
	pkgs := make([]scaUsagePackage, 0, len(seen))
	for _, pkg := range seen {
		pkgs = append(pkgs, *pkg)
	}
	sort.Slice(pkgs, func(i, j int) bool { return pkgs[i].key < pkgs[j].key })
	return pkgs, nil
}

func extractPurl(vuln map[string]any) string {
	raw, ok := vuln["PkgIdentifier"].(map[string]any)
	if !ok {
		return ""
	}
	for _, key := range []string{"PURL", "Purl", "purl"} {
		if value, ok := raw[key].(string); ok && strings.TrimSpace(value) != "" {
			return value
		}
	}
	return ""
}

func detectEcosystem(purl string, result map[string]any) string {
	lower := strings.ToLower(strings.TrimSpace(purl))
	switch {
	case strings.HasPrefix(lower, "pkg:maven/"):
		return "maven"
	case strings.HasPrefix(lower, "pkg:npm/"):
		return "npm"
	case strings.HasPrefix(lower, "pkg:golang/"):
		return "golang"
	case strings.HasPrefix(lower, "pkg:pypi/"):
		return "pypi"
	case strings.HasPrefix(lower, "pkg:gem/"):
		return "gem"
	default:
		if t, ok := result["Type"].(string); ok && strings.TrimSpace(t) != "" {
			return strings.TrimSpace(strings.ToLower(t))
		}
		return ""
	}
}

func deriveUsageTokens(ecosystem string, pkgName string, purl string) []string {
	ecosystem = strings.ToLower(strings.TrimSpace(ecosystem))
	pkgName = strings.TrimSpace(pkgName)
	purlLower := strings.ToLower(strings.TrimSpace(purl))

	var tokens []string
	add := func(value string) {
		value = strings.TrimSpace(value)
		if value == "" {
			return
		}
		if len(value) < 4 {
			return
		}
		tokens = append(tokens, value)
	}

	if ecosystem == "maven" {
		parts := strings.Split(pkgName, ":")
		if len(parts) >= 2 {
			group := strings.TrimSpace(parts[0])
			artifact := strings.TrimSpace(parts[1])
			if strings.Contains(group, ".") {
				add(group)
			}
			if len(artifact) >= 6 {
				add(artifact)
			}
		}
		if strings.HasPrefix(purlLower, "pkg:maven/") {
			coords := strings.TrimPrefix(purlLower, "pkg:maven/")
			coords = strings.Split(coords, "@")[0]
			coordParts := strings.Split(coords, "/")
			if len(coordParts) >= 2 {
				group := strings.TrimSpace(coordParts[0])
				artifact := strings.TrimSpace(coordParts[1])
				if strings.Contains(group, ".") {
					add(group)
				}
				if len(artifact) >= 6 {
					add(artifact)
				}
			}
		}
		return uniqueTokens(tokens)
	}

	if pkgName != "" {
		add(pkgName)
	}

	switch ecosystem {
	case "golang":
		if strings.HasPrefix(purlLower, "pkg:golang/") {
			module := strings.TrimPrefix(purlLower, "pkg:golang/")
			module = strings.Split(module, "@")[0]
			add(module)
		}
	case "npm":
		if strings.HasPrefix(purlLower, "pkg:npm/") {
			name := strings.TrimPrefix(purlLower, "pkg:npm/")
			name = strings.Split(name, "@")[0]
			add(name)
		}
	}
	return uniqueTokens(tokens)
}

func mergeTokens(a []string, b []string) []string {
	seen := make(map[string]struct{}, len(a)+len(b))
	var out []string
	for _, v := range append(a, b...) {
		key := strings.ToLower(strings.TrimSpace(v))
		if key == "" {
			continue
		}
		if _, ok := seen[key]; ok {
			continue
		}
		seen[key] = struct{}{}
		out = append(out, v)
	}
	return out
}

func uniqueTokens(tokens []string) []string {
	seen := make(map[string]struct{}, len(tokens))
	var out []string
	for _, token := range tokens {
		token = strings.TrimSpace(token)
		if token == "" {
			continue
		}
		key := strings.ToLower(token)
		if _, ok := seen[key]; ok {
			continue
		}
		seen[key] = struct{}{}
		out = append(out, token)
	}
	return out
}

func formatUsageIndexNote(err error, entryCount int) string {
	if err != nil {
		return fmt.Sprintf("[secrux-executor] usage index generation warning: %v\n", err)
	}
	if entryCount == 0 {
		return "[secrux-executor] usage index generation: no matches\n"
	}
	return fmt.Sprintf("[secrux-executor] usage index generated entries=%d\n", entryCount)
}
