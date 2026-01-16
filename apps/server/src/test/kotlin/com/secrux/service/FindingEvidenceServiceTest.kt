package com.secrux.service

import com.secrux.dto.CallChainDto
import com.secrux.dto.CallChainStepDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class FindingEvidenceServiceTest {

    private val service = FindingEvidenceService(workspaceRootDir = "build/workspaces")

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `extractSnippetFromEvidence returns snippet when present`() {
        val evidence =
            mapOf(
                "codeSnippet" to
                    mapOf(
                        "path" to "src/main/java/App.java",
                        "startLine" to 10,
                        "endLine" to 12,
                        "lines" to
                            listOf(
                                mapOf("lineNumber" to 10, "content" to "line10", "highlight" to false),
                                mapOf("lineNumber" to 11, "content" to "line11", "highlight" to true),
                                mapOf("lineNumber" to 12, "content" to "line12", "highlight" to false),
                            ),
                    ),
            )

        val snippet = service.extractSnippetFromEvidence(evidence)
        assertNotNull(snippet)
        assertEquals("src/main/java/App.java", snippet!!.path)
        assertEquals(10, snippet.startLine)
        assertEquals(12, snippet.endLine)
        assertEquals(3, snippet.lines.size)
        assertEquals(true, snippet.lines[1].highlight)
    }

    @Test
    fun `extractEnrichmentFromEvidence returns enrichment when present`() {
        val evidence =
            mapOf(
                "enrichment" to
                    mapOf(
                        "engine" to "intellij-psi-uast-enricher",
                        "primary" to mapOf("path" to "src/A.java", "line" to 1),
                    ),
            )

        val enrichment = service.extractEnrichmentFromEvidence(evidence)
        assertNotNull(enrichment)
        assertEquals("intellij-psi-uast-enricher", enrichment!!["engine"])
    }

    @Test
    fun `extractSnippetFromDataflowNode falls back to node value when codeSnippet absent`() {
        val evidence =
            mapOf(
                "dataflow" to
                    mapOf(
                        "nodes" to
                            listOf(
                                mapOf("id" to "n0", "file" to "src/A.java", "line" to 42, "value" to "danger()"),
                            ),
                        "edges" to emptyList<Map<String, Any?>>(),
                    ),
            )
        val snippet = service.extractSnippetFromDataflowNode(evidence, path = "src/A.java", line = 42)
        assertNotNull(snippet)
        assertEquals(42, snippet!!.startLine)
        assertEquals(1, snippet.lines.size)
        assertEquals("danger()", snippet.lines[0].content)
    }

    @Test
    fun `extractSnippetFromEvidence returns null when snippet missing`() {
        assertNull(service.extractSnippetFromEvidence(null))
        assertNull(service.extractSnippetFromEvidence(emptyMap()))
        assertNull(service.extractSnippetFromEvidence(mapOf("x" to 1)))
    }

    @Test
    fun `normalizeCallChainsForDisplay shifts doc comment step to method signature`() {
        val workspaceRoot = tempDir.resolve("workspaces")
        val service = FindingEvidenceService(workspaceRootDir = workspaceRoot.toString())
        val taskId = UUID.randomUUID()
        val file = workspaceRoot.resolve(taskId.toString()).resolve("src/A.java")
        Files.createDirectories(file.parent)
        Files.writeString(
            file,
            """
            /**
             * doc
             */
            @GetMapping("/x")
            public String foo(String imgFile) {
              return imgFile;
            }
            """.trimIndent(),
        )

        val callChains =
            listOf(
                CallChainDto(
                    chainId = "chain1",
                    steps =
                        listOf(
                            CallChainStepDto(
                                nodeId = "n0",
                                role = "SOURCE",
                                label = "/**",
                                file = "src/A.java",
                                line = 1,
                                snippet = "/**",
                            ),
                        ),
                ),
            )

        val normalized = service.normalizeCallChainsForDisplay(taskId = taskId, callChains = callChains, maxLookahead = 20)
        val step = normalized.single().steps.single()
        assertEquals(5, step.line)
        assertEquals("public String foo(String imgFile) {", step.label.trim())
        assertEquals("public String foo(String imgFile) {", step.snippet!!.trim())
    }
}
