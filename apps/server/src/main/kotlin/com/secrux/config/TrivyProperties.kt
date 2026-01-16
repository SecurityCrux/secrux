package com.secrux.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "secrux.scan.trivy")
data class TrivyProperties(
    val executable: String = "trivy",
    val timeoutSeconds: Long = 600,
    val additionalArgs: List<String> = emptyList(),
    val cacheDir: String? = null
)

