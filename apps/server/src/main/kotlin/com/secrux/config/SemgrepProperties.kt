package com.secrux.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "secrux.scan.semgrep")
data class SemgrepProperties(
    val executable: String = "semgrep",
    val config: String = "auto",
    val timeoutSeconds: Long = 180,
    val additionalArgs: List<String> = emptyList(),
    val workingDirectory: String? = null
)

