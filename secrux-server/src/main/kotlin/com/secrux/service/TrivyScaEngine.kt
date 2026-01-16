package com.secrux.service

import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.config.TrivyProperties
import com.secrux.support.CommandRunner
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration

@Component
class TrivyScaEngine(
    private val commandRunner: CommandRunner,
    private val props: TrivyProperties
) : ScaEngine {

    override val id: String = "trivy"

    private val log = LoggerFactory.getLogger(TrivyScaEngine::class.java)

    override fun scan(request: ScaScanRequest): ScaScanArtifacts {
        Files.createDirectories(request.outputDir)
        return when (val target = request.target) {
            is ScaScanTarget.Filesystem -> scanFilesystem(target.path, request.outputDir)
            is ScaScanTarget.Image -> scanImage(target.ref, request.outputDir)
            is ScaScanTarget.Sbom -> scanSbom(target.path, request.outputDir)
        }
    }

    private fun scanFilesystem(path: Path, outputDir: Path): ScaScanArtifacts {
        val vulnJson = outputDir.resolve("trivy-vulns.json")
        val sbomJson = outputDir.resolve("sbom.cdx.json")
        runTrivy(listOf("fs", "--format", "json", "--output", vulnJson.toString(), path.toString()))
        runTrivy(listOf("fs", "--format", "cyclonedx", "--output", sbomJson.toString(), path.toString()))
        return ScaScanArtifacts(vulnerabilitiesJson = vulnJson, sbomJson = sbomJson, dependencyGraphJson = null)
    }

    private fun scanImage(ref: String, outputDir: Path): ScaScanArtifacts {
        val vulnJson = outputDir.resolve("trivy-vulns.json")
        val sbomJson = outputDir.resolve("sbom.cdx.json")
        runTrivy(listOf("image", "--format", "json", "--output", vulnJson.toString(), ref))
        runTrivy(listOf("image", "--format", "cyclonedx", "--output", sbomJson.toString(), ref))
        return ScaScanArtifacts(vulnerabilitiesJson = vulnJson, sbomJson = sbomJson, dependencyGraphJson = null)
    }

    private fun scanSbom(sbomPath: Path, outputDir: Path): ScaScanArtifacts {
        val vulnJson = outputDir.resolve("trivy-vulns.json")
        val sbomCopy = outputDir.resolve("sbom.input.json")
        Files.copy(sbomPath, sbomCopy, StandardCopyOption.REPLACE_EXISTING)
        runTrivy(listOf("sbom", "--format", "json", "--output", vulnJson.toString(), sbomCopy.toString()))
        return ScaScanArtifacts(vulnerabilitiesJson = vulnJson, sbomJson = sbomCopy, dependencyGraphJson = null)
    }

    private fun runTrivy(args: List<String>) {
        val command = mutableListOf(props.executable)
        command.addAll(args)
        if (props.additionalArgs.isNotEmpty()) {
            command.addAll(props.additionalArgs)
        }
        val timeout = Duration.ofSeconds(props.timeoutSeconds)
        val environment =
            buildMap<String, String> {
                props.cacheDir?.takeIf { it.isNotBlank() }?.let { put("TRIVY_CACHE_DIR", it) }
            }
        log.info("event=trivy_exec_started timeout={} command={}", timeout, command.joinToString(" "))
        val result = commandRunner.run(command, workingDirectory = null, timeout = timeout, environment = environment)
        if (result.exitCode != 0) {
            throw SecruxException(
                ErrorCode.SCAN_EXECUTION_FAILED,
                "Trivy failed with exit code ${result.exitCode}: ${result.stderr.ifBlank { result.stdout }}"
            )
        }
    }
}
