package main

import (
	"bufio"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

type javaImportDecl struct {
	target     string
	isStatic   bool
	isWildcard bool
	line       int
}

type javaUsageOccurrence struct {
	groupID    string
	line       int
	col        int
	endLine    int
	endCol     int
	kind       string
	snippet    string
	symbol     string
	receiver   string
	callee     string
	confidence float64
}

func buildMavenJavaUsageEntries(scanRoot string, mavenPkgs []mavenUsagePackage) ([]scaUsageIndexEntry, int, error) {
	groupToKeys := make(map[string][]string)
	var groupIDs []string
	for _, pkg := range mavenPkgs {
		group := strings.ToLower(strings.TrimSpace(pkg.groupID))
		if group == "" {
			continue
		}
		if _, ok := groupToKeys[group]; !ok {
			groupIDs = append(groupIDs, group)
		}
		groupToKeys[group] = append(groupToKeys[group], pkg.key)
	}
	if len(groupIDs) == 0 {
		return nil, 0, nil
	}
	for group, keys := range groupToKeys {
		groupToKeys[group] = uniqueTokens(keys)
	}
	sort.Slice(groupIDs, func(i, j int) bool { return len(groupIDs[i]) > len(groupIDs[j]) })

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
	entries := make([]scaUsageIndexEntry, 0, 256)

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
		if strings.ToLower(filepath.Ext(d.Name())) != ".java" {
			return nil
		}
		info, statErr := d.Info()
		if statErr == nil && info.Size() > 2*1024*1024 {
			return nil
		}

		f, openErr := os.Open(path)
		if openErr != nil {
			return nil
		}

		scannedFiles++
		rel, _ := filepath.Rel(scanRoot, path)
		rel = filepath.ToSlash(rel)

		lines := readTextLines(f, 4000)
		_ = f.Close()
		if len(lines) == 0 {
			return nil
		}

		imports, matchedGroups := scanJavaImports(lines, groupIDs)
		content := strings.Join(lines, "\n")
		if len(matchedGroups) == 0 {
			lowerContent := strings.ToLower(content)
			for _, group := range groupIDs {
				if strings.Contains(lowerContent, group+".") {
					matchedGroups[group] = struct{}{}
				}
			}
			if len(matchedGroups) == 0 {
				return nil
			}
		}

		occurrences := extractJavaOccurrencesAst([]byte(content), lines, imports, groupIDs)
		if len(occurrences) == 0 && len(imports) == 0 {
			return nil
		}

		for _, imp := range imports {
			group := matchGroupID(groupIDs, imp.target)
			if group == "" {
				continue
			}
			keys := groupToKeys[group]
			for _, key := range keys {
				entries = append(entries, scaUsageIndexEntry{
					Ecosystem:  "maven",
					Key:        key,
					File:       rel,
					Line:       imp.line,
					Kind:       "import",
					Snippet:    lineSnippet(lines, imp.line, 400),
					Language:   "java",
					Symbol:     imp.target,
					StartLine:  imp.line,
					EndLine:    imp.line,
					StartCol:   1,
					EndCol:     1,
					Confidence: 0.9,
				})
			}
		}

		for _, occ := range occurrences {
			keys := groupToKeys[occ.groupID]
			if len(keys) == 0 {
				continue
			}
			for _, key := range keys {
				entries = append(entries, scaUsageIndexEntry{
					Ecosystem:  "maven",
					Key:        key,
					File:       rel,
					Line:       occ.line,
					Kind:       occ.kind,
					Snippet:    occ.snippet,
					Language:   "java",
					Symbol:     occ.symbol,
					Receiver:   occ.receiver,
					Callee:     occ.callee,
					StartLine:  occ.line,
					EndLine:    orInt(occ.endLine, occ.line),
					StartCol:   occ.col,
					EndCol:     orInt(occ.endCol, occ.col),
					Confidence: occ.confidence,
				})
			}
		}

		return nil
	})
	if walkErr != nil {
		return nil, scannedFiles, walkErr
	}
	return entries, scannedFiles, nil
}

func scanJavaImports(lines []string, groupIDs []string) ([]javaImportDecl, map[string]struct{}) {
	imports := make([]javaImportDecl, 0, 16)
	matchedGroups := make(map[string]struct{})
	for idx, line := range lines {
		raw := strings.TrimSpace(line)
		if !strings.HasPrefix(raw, "import ") {
			continue
		}
		rest := strings.TrimSpace(strings.TrimPrefix(raw, "import "))
		isStatic := false
		if strings.HasPrefix(rest, "static ") {
			isStatic = true
			rest = strings.TrimSpace(strings.TrimPrefix(rest, "static "))
		}
		if !strings.HasSuffix(rest, ";") {
			continue
		}
		target := strings.TrimSpace(strings.TrimSuffix(rest, ";"))
		if target == "" {
			continue
		}
		isWildcard := strings.HasSuffix(target, ".*")
		imports = append(imports, javaImportDecl{
			target:     target,
			isStatic:   isStatic,
			isWildcard: isWildcard,
			line:       idx + 1,
		})
		group := matchGroupID(groupIDs, target)
		if group != "" {
			matchedGroups[group] = struct{}{}
		}
	}
	return imports, matchedGroups
}

func extractJavaOccurrences(
	src []byte,
	lines []string,
	imports []javaImportDecl,
	groupIDs []string,
) []javaUsageOccurrence {
	return extractJavaOccurrencesHeuristic(src, lines, imports, groupIDs)
}

func extractJavaOccurrencesHeuristic(
	src []byte,
	lines []string,
	imports []javaImportDecl,
	groupIDs []string,
) []javaUsageOccurrence {
	typeToGroup := make(map[string]string)
	staticMemberToGroup := make(map[string]string)
	for _, imp := range imports {
		group := matchGroupID(groupIDs, imp.target)
		if group == "" {
			continue
		}
		if imp.isStatic {
			if imp.isWildcard {
				continue
			}
			owner, member := splitOwnerAndMember(imp.target)
			if member != "" {
				staticMemberToGroup[member] = group
			}
			_ = owner
			continue
		}
		if imp.isWildcard {
			continue
		}
		simple := lastSegment(imp.target)
		if simple != "" {
			typeToGroup[simple] = group
		}
	}

	tokens := scanJavaTokens(src)
	if len(tokens) == 0 {
		return nil
	}

	varToGroup := make(map[string]string)
	occurrences := make([]javaUsageOccurrence, 0, 64)

	for i := 0; i < len(tokens); i++ {
		tok := tokens[i]
		if tok.kind == javaTokenSymbol && tok.text == "@" && i+1 < len(tokens) && tokens[i+1].kind == javaTokenIdent {
			name := tokens[i+1].text
			group := typeToGroup[name]
			if group != "" {
				occurrences = append(occurrences, javaUsageOccurrence{
					groupID:    group,
					line:       tok.line,
					col:        tok.col,
					endLine:    tok.line,
					endCol:     tok.col,
					kind:       "annotation",
					snippet:    lineSnippet(lines, tok.line, 400),
					symbol:     name,
					confidence: 0.8,
				})
			}
			continue
		}

		if tok.kind != javaTokenIdent {
			continue
		}

		// Track variable declarations for imported types: Type varName [=|;|,|)|[]
		if group := typeToGroup[tok.text]; group != "" && i+2 < len(tokens) && tokens[i+1].kind == javaTokenIdent {
			next := tokens[i+2].text
			if next == "=" || next == ";" || next == "," || next == ")" || next == "[" {
				varToGroup[tokens[i+1].text] = group
				occurrences = append(occurrences, javaUsageOccurrence{
					groupID:    group,
					line:       tok.line,
					col:        tok.col,
					endLine:    tok.line,
					endCol:     tok.col,
					kind:       "type",
					snippet:    lineSnippet(lines, tok.line, 400),
					symbol:     tok.text,
					confidence: 0.7,
				})
			}
		}

		if tok.text == "new" && i+1 < len(tokens) {
			end, typeName := parseDottedIdentifiers(tokens, i+1)
			group := resolveJavaSymbolGroup(typeName, groupIDs, typeToGroup)
			if group != "" {
				occurrences = append(occurrences, javaUsageOccurrence{
					groupID:    group,
					line:       tok.line,
					col:        tok.col,
					endLine:    tok.line,
					endCol:     tok.col,
					kind:       "new",
					snippet:    lineSnippet(lines, tok.line, 400),
					symbol:     typeName,
					confidence: 0.8,
				})
			}
			i = end - 1
			continue
		}

		end, parts := parseDottedIdentifierParts(tokens, i)
		if len(parts) == 0 {
			continue
		}
		if end < len(tokens) && tokens[end].text == "(" {
			// method call: receiver.method(...)
			methodName := parts[len(parts)-1]
			receiverParts := parts[:len(parts)-1]
			if len(receiverParts) > 0 {
				receiver := joinWithDot(receiverParts)
				group, confidence := resolveJavaReceiverGroup(receiver, groupIDs, typeToGroup, varToGroup)
				if group != "" {
					occurrences = append(occurrences, javaUsageOccurrence{
						groupID:    group,
						line:       tok.line,
						col:        tok.col,
						endLine:    tok.line,
						endCol:     tok.col,
						kind:       "call",
						snippet:    lineSnippet(lines, tok.line, 400),
						receiver:   receiver,
						callee:     methodName,
						symbol:     methodName,
						confidence: confidence,
					})
				}
			} else if group := staticMemberToGroup[methodName]; group != "" {
				occurrences = append(occurrences, javaUsageOccurrence{
					groupID:    group,
					line:       tok.line,
					col:        tok.col,
					endLine:    tok.line,
					endCol:     tok.col,
					kind:       "call",
					snippet:    lineSnippet(lines, tok.line, 400),
					receiver:   "",
					callee:     methodName,
					symbol:     methodName,
					confidence: 0.6,
				})
			}
		}
		i = end - 1
	}

	return occurrences
}

func parseDottedIdentifiers(tokens []javaToken, start int) (int, string) {
	end, parts := parseDottedIdentifierParts(tokens, start)
	return end, joinWithDot(parts)
}

func parseDottedIdentifierParts(tokens []javaToken, start int) (int, []string) {
	if start >= len(tokens) || tokens[start].kind != javaTokenIdent {
		return start, nil
	}
	parts := []string{tokens[start].text}
	i := start + 1
	for i+1 < len(tokens) && tokens[i].text == "." && tokens[i+1].kind == javaTokenIdent {
		parts = append(parts, tokens[i+1].text)
		i += 2
	}
	return i, parts
}

func resolveJavaSymbolGroup(symbol string, groupIDs []string, typeToGroup map[string]string) string {
	symbol = strings.TrimSpace(symbol)
	if symbol == "" {
		return ""
	}
	if strings.Contains(symbol, ".") {
		return matchGroupID(groupIDs, symbol)
	}
	return typeToGroup[symbol]
}

func resolveJavaReceiverGroup(
	receiver string,
	groupIDs []string,
	typeToGroup map[string]string,
	varToGroup map[string]string,
) (string, float64) {
	receiver = strings.TrimSpace(receiver)
	if receiver == "" {
		return "", 0
	}
	if strings.Contains(receiver, ".") {
		if group := matchGroupID(groupIDs, receiver); group != "" {
			return group, 0.9
		}
		last := lastSegment(receiver)
		if group := varToGroup[last]; group != "" {
			return group, 0.65
		}
		if group := typeToGroup[last]; group != "" {
			return group, 0.75
		}
		return "", 0
	}
	if group := varToGroup[receiver]; group != "" {
		return group, 0.65
	}
	if group := typeToGroup[receiver]; group != "" {
		return group, 0.75
	}
	return "", 0
}

func matchGroupID(groupIDs []string, value string) string {
	value = strings.ToLower(strings.TrimSpace(value))
	if value == "" {
		return ""
	}
	for _, group := range groupIDs {
		if strings.HasPrefix(value, group+".") || value == group {
			return group
		}
	}
	return ""
}

func lastSegment(value string) string {
	value = strings.TrimSpace(value)
	if value == "" {
		return ""
	}
	idx := strings.LastIndex(value, ".")
	if idx == -1 || idx == len(value)-1 {
		return value
	}
	return value[idx+1:]
}

func splitOwnerAndMember(target string) (string, string) {
	target = strings.TrimSpace(target)
	if target == "" {
		return "", ""
	}
	idx := strings.LastIndex(target, ".")
	if idx == -1 || idx == len(target)-1 {
		return "", ""
	}
	return target[:idx], target[idx+1:]
}

func lineSnippet(lines []string, line int, maxLen int) string {
	if line <= 0 || line > len(lines) {
		return ""
	}
	snippet := strings.TrimSpace(lines[line-1])
	if maxLen > 0 && len(snippet) > maxLen {
		snippet = snippet[:maxLen]
	}
	return snippet
}

func readTextLines(f *os.File, maxLines int) []string {
	_, _ = f.Seek(0, 0)
	scanner := bufio.NewScanner(f)
	buf := make([]byte, 0, 64*1024)
	scanner.Buffer(buf, 256*1024)
	lines := make([]string, 0, 256)
	for scanner.Scan() {
		lines = append(lines, scanner.Text())
		if maxLines > 0 && len(lines) >= maxLines {
			break
		}
	}
	return lines
}

func orInt(value int, fallback int) int {
	if value != 0 {
		return value
	}
	return fallback
}
