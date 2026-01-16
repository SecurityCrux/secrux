package com.secrux.security

import com.secrux.repo.AppUserDirectoryRepository
import com.secrux.repo.IamPermissionRepository
import com.secrux.repo.IdePluginTokenRepository
import com.secrux.service.IntellijPluginTokenService
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.stereotype.Service

@Service
class IdePluginTokenAuthenticationService(
    private val tokenRepository: IdePluginTokenRepository,
    private val userDirectoryRepository: AppUserDirectoryRepository,
    private val permissionRepository: IamPermissionRepository,
    private val tokenService: IntellijPluginTokenService,
) {

    fun authenticate(rawToken: String): UsernamePasswordAuthenticationToken? {
        val token = rawToken.trim()
        if (!IdePluginTokenFormat.isIdePluginToken(token)) return null

        val tokenHash = tokenService.hashForLookup(token)
        val record = tokenRepository.findActiveByHash(tokenHash) ?: return null

        val user = userDirectoryRepository.findById(record.tenantId, record.userId) ?: return null
        if (!user.enabled) return null

        val dbPermissions = permissionRepository.listEffectivePermissions(record.tenantId, record.userId)
        val roleNames = if (dbPermissions.isNotEmpty()) dbPermissions.toSet() else user.roles.toSet()
        val principal =
            SecruxPrincipal(
                tenantId = record.tenantId,
                userId = record.userId,
                email = user.email,
                username = user.username,
                roles = roleNames,
            )

        val now = tokenService.now()
        tokenService.touchTokenUsage(record.tokenId, now)

        return UsernamePasswordAuthenticationToken(principal, token, toAuthorities(roleNames))
    }

    private fun toAuthorities(roleNames: Set<String>): Collection<GrantedAuthority> =
        if (roleNames.isEmpty()) {
            setOf(SimpleGrantedAuthority("ROLE_USER"))
        } else {
            roleNames.map { SimpleGrantedAuthority("ROLE_${it.uppercase()}") }.toSet()
        }
}

