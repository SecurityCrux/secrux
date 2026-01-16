package com.secrux.service.enrichment

import com.secrux.domain.Finding
import com.secrux.dto.CodeSnippetDto
import com.secrux.dto.DataFlowEdgeDto
import com.secrux.dto.DataFlowNodeDto
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import org.springframework.stereotype.Component

@Component
class JavaCodeEnricher(
    private val clock: Clock
) : CodeEnricher {

    override fun supports(path: String): Boolean = path.endsWith(".java", ignoreCase = true)

    override fun enrich(
        finding: Finding,
        snippet: CodeSnippetDto?,
        dataFlowNodes: List<DataFlowNodeDto>,
        dataFlowEdges: List<DataFlowEdgeDto>,
    ): Map<String, Any?>? {
        val primaryPathRaw = finding.location["path"]?.toString() ?: return null
        val primaryLine = extractLine(finding.location) ?: return null
        val primaryPath = Path.of(primaryPathRaw)
        if (!Files.exists(primaryPath) || !Files.isRegularFile(primaryPath)) {
            return null
        }

        val maxFiles = 6
        val maxNodes = 10
        val maxMethodChars = 25_000
        val maxCallSamples = 80
        val maxConditions = 40
        val maxExternalSymbols = 40
        val neighborhoodWindowLines = 25
        val maxNeighborhoodConditions = 12
        val maxNeighborhoodInvocations = 20
        val maxNeighborhoodFieldDefs = 12

        val nodeLocations =
            dataFlowNodes
                .asSequence()
                .mapNotNull { node ->
                    val file = node.file?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val line = node.line ?: return@mapNotNull null
                    NodeLocation(nodeId = node.id, label = node.label, path = file, line = line)
                }
                .distinctBy { "${it.path}:${it.line}:${it.nodeId}" }
                .take(maxNodes)
                .toList()

        val filesToParse =
            (nodeLocations.map { it.path } + primaryPathRaw)
                .distinct()
                .take(maxFiles)

        val indexes =
            filesToParse.mapNotNull { path ->
                val p = Path.of(path)
                if (!Files.exists(p) || !Files.isRegularFile(p)) return@mapNotNull null
                runCatching { JavaAstIndex.get(p) }.getOrNull()
            }

        val primaryIndex =
            indexes.firstOrNull { it.path.toString() == primaryPathRaw }
                ?: runCatching { JavaAstIndex.get(primaryPath) }.getOrNull()
        val primaryMethod = primaryIndex?.findEnclosingMethod(primaryLine)

        val dataflowMethods =
            nodeLocations.mapNotNull { loc ->
                val idx = indexes.firstOrNull { it.path.toString() == loc.path } ?: return@mapNotNull null
                val m = idx.findEnclosingMethod(loc.line) ?: return@mapNotNull null
                val neighborhood = idx.buildNeighborhood(method = m.tree, focusLine = loc.line, window = neighborhoodWindowLines)
                val neighborhoodSymbols =
                    neighborhood.externalSymbols
                        .asSequence()
                        .map { normalizeJavaSymbol(it) }
                        .filter { it.isNotBlank() }
                        .distinct()
                        .take(maxExternalSymbols)
                        .toList()
                val neighborhoodFieldDefs =
                    if (neighborhoodSymbols.isNotEmpty()) {
                        idx.findFieldDefinitionsAround(m.tree, neighborhoodSymbols).take(maxNeighborhoodFieldDefs)
                    } else {
                        emptyList()
                    }
                mapOf(
                    "nodeId" to loc.nodeId,
                    "label" to loc.label,
                    "path" to idx.path.toString(),
                    "line" to loc.line,
                    "method" to m.toSummary(maxChars = maxMethodChars),
                    "neighborhood" to mapOf(
                        "windowLines" to neighborhoodWindowLines,
                        "conditions" to neighborhood.conditions.take(maxNeighborhoodConditions),
                        "invocations" to neighborhood.invocations.take(maxNeighborhoodInvocations),
                        "externalSymbols" to neighborhoodSymbols,
                        "fieldDefinitions" to neighborhoodFieldDefs,
                    ),
                )
            }

        val primarySummary = primaryMethod?.toSummary(maxChars = maxMethodChars)

        val nodeLineSetByFile = nodeLocations.groupBy { it.path }.mapValues { (_, list) -> list.map { it.line }.toSet() }

        val primaryInvocations =
            if (primaryIndex != null && primaryMethod != null) {
                val invocations = primaryIndex.collectInvocations(primaryMethod.tree)
                val fileNodeLines = nodeLineSetByFile[primaryIndex.path.toString()].orEmpty()
                invocations.take(maxCallSamples).map { inv ->
                    mapOf(
                        "line" to inv.line,
                        "text" to inv.text,
                        "inDataflow" to fileNodeLines.contains(inv.line),
                        "argIdentifiers" to inv.argIdentifiers,
                    )
                }
            } else {
                emptyList()
            }

        val primaryConditions =
            if (primaryIndex != null && primaryMethod != null) {
                primaryIndex.collectConditions(primaryMethod.tree).take(maxConditions).map { cond ->
                    mapOf(
                        "line" to cond.line,
                        "text" to cond.text,
                        "externalSymbols" to cond.externalSymbols,
                    )
                }
            } else {
                emptyList()
            }

        val externalSymbols =
            (if (primaryIndex != null && primaryMethod != null) {
                primaryIndex.collectExternalSymbols(primaryMethod.tree)
            } else {
                emptySet()
            }) +
                (primaryConditions.flatMap { (it["externalSymbols"] as? List<*>)?.filterIsInstance<String>().orEmpty() })

        val externalSymbolSamples =
            externalSymbols
                .asSequence()
                .map { normalizeJavaSymbol(it) }
                .filter { it.isNotBlank() }
                .distinct()
                .take(maxExternalSymbols)
                .toList()

        val fieldDefinitions =
            if (primaryIndex != null && primaryMethod != null && externalSymbolSamples.isNotEmpty()) {
                primaryIndex.findFieldDefinitionsAround(primaryMethod.tree, externalSymbolSamples).take(30)
            } else {
                emptyList()
            }

        val roleByNodeId = dataFlowNodes.associate { it.id to it.role }

        fun reason(
            code: String,
            titleZh: String,
            titleEn: String,
            detailsZh: String,
            detailsEn: String,
        ): Map<String, Any?> =
            mapOf(
                "code" to code,
                "titleI18n" to mapOf("zh" to titleZh, "en" to titleEn),
                "detailsI18n" to mapOf("zh" to detailsZh, "en" to detailsEn),
            )

        val blocks =
            buildList<Map<String, Any?>>() {
                if (primarySummary != null) {
                    add(
                        mapOf(
                            "id" to "primary.method",
                            "kind" to "METHOD",
                            "reason" to
                                reason(
                                    code = "PRIMARY_METHOD",
                                    titleZh = "主方法上下文",
                                    titleEn = "Primary method context",
                                    detailsZh = "漏洞命中点所在方法的完整上下文（用于人类/AI 理解漏洞发生位置与周边逻辑）。",
                                    detailsEn = "Full method context containing the finding location (for humans/AI to understand surrounding logic).",
                                ),
                            "file" to mapOf("path" to primaryPathRaw, "language" to "java"),
                            "range" to mapOf("startLine" to primarySummary["startLine"], "endLine" to primarySummary["endLine"], "highlightLines" to listOf(primaryLine)),
                            "related" to mapOf("findingLine" to primaryLine),
                            "method" to primarySummary,
                            "conditions" to primaryConditions,
                            "invocations" to primaryInvocations,
                        ),
                    )
                }
                for (nodeMethod in dataflowMethods) {
                    val nodeId = nodeMethod["nodeId"]?.toString() ?: continue
                    val label = nodeMethod["label"]?.toString() ?: nodeId
                    val path = nodeMethod["path"]?.toString()
                    val line = nodeMethod["line"] as? Int
                    val method = nodeMethod["method"]
                    add(
                        mapOf(
                            "id" to "node.$nodeId.method",
                            "kind" to "METHOD",
                            "reason" to
                                reason(
                                    code = "DATAFLOW_NODE_METHOD",
                                    titleZh = "链路节点方法上下文",
                                    titleEn = "Chain-node method context",
                                    detailsZh = "数据流节点（$label）所在方法的上下文，用于解释该节点的周边逻辑与潜在约束条件。",
                                    detailsEn = "Context of the method containing the dataflow node ($label), to explain nearby logic and possible constraints.",
                                ),
                            "file" to (path?.let { p -> mapOf("path" to p, "language" to "java") }),
                            "range" to mapOf("highlightLines" to listOfNotNull(line)),
                            "related" to mapOf("nodeId" to nodeId, "label" to label, "role" to roleByNodeId[nodeId]),
                            "method" to method,
                            "conditions" to (nodeMethod["neighborhood"] as? Map<*, *>)?.get("conditions"),
                            "invocations" to (nodeMethod["neighborhood"] as? Map<*, *>)?.get("invocations"),
                        ),
                    )
                }
            }

        return mapOf(
            "engine" to "java-ast-enricher",
            "version" to 2,
            "generatedAt" to clock.instant().toString(),
            "blocks" to blocks,
            "primary" to mapOf(
                "path" to primaryPathRaw,
                "line" to primaryLine,
                "method" to primarySummary,
                "conditions" to primaryConditions,
                "invocations" to primaryInvocations,
            ),
            "dataflow" to mapOf(
                "nodes" to dataFlowNodes.map { mapOf("id" to it.id, "label" to it.label, "file" to it.file, "line" to it.line) },
                "edges" to dataFlowEdges.map { mapOf("source" to it.source, "target" to it.target, "label" to it.label) },
                "nodeMethods" to dataflowMethods,
            ),
            "externalSymbols" to externalSymbolSamples,
            "fieldDefinitions" to fieldDefinitions,
            "limits" to mapOf(
                "maxFiles" to maxFiles,
                "maxNodes" to maxNodes,
                "maxMethodChars" to maxMethodChars,
                "maxCallSamples" to maxCallSamples,
                "neighborhoodWindowLines" to neighborhoodWindowLines,
            ),
        )
    }

    private data class NodeLocation(
        val nodeId: String,
        val label: String,
        val path: String,
        val line: Int
    )

    private fun extractLine(location: Map<String, Any?>): Int? {
        val raw = location["line"] ?: location["startLine"] ?: return null
        return when (raw) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull()
            else -> null
        }
    }
}
