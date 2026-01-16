package com.secrux.service

import com.secrux.dto.ResultProcessRequest
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.streams.toList

@Component
class SarifArtifactResolver(
    private val semgrepSarifConverter: SemgrepSarifConverter
) {

    fun resolveSarifPaths(taskId: UUID, request: ResultProcessRequest): List<String> {
        val provided = request.sarifLocations
        val candidatePaths: List<Path> =
            if (provided.isNotEmpty()) {
                provided.map { Path.of(it) }
            } else {
                val defaultDir = Path.of("build", "sarif", taskId.toString())
                if (!Files.exists(defaultDir)) emptyList()
                else Files.list(defaultDir).use { stream ->
                    stream.filter(Files::isRegularFile).toList()
                }
            }
        if (candidatePaths.isEmpty()) return emptyList()
        val resolved = mutableListOf<String>()
        candidatePaths.forEach { path ->
            when {
                isSarifArtifact(path) -> resolved.add(path.toAbsolutePath().toString())
                semgrepSarifConverter.supports(path) -> {
                    val converted = semgrepSarifConverter.convert(path)
                    resolved.add(converted.toAbsolutePath().toString())
                }
            }
        }
        return resolved
    }

    private fun isSarifArtifact(path: Path): Boolean {
        val name = path.fileName.toString().lowercase()
        return name.endsWith(".sarif") || name.endsWith(".sarif.json")
    }
}

