package main

import "bytes"

type javaTokenKind int

const (
	javaTokenIdent javaTokenKind = iota
	javaTokenSymbol
)

type javaToken struct {
	kind javaTokenKind
	text string
	line int
	col  int
}

func scanJavaTokens(src []byte) []javaToken {
	tokens := make([]javaToken, 0, len(src)/16)
	i := 0
	line := 1
	col := 1

	for i < len(src) {
		b := src[i]
		switch b {
		case '\r':
			i++
			continue
		case '\n':
			i++
			line++
			col = 1
			continue
		case ' ', '\t', '\f':
			i++
			col++
			continue
		}

		// Line comment
		if b == '/' && i+1 < len(src) && src[i+1] == '/' {
			i += 2
			col += 2
			for i < len(src) && src[i] != '\n' {
				i++
				col++
			}
			continue
		}

		// Block comment
		if b == '/' && i+1 < len(src) && src[i+1] == '*' {
			i += 2
			col += 2
			for i < len(src) {
				if src[i] == '\n' {
					line++
					col = 1
					i++
					continue
				}
				if src[i] == '*' && i+1 < len(src) && src[i+1] == '/' {
					i += 2
					col += 2
					break
				}
				i++
				col++
			}
			continue
		}

		// String literal
		if b == '"' {
			i++
			col++
			for i < len(src) {
				if src[i] == '\n' {
					line++
					col = 1
					i++
					continue
				}
				if src[i] == '\\' && i+1 < len(src) {
					i += 2
					col += 2
					continue
				}
				if src[i] == '"' {
					i++
					col++
					break
				}
				i++
				col++
			}
			continue
		}

		// Char literal
		if b == '\'' {
			i++
			col++
			for i < len(src) {
				if src[i] == '\n' {
					line++
					col = 1
					i++
					continue
				}
				if src[i] == '\\' && i+1 < len(src) {
					i += 2
					col += 2
					continue
				}
				if src[i] == '\'' {
					i++
					col++
					break
				}
				i++
				col++
			}
			continue
		}

		if isJavaIdentStart(b) {
			start := i
			startCol := col
			i++
			col++
			for i < len(src) && isJavaIdentPart(src[i]) {
				i++
				col++
			}
			tokens = append(tokens, javaToken{
				kind: javaTokenIdent,
				text: string(src[start:i]),
				line: line,
				col:  startCol,
			})
			continue
		}

		tokens = append(tokens, javaToken{
			kind: javaTokenSymbol,
			text: string([]byte{b}),
			line: line,
			col:  col,
		})
		i++
		col++
	}

	return tokens
}

func isJavaIdentStart(b byte) bool {
	return (b >= 'a' && b <= 'z') ||
		(b >= 'A' && b <= 'Z') ||
		b == '_' ||
		b == '$'
}

func isJavaIdentPart(b byte) bool {
	if isJavaIdentStart(b) {
		return true
	}
	return b >= '0' && b <= '9'
}

func joinWithDot(parts []string) string {
	if len(parts) == 0 {
		return ""
	}
	if len(parts) == 1 {
		return parts[0]
	}
	var buf bytes.Buffer
	for i, part := range parts {
		if i > 0 {
			buf.WriteByte('.')
		}
		buf.WriteString(part)
	}
	return buf.String()
}
