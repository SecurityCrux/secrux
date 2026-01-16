package com.secrux.security

import com.secrux.repo.AppUserDirectoryRepository
import com.secrux.repo.IamPermissionRepository
import org.springframework.core.convert.converter.Converter
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.jwt.Jwt
import java.util.UUID

class SecruxJwtAuthenticationConverter(
    private val authProperties: SecruxAuthProperties,
    private val userDirectoryRepository: AppUserDirectoryRepository,
    private val permissionRepository: IamPermissionRepository
) : Converter<Jwt, AbstractAuthenticationToken> {

    override fun convert(source: Jwt): AbstractAuthenticationToken {
        val tokenRoleNames = extractRoleNames(source)
        val principal = buildPrincipal(source, tokenRoleNames)
        val authorities = toAuthorities(principal.roles)
        return SecruxJwtAuthenticationToken(source, principal, authorities)
    }

    private fun buildPrincipal(jwt: Jwt, tokenRoleNames: Set<String>): SecruxPrincipal {
        val userIdClaim = jwt.getClaimAsString(authProperties.userClaim)
            ?: throw unauthorized("Missing user claim '${authProperties.userClaim}'")
        val userId = runCatching { UUID.fromString(userIdClaim) }.getOrElse { ex ->
            throw unauthorized("Invalid user claim format: ${ex.message}")
        }

        val tenantIdFromClaim =
            jwt.getClaimAsString(authProperties.tenantClaim)
                ?.let {
                    runCatching { UUID.fromString(it) }.getOrElse { ex ->
                        throw unauthorized("Invalid tenant claim format: ${ex.message}")
                    }
                }
        val tenantIdFromDb = userDirectoryRepository.findTenantIdByUserId(userId)
        val tenantId = tenantIdFromClaim ?: tenantIdFromDb
            ?: throw unauthorized("Missing tenant claim '${authProperties.tenantClaim}'")
        if (tenantIdFromClaim != null && tenantIdFromDb != null && tenantIdFromClaim != tenantIdFromDb) {
            throw unauthorized("Tenant claim does not match user directory")
        }

        val email = jwt.getClaimAsString(authProperties.emailClaim)
        val username = jwt.getClaimAsString(authProperties.nameClaim)

        val user = userDirectoryRepository.findById(tenantId, userId)
        if (user != null && !user.enabled) {
            throw unauthorized("User is disabled")
        }

        val dbPermissions = permissionRepository.listEffectivePermissions(tenantId, userId)
        val roleNames = if (dbPermissions.isNotEmpty()) dbPermissions else tokenRoleNames

        return SecruxPrincipal(
            tenantId = tenantId,
            userId = userId,
            email = email,
            username = username,
            roles = roleNames
        )
    }

    private fun unauthorized(message: String): OAuth2AuthenticationException =
        OAuth2AuthenticationException(
            OAuth2Error(
                "invalid_token",
                message,
                null
            )
        )

    private fun toAuthorities(roleNames: Set<String>): Collection<GrantedAuthority> =
        if (roleNames.isEmpty()) {
            setOf(SimpleGrantedAuthority("ROLE_USER"))
        } else {
            roleNames.map { SimpleGrantedAuthority("ROLE_${it.uppercase()}") }.toSet()
        }

    private fun extractRoleNames(jwt: Jwt): Set<String> =
        authProperties.rolePaths.flatMap { path ->
            val value = resolvePath(jwt.claims, path)
            when (value) {
                is Collection<*> -> value.mapNotNull { it?.toString() }
                is String -> listOf(value)
                else -> emptyList()
            }
        }.filter { it.isNotBlank() }.toSet()

    private fun resolvePath(source: Any?, path: String): Any? {
        if (source !is Map<*, *>) {
            return null
        }
        var current: Any? = source
        for (segment in path.split(".")) {
            current = when (val node = current) {
                is Map<*, *> -> node[segment]
                else -> return null
            }
        }
        return current
    }
}

class SecruxJwtAuthenticationToken(
    private val jwt: Jwt,
    private val principalDetails: SecruxPrincipal,
    authorities: Collection<GrantedAuthority>
) : AbstractAuthenticationToken(authorities) {

    init {
        super.setAuthenticated(true)
    }

    override fun getCredentials(): Any = jwt

    override fun getPrincipal(): Any = principalDetails

    override fun getName(): String = principalDetails.userId.toString()
}
