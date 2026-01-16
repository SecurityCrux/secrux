package com.secrux.service

import com.secrux.dto.CodeLineDto
import com.secrux.dto.CodeSnippetDto
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

@Service
class ScaIssueSnippetService(
    private val evidenceService: FindingEvidenceService
) {

    fun getUsageSnippet(
        taskId: UUID,
        path: String,
        line: Int,
        context: Int,
        receiver: String? = null,
        symbol: String? = null,
        kind: String? = null,
        language: String? = null
    ): CodeSnippetDto? {
        val safeContext = context.coerceIn(0, 50)
        if (line <= 0) return null

        val filePath = evidenceService.resolveTaskWorkspaceFilePath(taskId, path)
        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            return null
        }

        val lines = Files.readAllLines(filePath)
        if (lines.isEmpty()) return null

        val safeLine = line.coerceIn(1, lines.size)
        val guessedLanguage = language?.trim()?.takeIf { it.isNotBlank() } ?: guessLanguage(filePath)

        val anchors = buildList {
            add(Anchor(line = safeLine, radius = safeContext))

            if (guessedLanguage == "java") {
                val importLine = findJavaImportAnchor(lines, symbol, receiver)
                if (importLine != null && importLine != safeLine) {
                    add(Anchor(line = importLine, radius = 2))
                }

                val receiverName = receiver?.trim()?.takeIf { it.isNotBlank() }
                if (kind?.trim()?.lowercase() == "call" && receiverName != null && isJavaIdentifier(receiverName)) {
                    val declLine = findJavaReceiverDeclaration(lines, safeLine, receiverName)
                    if (declLine != null && declLine != safeLine) {
                        add(Anchor(line = declLine, radius = min(3, safeContext)))
                    }
                }
            }
        }

        val highlightLines = anchors.map { it.line }.toSet()
        val ranges = mergeRanges(
            anchors.map { anchor ->
                val start = max(1, anchor.line - anchor.radius)
                val end = min(lines.size, anchor.line + anchor.radius)
                start..end
            }
        )

        val snippetLines = buildSnippetLines(lines, ranges, highlightLines, maxOutputLines = 260)
        if (snippetLines.isEmpty()) return null

        val displayPath = evidenceService.normalizeWorkspaceNodeFile(taskId, filePath.toString()) ?: path
        val startLine = ranges.firstOrNull()?.first ?: safeLine
        val endLine = ranges.lastOrNull()?.last ?: safeLine

        return CodeSnippetDto(
            path = displayPath,
            startLine = startLine,
            endLine = endLine,
            lines = snippetLines
        )
    }

    private data class Anchor(val line: Int, val radius: Int)

    private fun guessLanguage(path: Path): String? {
        val lower = path.fileName?.toString()?.lowercase() ?: return null
        return when {
            lower.endsWith(".java") -> "java"
            lower.endsWith(".kt") -> "kotlin"
            lower.endsWith(".js") -> "javascript"
            lower.endsWith(".jsx") -> "jsx"
            lower.endsWith(".ts") || lower.endsWith(".tsx") -> "typescript"
            lower.endsWith(".go") -> "go"
            lower.endsWith(".py") -> "python"
            lower.endsWith(".rb") -> "ruby"
            lower.endsWith(".php") -> "php"
            lower.endsWith(".rs") -> "rust"
            lower.endsWith(".yml") || lower.endsWith(".yaml") -> "yaml"
            lower.endsWith(".json") -> "json"
            else -> null
        }
    }

    private fun mergeRanges(ranges: List<IntRange>): List<IntRange> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.sortedBy { it.first }
        val merged = mutableListOf<IntRange>()
        var current = sorted.first()
        for (next in sorted.drop(1)) {
            if (next.first <= current.last + 1) {
                current = current.first..max(current.last, next.last)
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged
    }

    private fun buildSnippetLines(
        fileLines: List<String>,
        ranges: List<IntRange>,
        highlightLines: Set<Int>,
        maxOutputLines: Int
    ): List<CodeLineDto> {
        val out = mutableListOf<CodeLineDto>()
        var gapCounter = 1

        fun appendGap() {
            out.add(CodeLineDto(lineNumber = -gapCounter, content = "…", highlight = false))
            gapCounter++
        }

        for ((rangeIndex, range) in ranges.withIndex()) {
            if (out.size >= maxOutputLines) break
            if (rangeIndex > 0) {
                appendGap()
            }

            var lineNo = range.first
            while (lineNo <= range.last) {
                if (out.size >= maxOutputLines) break
                val content = fileLines.getOrNull(lineNo - 1) ?: ""
                val trimmed = content.trimEnd()

                if (trimmed.isBlank()) {
                    var blankEnd = lineNo
                    while (blankEnd <= range.last && (fileLines.getOrNull(blankEnd - 1)?.trimEnd()?.isBlank() == true)) {
                        blankEnd++
                    }
                    val blankCount = blankEnd - lineNo
                    if (blankCount >= 2) {
                        appendGap()
                        lineNo = blankEnd
                        continue
                    }
                }

                out.add(
                    CodeLineDto(
                        lineNumber = lineNo,
                        content = content.take(500),
                        highlight = highlightLines.contains(lineNo)
                    )
                )
                lineNo++
            }
        }

        return out
    }

    private fun findJavaImportAnchor(lines: List<String>, symbol: String?, receiver: String?): Int? {
        val hints = buildList {
            val sym = symbol?.trim()?.takeIf { it.isNotBlank() }
            if (sym != null) {
                add(sym)
                val simple = sym.substringAfterLast('.')
                if (simple.isNotBlank() && simple != sym) add(simple)
            }
            val recv = receiver?.trim()?.takeIf { it.isNotBlank() }
            if (recv != null) {
                add(recv)
                if (recv.contains('.')) {
                    val simple = recv.substringAfterLast('.')
                    if (simple.isNotBlank() && simple != recv) add(simple)
                }
            }
        }.map { it.lowercase() }
        if (hints.isEmpty()) return null

        val limit = min(lines.size, 600)
        for (idx in 0 until limit) {
            val raw = lines[idx].trim()
            if (!raw.startsWith("import ")) continue
            val lower = raw.lowercase()
            if (hints.any { hint -> hint.isNotBlank() && lower.contains(hint) }) {
                return idx + 1
            }
        }
        return null
    }

    private fun findJavaReceiverDeclaration(lines: List<String>, callLine: Int, receiver: String): Int? {
        val escaped = Regex.escape(receiver)
        val assign = Regex("""\b$escaped\b\s*=""")
        val word = Regex("""\b$escaped\b""")
        val scanFrom = (callLine - 1).coerceAtMost(lines.size)
        val scanTo = (callLine - 260).coerceAtLeast(1)

        for (idx in scanFrom downTo scanTo) {
            val raw = lines[idx - 1]
            val trimmed = raw.trim()
            if (trimmed.isBlank()) continue
            if (trimmed.startsWith("//")) continue
            if (trimmed.contains("$receiver.")) continue
            if (!word.containsMatchIn(trimmed)) continue
            if (assign.containsMatchIn(trimmed)) return idx
        }

        for (idx in scanFrom downTo scanTo) {
            val raw = lines[idx - 1]
            val trimmed = raw.trim()
            if (trimmed.isBlank()) continue
            if (trimmed.startsWith("//")) continue
            if (trimmed.contains("$receiver.")) continue
            if (!word.containsMatchIn(trimmed)) continue
            if (trimmed.endsWith(";") || trimmed.endsWith(")")) {
                return idx
            }
        }

        return null
    }

    private fun isJavaIdentifier(value: String): Boolean {
        if (value.isBlank()) return false
        val first = value.first()
        if (!(first == '_' || first == '$' || first.isLetter())) return false
        return value.all { ch -> ch == '_' || ch == '$' || ch.isLetterOrDigit() }
    }
}

