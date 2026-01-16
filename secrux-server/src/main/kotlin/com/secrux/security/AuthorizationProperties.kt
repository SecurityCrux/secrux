package com.secrux.security

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "secrux.authz")
data class AuthorizationProperties(
    val enabled: Boolean = false,
    val opaUrl: String = "http://localhost:8181",
    val policyPath: String = "/v1/data/secrux/allow",
    val failOpen: Boolean = false,
    val timeout: Duration = Duration.ofSeconds(2)
)

