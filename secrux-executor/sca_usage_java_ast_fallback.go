//go:build !cgo

package main

func extractJavaOccurrencesAst(
	src []byte,
	lines []string,
	imports []javaImportDecl,
	groupIDs []string,
) []javaUsageOccurrence {
	return extractJavaOccurrencesHeuristic(src, lines, imports, groupIDs)
}
