package com.secrux.service

import java.nio.file.Path

sealed interface ScaScanTarget {
    data class Filesystem(val path: Path) : ScaScanTarget
    data class Image(val ref: String) : ScaScanTarget
    data class Sbom(val path: Path) : ScaScanTarget
}

data class ScaScanArtifacts(
    val vulnerabilitiesJson: Path,
    val sbomJson: Path?,
    val dependencyGraphJson: Path?
)

data class ScaScanRequest(
    val target: ScaScanTarget,
    val outputDir: Path
)

interface ScaEngine {
    val id: String

    fun scan(request: ScaScanRequest): ScaScanArtifacts
}

