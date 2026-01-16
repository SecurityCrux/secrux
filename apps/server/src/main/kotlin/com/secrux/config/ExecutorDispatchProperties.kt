package com.secrux.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "secrux.executor.dispatch")
data class ExecutorDispatchProperties(
    val apiBaseUrl: String? = null,
    val defaultTimeoutSeconds: Int = 900
)

