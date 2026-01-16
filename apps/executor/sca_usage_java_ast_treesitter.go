//go:build cgo

package main

import (
	"strings"
	"sync"

	sitter "github.com/smacker/go-tree-sitter"
	tsjava "github.com/smacker/go-tree-sitter/java"
)

var javaParserPool = sync.Pool{
	New: func() any {
		parser := sitter.NewParser()
		parser.SetLanguage(tsjava.GetLanguage())
		return parser
	},
}

func extractJavaOccurrencesAst(
	src []byte,
	lines []string,
	imports []javaImportDecl,
	groupIDs []string,
) []javaUsageOccurrence {
	parser := javaParserPool.Get().(*sitter.Parser)
	defer javaParserPool.Put(parser)

	tree := parser.Parse(nil, src)
	if tree == nil {
		return extractJavaOccurrencesHeuristic(src, lines, imports, groupIDs)
	}
	defer tree.Close()

	typeToGroup, staticMemberToGroup := buildJavaImportMaps(imports, groupIDs)
	varToGroup := make(map[string]string)
	var occurrences []javaUsageOccurrence

	root := tree.RootNode()
	walkNamedNodes(root, func(node *sitter.Node) {
		switch node.Type() {
		case "local_variable_declaration", "field_declaration", "formal_parameter", "catch_formal_parameter", "resource":
			typeNode := javaFindTypeNode(node)
			if typeNode == nil {
				return
			}
			typeName := normalizeJavaTypeName(typeNode.Content(src))
			if typeName == "" {
				return
			}
			group := resolveJavaSymbolGroup(typeName, groupIDs, typeToGroup)
			if group == "" {
				return
			}

			startLine, startCol, endLine, endCol := javaNodeRange(typeNode)
			occurrences = append(occurrences, javaUsageOccurrence{
				groupID:    group,
				line:       startLine,
				col:        startCol,
				endLine:    endLine,
				endCol:     endCol,
				kind:       "type",
				snippet:    lineSnippet(lines, startLine, 400),
				symbol:     typeName,
				confidence: 0.75,
			})

			for _, name := range javaDeclaredVariableNames(node, src) {
				if _, ok := varToGroup[name]; !ok {
					varToGroup[name] = group
				}
			}
		}
	})

	walkNamedNodes(root, func(node *sitter.Node) {
		switch node.Type() {
		case "method_invocation":
			nameNode := node.ChildByFieldName("name")
			if nameNode == nil {
				nameNode = javaFindLastNamedChild(node, "identifier")
			}
			if nameNode == nil {
				return
			}
			methodName := strings.TrimSpace(nameNode.Content(src))
			if methodName == "" {
				return
			}

			objectNode := node.ChildByFieldName("object")
			if objectNode != nil {
				receiver := strings.TrimSpace(objectNode.Content(src))
				group, confidence := resolveJavaReceiverGroup(receiver, groupIDs, typeToGroup, varToGroup)
				if group == "" {
					return
				}
				startLine, startCol, endLine, endCol := javaNodeRange(node)
				occurrences = append(occurrences, javaUsageOccurrence{
					groupID:    group,
					line:       startLine,
					col:        startCol,
					endLine:    endLine,
					endCol:     endCol,
					kind:       "call",
					snippet:    lineSnippet(lines, startLine, 400),
					receiver:   receiver,
					callee:     methodName,
					symbol:     methodName,
					confidence: confidence,
				})
				return
			}

			if group := staticMemberToGroup[methodName]; group != "" {
				startLine, startCol, endLine, endCol := javaNodeRange(node)
				occurrences = append(occurrences, javaUsageOccurrence{
					groupID:    group,
					line:       startLine,
					col:        startCol,
					endLine:    endLine,
					endCol:     endCol,
					kind:       "call",
					snippet:    lineSnippet(lines, startLine, 400),
					callee:     methodName,
					symbol:     methodName,
					confidence: 0.6,
				})
			}
		case "object_creation_expression":
			typeNode := node.ChildByFieldName("type")
			if typeNode == nil {
				typeNode = javaFindTypeNode(node)
			}
			if typeNode == nil {
				return
			}
			typeName := normalizeJavaTypeName(typeNode.Content(src))
			if typeName == "" {
				return
			}
			group := resolveJavaSymbolGroup(typeName, groupIDs, typeToGroup)
			if group == "" {
				return
			}
			startLine, startCol, endLine, endCol := javaNodeRange(node)
			occurrences = append(occurrences, javaUsageOccurrence{
				groupID:    group,
				line:       startLine,
				col:        startCol,
				endLine:    endLine,
				endCol:     endCol,
				kind:       "new",
				snippet:    lineSnippet(lines, startLine, 400),
				symbol:     typeName,
				confidence: 0.85,
			})
		case "marker_annotation", "annotation", "single_element_annotation", "normal_annotation":
			nameNode := node.ChildByFieldName("name")
			if nameNode == nil {
				nameNode = javaFindFirstNamedChildOfTypes(node, []string{"scoped_identifier", "identifier", "type_identifier"})
			}
			if nameNode == nil {
				return
			}
			name := strings.TrimSpace(nameNode.Content(src))
			if name == "" {
				return
			}
			group := resolveJavaSymbolGroup(name, groupIDs, typeToGroup)
			if group == "" {
				return
			}
			startLine, startCol, endLine, endCol := javaNodeRange(node)
			occurrences = append(occurrences, javaUsageOccurrence{
				groupID:    group,
				line:       startLine,
				col:        startCol,
				endLine:    endLine,
				endCol:     endCol,
				kind:       "annotation",
				snippet:    lineSnippet(lines, startLine, 400),
				symbol:     name,
				confidence: 0.8,
			})
		}
	})

	if len(occurrences) == 0 {
		return extractJavaOccurrencesHeuristic(src, lines, imports, groupIDs)
	}
	return occurrences
}

func buildJavaImportMaps(imports []javaImportDecl, groupIDs []string) (map[string]string, map[string]string) {
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
			_, member := splitOwnerAndMember(imp.target)
			if member != "" {
				staticMemberToGroup[member] = group
			}
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

	return typeToGroup, staticMemberToGroup
}

func walkNamedNodes(root *sitter.Node, visit func(node *sitter.Node)) {
	if root == nil {
		return
	}
	stack := []*sitter.Node{root}
	for len(stack) > 0 {
		node := stack[len(stack)-1]
		stack = stack[:len(stack)-1]

		visit(node)

		for i := int(node.NamedChildCount()) - 1; i >= 0; i-- {
			child := node.NamedChild(i)
			if child != nil {
				stack = append(stack, child)
			}
		}
	}
}

func javaNodeRange(node *sitter.Node) (int, int, int, int) {
	if node == nil {
		return 0, 0, 0, 0
	}
	start := node.StartPoint()
	end := node.EndPoint()
	return int(start.Row) + 1, int(start.Column) + 1, int(end.Row) + 1, int(end.Column) + 1
}

func javaFindTypeNode(node *sitter.Node) *sitter.Node {
	if node == nil {
		return nil
	}
	if typed := node.ChildByFieldName("type"); typed != nil {
		return typed
	}
	for i := 0; i < int(node.NamedChildCount()); i++ {
		child := node.NamedChild(i)
		if child == nil {
			continue
		}
		if isJavaTypeNode(child.Type()) {
			return child
		}
	}
	return nil
}

func javaFindFirstNamedChildOfTypes(node *sitter.Node, types []string) *sitter.Node {
	if node == nil {
		return nil
	}
	typeSet := make(map[string]struct{}, len(types))
	for _, t := range types {
		typeSet[t] = struct{}{}
	}
	for i := 0; i < int(node.NamedChildCount()); i++ {
		child := node.NamedChild(i)
		if child == nil {
			continue
		}
		if _, ok := typeSet[child.Type()]; ok {
			return child
		}
	}
	return nil
}

func javaFindLastNamedChild(node *sitter.Node, nodeType string) *sitter.Node {
	if node == nil {
		return nil
	}
	for i := int(node.NamedChildCount()) - 1; i >= 0; i-- {
		child := node.NamedChild(i)
		if child == nil {
			continue
		}
		if child.Type() == nodeType {
			return child
		}
	}
	return nil
}

func isJavaTypeNode(nodeType string) bool {
	switch nodeType {
	case "type_identifier",
		"scoped_type_identifier",
		"generic_type",
		"array_type",
		"annotated_type",
		"integral_type",
		"floating_point_type",
		"boolean_type",
		"void_type":
		return true
	default:
		return false
	}
}

func normalizeJavaTypeName(raw string) string {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return ""
	}

	// Strip leading annotations inside types, e.g. "@Nonnull Foo".
	for strings.HasPrefix(raw, "@") {
		space := strings.IndexByte(raw, ' ')
		if space == -1 {
			return ""
		}
		raw = strings.TrimSpace(raw[space+1:])
	}

	if idx := strings.IndexByte(raw, '<'); idx != -1 {
		raw = raw[:idx]
	}
	raw = strings.TrimSpace(raw)
	for strings.HasSuffix(raw, "[]") {
		raw = strings.TrimSpace(strings.TrimSuffix(raw, "[]"))
	}
	raw = strings.TrimSuffix(raw, "...")
	return strings.TrimSpace(raw)
}

func javaDeclaredVariableNames(node *sitter.Node, src []byte) []string {
	if node == nil {
		return nil
	}

	if node.Type() == "formal_parameter" || node.Type() == "catch_formal_parameter" {
		if name := javaFindFirstIdentifier(node, src); name != "" {
			return []string{name}
		}
		return nil
	}

	var names []string
	for i := 0; i < int(node.NamedChildCount()); i++ {
		child := node.NamedChild(i)
		if child == nil {
			continue
		}
		switch child.Type() {
		case "variable_declarator":
			if name := javaVariableDeclaratorName(child, src); name != "" {
				names = append(names, name)
			}
		case "variable_declarator_list":
			for j := 0; j < int(child.NamedChildCount()); j++ {
				item := child.NamedChild(j)
				if item == nil || item.Type() != "variable_declarator" {
					continue
				}
				if name := javaVariableDeclaratorName(item, src); name != "" {
					names = append(names, name)
				}
			}
		}
	}

	if len(names) == 0 {
		walkNamedNodes(node, func(child *sitter.Node) {
			if child.Type() != "variable_declarator" {
				return
			}
			if name := javaVariableDeclaratorName(child, src); name != "" {
				names = append(names, name)
			}
		})
	}
	return uniqueTokens(names)
}

func javaVariableDeclaratorName(node *sitter.Node, src []byte) string {
	if node == nil {
		return ""
	}
	if name := node.ChildByFieldName("name"); name != nil {
		if name.Type() == "variable_declarator_id" {
			for i := 0; i < int(name.NamedChildCount()); i++ {
				child := name.NamedChild(i)
				if child != nil && child.Type() == "identifier" {
					return strings.TrimSpace(child.Content(src))
				}
			}
		}
		if name.Type() == "identifier" {
			return strings.TrimSpace(name.Content(src))
		}
		return strings.TrimSpace(name.Content(src))
	}
	id := node.ChildByFieldName("declarator")
	if id == nil {
		id = node.NamedChild(0)
	}
	if id == nil {
		return ""
	}
	if id.Type() == "variable_declarator_id" {
		for i := 0; i < int(id.NamedChildCount()); i++ {
			child := id.NamedChild(i)
			if child != nil && child.Type() == "identifier" {
				return strings.TrimSpace(child.Content(src))
			}
		}
	}
	if id.Type() == "identifier" {
		return strings.TrimSpace(id.Content(src))
	}
	return ""
}

func javaFindFirstIdentifier(node *sitter.Node, src []byte) string {
	var out string
	walkNamedNodes(node, func(child *sitter.Node) {
		if out != "" {
			return
		}
		if child.Type() == "identifier" {
			out = strings.TrimSpace(child.Content(src))
		}
	})
	return out
}
