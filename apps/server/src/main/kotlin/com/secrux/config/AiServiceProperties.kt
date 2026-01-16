package com.secrux.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("secrux.ai.service")
data class AiServiceProperties(
    val baseUrl: String = "http://localhost:5156",
    val token: String = "local-dev-token"
)
