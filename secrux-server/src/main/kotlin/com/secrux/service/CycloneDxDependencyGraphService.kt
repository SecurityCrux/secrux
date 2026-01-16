package com.secrux.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path

data class DependencyGraphNode(
    val id: String,
    val label: String,
    val purl: String? = null,
    val name: String? = null,
    val version: String? = null
)

data class DependencyGraphEdge(
    val source: String,
    val target: String
)

data class DependencyGraph(
    val nodes: List<DependencyGraphNode>,
    val edges: List<DependencyGraphEdge>
)

@Service
class CycloneDxDependencyGraphService(
    private val objectMapper: ObjectMapper
) {

    fun buildFromSbom(sbomPath: Path): DependencyGraph {
        if (!Files.exists(sbomPath) || Files.isDirectory(sbomPath)) {
            return DependencyGraph(nodes = emptyList(), edges = emptyList())
        }
        val root = runCatching { objectMapper.readTree(sbomPath.toFile()) }.getOrNull()
            ?: return DependencyGraph(nodes = emptyList(), edges = emptyList())
        if (!isCycloneDx(root)) {
            return DependencyGraph(nodes = emptyList(), edges = emptyList())
        }
        val componentIndex = indexComponents(root.path("components"))
        val dependencies = root.path("dependencies")
        val edges = mutableListOf<DependencyGraphEdge>()
        val refs = mutableSetOf<String>()
        if (dependencies.isArray) {
            dependencies.forEach { dep ->
                val ref = dep.path("ref").asText(null)?.trim()
                if (ref.isNullOrBlank()) return@forEach
                refs.add(ref)
                val dependsOn = dep.path("dependsOn")
                if (dependsOn.isArray) {
                    dependsOn.forEach { child ->
                        val target = child.asText(null)?.trim()
                        if (!target.isNullOrBlank()) {
                            refs.add(target)
                            edges.add(DependencyGraphEdge(source = ref, target = target))
                        }
                    }
                }
            }
        }
        componentIndex.keys.forEach { refs.add(it) }
        val nodes =
            refs.map { id ->
                val component = componentIndex[id]
                val purl = component?.purl
                val name = component?.name
                val version = component?.version
                DependencyGraphNode(
                    id = id,
                    label =
                        purl
                            ?: listOfNotNull(name, version).joinToString("@").ifBlank { id },
                    purl = purl,
                    name = name,
                    version = version
                )
            }
        return DependencyGraph(nodes = nodes, edges = edges)
    }

    fun writeGraph(sbomPath: Path, outputPath: Path): DependencyGraph {
        val graph = buildFromSbom(sbomPath)
        Files.createDirectories(outputPath.parent)
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), graph)
        return graph
    }

    private fun isCycloneDx(root: JsonNode): Boolean {
        val format = root.path("bomFormat").asText("").trim()
        return format.equals("CycloneDX", ignoreCase = true)
    }

    private data class ComponentMeta(
        val name: String?,
        val version: String?,
        val purl: String?
    )

    private fun indexComponents(components: JsonNode): Map<String, ComponentMeta> {
        if (!components.isArray) return emptyMap()
        val map = LinkedHashMap<String, ComponentMeta>()
        components.forEach { comp ->
            val ref = comp.path("bom-ref").asText(null)?.trim()
                ?: comp.path("bomRef").asText(null)?.trim()
            if (ref.isNullOrBlank()) return@forEach
            map[ref] =
                ComponentMeta(
                    name = comp.path("name").asText(null),
                    version = comp.path("version").asText(null),
                    purl = comp.path("purl").asText(null)
                )
        }
        return map
    }
}

