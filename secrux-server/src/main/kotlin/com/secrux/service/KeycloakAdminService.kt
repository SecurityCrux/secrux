package com.secrux.service

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.config.KeycloakAdminProperties
import com.secrux.dto.UserCreateRequest
import com.secrux.dto.UserListResponse
import com.secrux.dto.UserPasswordResetRequest
import com.secrux.dto.UserProfileUpdateRequest
import com.secrux.dto.UserStatusUpdateRequest
import com.secrux.dto.UserSummary
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import java.util.Base64
import java.util.UUID

@Service
class KeycloakAdminService(
    private val properties: KeycloakAdminProperties,
    builder: RestClient.Builder,
) {
    private val log = LoggerFactory.getLogger(KeycloakAdminService::class.java)
    private val client: RestClient = builder.baseUrl(properties.baseUrl).build()

    @Volatile
    private var tenantIdUserProfileEnsured: Boolean = false

    fun listUsers(
        search: String?,
        limit: Int,
        offset: Int,
    ): UserListResponse {
        ensureEnabled()
        val token = adminToken()
        val response =
            client
                .get()
                .uri { uri ->
                    uri
                        .path("/admin/realms/${properties.realm}/users")
                        .queryParam("search", search?.takeIf { it.isNotBlank() })
                        .queryParam("first", offset)
                        .queryParam("max", limit)
                        .build()
                }.headers { it.setBearerAuth(token) }
                .retrieve()
                .onStatus({ it.is4xxClientError }, log4xx("listing users"))
                .body(List::class.java) ?: emptyList<Any?>()
        val items = response.mapNotNull { mapUser(it) }
        return UserListResponse(
            items = items,
            total = items.size.toLong(), // Keycloak does not return total; approximate with page size
            limit = limit,
            offset = offset,
        )
    }

    fun createUser(
        tenantId: UUID,
        request: UserCreateRequest
    ): UserSummary {
        ensureEnabled()
        val token = adminToken()
        ensureTenantIdUserProfileAttribute(token)
        val username = request.username.trim()
        if (username.isBlank()) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "username is required")
        }
        val email =
            request.email
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "$username@$tenantId.secrux.local"
        val defaultName = username
        val createResponse =
            client
                .post()
                .uri("/admin/realms/${properties.realm}/users")
                .headers { it.setBearerAuth(token) }
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    mapOf(
                        "username" to username,
                        "email" to email,
                        "emailVerified" to true,
                        "firstName" to defaultName,
                        "lastName" to defaultName,
                        "enabled" to request.enabled,
                        "requiredActions" to emptyList<String>(),
                        "attributes" to mapOf("tenant_id" to listOf(tenantId.toString())),
                    ),
                ).retrieve()
                .onStatus({ it.is4xxClientError }, log4xx("creating user ${request.username}"))
                .toBodilessEntity()
        val location = createResponse.headers.location?.path ?: throw SecruxException(ErrorCode.VALIDATION_ERROR, "User creation failed")
        val id = location.substringAfterLast("/")
        if (!request.password.isNullOrBlank()) {
            resetPassword(id, UserPasswordResetRequest(request.password, temporary = false), token)
            clearRequiredActions(id, token)
        }
        return UserSummary(
            id = id,
            username = username,
            email = email,
            enabled = request.enabled,
        )
    }

    fun upsertTenantIdAttribute(
        userId: String,
        tenantId: UUID,
        fallbackName: String,
        fallbackEmail: String,
    ) {
        ensureEnabled()
        val token = adminToken()
        ensureTenantIdUserProfileAttribute(token)
        val raw =
            client
                .get()
                .uri("/admin/realms/${properties.realm}/users/$userId")
                .headers { it.setBearerAuth(token) }
                .retrieve()
                .onStatus({ it.is4xxClientError }, log4xx("fetching user $userId"))
                .body(Map::class.java)
                ?: emptyMap<String, Any?>()

        val rawEmail = raw["email"]?.toString()?.trim()
        val email = rawEmail?.takeIf { it.isNotBlank() } ?: fallbackEmail
        val emailWasMissing = rawEmail.isNullOrBlank()
        val emailVerified =
            when (val existingVerified = raw["emailVerified"] as? Boolean) {
                null -> emailWasMissing
                else -> existingVerified
            }
        val firstName =
            raw["firstName"]
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: fallbackName
        val lastName =
            raw["lastName"]
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: fallbackName
        val enabled = raw["enabled"] as? Boolean ?: true
        val requiredActions =
            (raw["requiredActions"] as? Collection<*>)
                ?.mapNotNull { it?.toString() }
                ?.filter { it.isNotBlank() }
                ?: emptyList()

        val rawAttributes = raw["attributes"] as? Map<*, *>
        val attributes = mutableMapOf<String, List<String>>()
        rawAttributes?.forEach { (key, value) ->
            val name = key?.toString() ?: return@forEach
            val values =
                when (value) {
                    is Collection<*> -> value.mapNotNull { it?.toString() }.filter { it.isNotBlank() }
                    is String -> listOf(value)
                    else -> emptyList()
                }
            if (values.isNotEmpty()) {
                attributes[name] = values
            }
        }
        attributes["tenant_id"] = listOf(tenantId.toString())

        val payload =
            mapOf(
                "email" to email,
                "emailVerified" to emailVerified,
                "firstName" to firstName,
                "lastName" to lastName,
                "enabled" to enabled,
                "requiredActions" to requiredActions,
                "attributes" to attributes,
            )

        client
            .put()
            .uri("/admin/realms/${properties.realm}/users/$userId")
            .headers { it.setBearerAuth(token) }
            .contentType(MediaType.APPLICATION_JSON)
            .body(payload)
            .retrieve()
            .onStatus({ it.is4xxClientError }, log4xx("upserting tenant_id for user $userId"))
            .toBodilessEntity()
    }

    fun findByUsername(
        username: String
    ): UserSummary? {
        ensureEnabled()
        val term = username.trim()
        if (term.isBlank()) return null
        val token = adminToken()
        val response =
            client
                .get()
                .uri { uri ->
                    uri
                        .path("/admin/realms/${properties.realm}/users")
                        .queryParam("search", term)
                        .queryParam("first", 0)
                        .queryParam("max", 20)
                        .build()
                }.headers { it.setBearerAuth(token) }
                .retrieve()
                .onStatus({ it.is4xxClientError }, log4xx("searching user $term"))
                .body(List::class.java) ?: emptyList<Any?>()
        return response.mapNotNull { mapUser(it) }
            .firstOrNull { it.username.equals(term, ignoreCase = true) }
    }

    fun updateStatus(
        userId: String,
        request: UserStatusUpdateRequest,
    ) {
        ensureEnabled()
        val token = adminToken()
        client
            .put()
            .uri("/admin/realms/${properties.realm}/users/$userId")
            .headers { it.setBearerAuth(token) }
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("enabled" to request.enabled))
            .retrieve()
            .onStatus({ it.is4xxClientError }, log4xx("updating user $userId status"))
            .toBodilessEntity()
    }

    fun updateProfile(
        userId: String,
        request: UserProfileUpdateRequest,
    ) {
        ensureEnabled()
        val token = adminToken()
        val payload = mutableMapOf<String, Any?>()
        request.username?.trim()?.takeIf { it.isNotBlank() }?.let { payload["username"] = it }
        request.email?.trim()?.takeIf { it.isNotBlank() }?.let { payload["email"] = it }
        request.name?.trim()?.takeIf { it.isNotBlank() }?.let {
            payload["firstName"] = it
            payload["lastName"] = it
        }
        if (payload.isEmpty()) {
            return
        }
        client
            .put()
            .uri("/admin/realms/${properties.realm}/users/$userId")
            .headers { it.setBearerAuth(token) }
            .contentType(MediaType.APPLICATION_JSON)
            .body(payload)
            .retrieve()
            .onStatus({ it.is4xxClientError }, log4xx("updating user $userId profile"))
            .toBodilessEntity()
    }

    fun resetPassword(
        userId: String,
        request: UserPasswordResetRequest,
    ) {
        ensureEnabled()
        val token = adminToken()
        resetPassword(userId, request, token)
        if (!request.temporary) {
            clearRequiredActions(userId, token)
        }
    }

    private fun resetPassword(
        userId: String,
        request: UserPasswordResetRequest,
        token: String,
    ) {
        client
            .put()
            .uri("/admin/realms/${properties.realm}/users/$userId/reset-password")
            .headers { it.setBearerAuth(token) }
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                mapOf(
                    "type" to "password",
                    "value" to request.password,
                    "temporary" to request.temporary,
                ),
            ).retrieve()
            .onStatus({ it.is4xxClientError }, log4xx("resetting password for user $userId"))
            .toBodilessEntity()
    }

    fun ensureRequiredProfile(
        userId: String,
        fallbackName: String,
        fallbackEmail: String,
    ) {
        ensureEnabled()
        val token = adminToken()
        val raw =
            client
                .get()
                .uri("/admin/realms/${properties.realm}/users/$userId")
                .headers { it.setBearerAuth(token) }
                .retrieve()
                .onStatus({ it.is4xxClientError }, log4xx("fetching user $userId"))
                .body(Map::class.java)
                ?: emptyMap<String, Any?>()

        val email = raw["email"]?.toString()?.trim()
        val firstName = raw["firstName"]?.toString()?.trim()
        val lastName = raw["lastName"]?.toString()?.trim()

        val payload = mutableMapOf<String, Any?>()
        if (email.isNullOrBlank()) {
            payload["email"] = fallbackEmail
            payload["emailVerified"] = true
        }
        if (firstName.isNullOrBlank()) {
            payload["firstName"] = fallbackName
        }
        if (lastName.isNullOrBlank()) {
            payload["lastName"] = fallbackName
        }
        if (payload.isEmpty()) {
            return
        }
        client
            .put()
            .uri("/admin/realms/${properties.realm}/users/$userId")
            .headers { it.setBearerAuth(token) }
            .contentType(MediaType.APPLICATION_JSON)
            .body(payload)
            .retrieve()
            .onStatus({ it.is4xxClientError }, log4xx("ensuring required profile for user $userId"))
            .toBodilessEntity()
    }

    private fun ensureTenantIdUserProfileAttribute(token: String) {
        if (tenantIdUserProfileEnsured) {
            return
        }
        val raw =
            client
                .get()
                .uri("/admin/realms/${properties.realm}/users/profile")
                .headers { it.setBearerAuth(token) }
                .retrieve()
                .onStatus({ it.is4xxClientError }, log4xx("fetching user profile"))
                .body(Map::class.java)
                ?: emptyMap<String, Any?>()

        val attributes = raw["attributes"] as? List<*> ?: emptyList<Any?>()
        val hasTenantId = attributes.any { (it as? Map<*, *>)?.get("name")?.toString() == "tenant_id" }
        if (hasTenantId) {
            tenantIdUserProfileEnsured = true
            return
        }

        val updatedAttributes =
            attributes.mapNotNull { it as? Map<*, *> }.map { it.toMutableMap() } +
                mapOf(
                    "name" to "tenant_id",
                    "displayName" to "Tenant ID",
                    "permissions" to mapOf(
                        "view" to listOf("admin"),
                        "edit" to listOf("admin"),
                    ),
                    "multivalued" to false,
                    "group" to "user-metadata",
                )

        val groups = raw["groups"] as? List<*> ?: emptyList<Any?>()
        val payload =
            mapOf(
                "attributes" to updatedAttributes,
                "groups" to groups,
            )

        client
            .put()
            .uri("/admin/realms/${properties.realm}/users/profile")
            .headers { it.setBearerAuth(token) }
            .contentType(MediaType.APPLICATION_JSON)
            .body(payload)
            .retrieve()
            .onStatus({ it.is4xxClientError }, log4xx("updating user profile"))
            .toBodilessEntity()

        tenantIdUserProfileEnsured = true
    }

    fun clearRequiredActions(
        userId: String,
    ) {
        ensureEnabled()
        val token = adminToken()
        clearRequiredActions(userId, token)
    }

    private fun clearRequiredActions(
        userId: String,
        token: String,
    ) {
        client
            .put()
            .uri("/admin/realms/${properties.realm}/users/$userId")
            .headers { it.setBearerAuth(token) }
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("requiredActions" to emptyList<String>()))
            .retrieve()
            .onStatus({ it.is4xxClientError }, log4xx("clearing required actions for user $userId"))
            .toBodilessEntity()
    }

    private fun log4xx(action: String) = RestClient.ResponseSpec.ErrorHandler { _, response ->
        val status = response.statusCode.value()
        val body =
            runCatching {
                response.body.use { it.readAllBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
                ?.take(2000)
        log.warn("event=keycloak_admin_4xx action={} status={} body={}", action, response.statusCode, body)
        val code =
            when (status) {
                401 -> ErrorCode.UNAUTHORIZED
                403 -> ErrorCode.FORBIDDEN
                404 -> ErrorCode.USER_NOT_FOUND
                else -> ErrorCode.VALIDATION_ERROR
            }
        throw SecruxException(code, "Keycloak admin request failed: $action (status=$status)")
    }

    private fun adminToken(): String {
        val form = LinkedMultiValueMap<String, String>()
        form.add("grant_type", "client_credentials")
        form.add("client_id", properties.clientId)
        form.add("client_secret", properties.clientSecret)
        form.add("scope", "openid")
        val tokenResponse =
            client
                .post()
                .uri("/realms/${properties.realm}/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResponse::class.java)
                ?: throw SecruxException(ErrorCode.UNAUTHORIZED, "Failed to fetch admin token")
        if (log.isDebugEnabled) {
            runCatching {
                val payload = tokenResponse.access_token.split(".").getOrNull(1)
                val decoded =
                    payload?.let {
                        String(Base64.getUrlDecoder().decode(it.padEnd(it.length + (4 - it.length % 4) % 4, '=')))
                    }
                log.debug("event=keycloak_admin_token_decoded payload={}", decoded)
            }
        }
        return tokenResponse.access_token
    }

    private fun mapUser(raw: Any?): UserSummary? {
        val map = raw as? Map<*, *> ?: return null
        val id = map["id"]?.toString() ?: return null
        val username = map["username"]?.toString() ?: return null
        return UserSummary(
            id = id,
            username = username,
            email = map["email"]?.toString(),
            enabled = map["enabled"] as? Boolean ?: false,
        )
    }

    private fun ensureEnabled() {
        if (!properties.enabled) {
            throw SecruxException(ErrorCode.UNAUTHORIZED, "Keycloak admin API is disabled")
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class TokenResponse(
        val access_token: String,
    )
}
