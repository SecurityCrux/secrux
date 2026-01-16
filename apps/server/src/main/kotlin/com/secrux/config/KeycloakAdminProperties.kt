package com.secrux.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "secrux.keycloak.admin")
data class KeycloakAdminProperties(
    val enabled: Boolean = false,
    val baseUrl: String = "http://localhost:8080",
    val realm: String = "secrux",
    val clientId: String = "",
    val clientSecret: String = ""
)
