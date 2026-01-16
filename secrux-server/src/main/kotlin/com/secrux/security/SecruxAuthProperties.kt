package com.secrux.security

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "secrux.auth")
data class SecruxAuthProperties(
    val mode: AuthMode = AuthMode.KEYCLOAK,
    val issuerUri: String? = null,
    /**
     * Additional issuer values to accept during JWT validation.
     *
     * This is useful in local Docker setups where Keycloak is reached via `localhost` in the browser
     * but via a service hostname (e.g. `http://keycloak:8081`) from within containers.
     */
    val issuerAliases: List<String> = emptyList(),
    val audience: String? = null,
    val localSecret: String = "dev-secret-should-be-at-least-32-bytes-long-1234",
    val accessTokenTtl: Duration = Duration.ofMinutes(15),
    val refreshTokenTtl: Duration = Duration.ofDays(30),
    val tenantClaim: String = "tenant_id",
    val userClaim: String = "sub",
    val emailClaim: String = "email",
    val nameClaim: String = "preferred_username",
    val rolePaths: List<String> = listOf("realm_access.roles")
)

enum class AuthMode {
    KEYCLOAK,
    LOCAL
}
