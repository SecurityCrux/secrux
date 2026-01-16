package com.secrux.security

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KeycloakRealmRoleCoverageTest {

    private val objectMapper = ObjectMapper()

    @Test
    fun `keycloak realm import defines roles for every AuthorizationAction`() {
        val realm = loadRealmImport()
        val roleNames = realm.path("roles").path("realm").mapNotNull { it.path("name").textValue() }.toSet()

        val missing = AuthorizationAction.values()
            .map { it.value }
            .filterNot { it in roleNames }

        assertTrue(missing.isEmpty(), "Missing realm roles for actions: $missing")
    }

    @Test
    fun `secrux_admin composite includes every AuthorizationAction`() {
        val realm = loadRealmImport()
        val secruxAdmin =
            realm.path("roles").path("realm")
                .firstOrNull { it.path("name").asText() == "secrux_admin" }
                ?: error("keycloak realm import missing secrux_admin role")

        val composites = secruxAdmin.path("composites").path("realm").map { it.asText() }.toSet()
        val missing = AuthorizationAction.values()
            .map { it.value }
            .filterNot { it in composites }

        assertTrue(missing.isEmpty(), "secrux_admin composite missing actions: $missing")
    }

    private fun loadRealmImport(): com.fasterxml.jackson.databind.JsonNode {
        val realmPath = realmImportCandidates().firstOrNull { Files.exists(it) }
            ?: error("keycloak realm import not found (tried: ${realmImportCandidates().joinToString()})")
        return objectMapper.readTree(realmPath.toFile())
    }

    private fun realmImportCandidates(): List<Path> =
        listOf(
            Paths.get("keycloak", "realm-secrux.json"),
            Paths.get("..", "keycloak", "realm-secrux.json"),
        )
}
