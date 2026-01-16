package com.secrux.service

import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.domain.IamUserRole
import com.secrux.dto.TokenResponse
import com.secrux.repo.AppUserDirectoryRepository
import com.secrux.repo.IamPermissionRepository
import com.secrux.repo.IamUserRoleRepository
import com.secrux.repo.LocalCredentialRepository
import com.secrux.repo.RefreshTokenRepository
import com.secrux.security.AuthMode
import com.secrux.security.SecruxAuthProperties
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.SignatureAlgorithm
import io.jsonwebtoken.security.Keys
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.OffsetDateTime
import java.util.Base64
import java.util.Date
import java.util.UUID

@Service
class LocalAuthService(
    private val dsl: DSLContext,
    private val clock: Clock,
    private val authProperties: SecruxAuthProperties,
    private val passwordHashService: PasswordHashService,
    private val userDirectoryRepository: AppUserDirectoryRepository,
    private val localCredentialRepository: LocalCredentialRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val permissionRepository: IamPermissionRepository,
    private val userRoleRepository: IamUserRoleRepository,
    private val bootstrapService: IamBootstrapService,
) {

    fun login(usernameOrEmail: String, password: String): TokenResponse {
        requireLocalMode()
        val term = usernameOrEmail.trim()
        if (term.isBlank()) {
            throw SecruxException(ErrorCode.AUTH_INVALID_CREDENTIALS, "Invalid credentials")
        }

        val matches = userDirectoryRepository.findByUsernameOrEmail(tenantId = null, usernameOrEmail = term)
        if (matches.size != 1) {
            throw SecruxException(ErrorCode.AUTH_INVALID_CREDENTIALS, "Invalid credentials")
        }
        val user = matches.first()
        if (!user.enabled) {
            throw SecruxException(ErrorCode.UNAUTHORIZED, "User is disabled")
        }
        val credential = localCredentialRepository.findByUserId(user.userId)
            ?: throw SecruxException(ErrorCode.AUTH_INVALID_CREDENTIALS, "Invalid credentials")
        if (!passwordHashService.matches(password, credential.passwordHash)) {
            throw SecruxException(ErrorCode.AUTH_INVALID_CREDENTIALS, "Invalid credentials")
        }

        ensureBootstrapAdmin(user.tenantId, user.userId)

        val now = OffsetDateTime.now(clock)
        userDirectoryRepository.updateLastLoginAt(user.tenantId, user.userId, now)

        val permissions = permissionRepository.listEffectivePermissions(user.tenantId, user.userId).sorted()
        userDirectoryRepository.setLegacyRoles(user.tenantId, user.userId, permissions)
        return issueTokens(
            tenantId = user.tenantId,
            userId = user.userId,
            email = user.email,
            username = user.username,
            permissions = permissions
        )
    }

    fun refresh(refreshToken: String): TokenResponse {
        requireLocalMode()
        val raw = refreshToken.trim()
        if (raw.isBlank()) {
            throw SecruxException(ErrorCode.AUTH_TOKEN_INVALID, "Invalid refresh token")
        }
        val now = OffsetDateTime.now(clock)
        val tokenHash = hashToken(raw)
        val token = refreshTokenRepository.findActiveByHash(tokenHash, now)
            ?: throw SecruxException(ErrorCode.AUTH_TOKEN_INVALID, "Invalid refresh token")

        val user = userDirectoryRepository.findById(token.tenantId, token.userId)
        if (user != null && !user.enabled) {
            refreshTokenRepository.revoke(token.tokenId)
            throw SecruxException(ErrorCode.UNAUTHORIZED, "User is disabled")
        }

        val permissions = permissionRepository.listEffectivePermissions(token.tenantId, token.userId).sorted()
        userDirectoryRepository.setLegacyRoles(token.tenantId, token.userId, permissions)

        return dsl.transactionResult { config ->
            val ctx = DSL.using(config)
            refreshTokenRepository.revoke(ctx, token.tokenId)
            issueTokens(
                ctx = ctx,
                tenantId = token.tenantId,
                userId = token.userId,
                email = user?.email,
                username = user?.username,
                permissions = permissions
            )
        }
    }

    fun logout(refreshToken: String) {
        requireLocalMode()
        val raw = refreshToken.trim()
        if (raw.isBlank()) {
            return
        }
        val now = OffsetDateTime.now(clock)
        val tokenHash = hashToken(raw)
        val token = refreshTokenRepository.findActiveByHash(tokenHash, now) ?: return
        refreshTokenRepository.revoke(token.tokenId)
    }

    private fun ensureBootstrapAdmin(tenantId: UUID, userId: UUID) {
        val roleIds = userRoleRepository.listRoleIdsByUser(tenantId, userId)
        if (roleIds.isNotEmpty()) return
        if (userRoleRepository.existsAnyForTenant(tenantId)) return
        val builtIns = bootstrapService.ensureBuiltInRoles(tenantId)
        val now = OffsetDateTime.now(clock)
        val assignment =
            IamUserRole(
                id = UUID.randomUUID(),
                tenantId = tenantId,
                userId = userId,
                roleId = builtIns.adminRoleId,
                createdAt = now
            )
        dsl.transaction { config ->
            val ctx = DSL.using(config)
            userRoleRepository.insertAll(ctx, listOf(assignment))
        }
    }

    private fun issueTokens(
        tenantId: UUID,
        userId: UUID,
        email: String?,
        username: String?,
        permissions: List<String>
    ): TokenResponse =
        issueTokens(
            ctx = null,
            tenantId = tenantId,
            userId = userId,
            email = email,
            username = username,
            permissions = permissions
        )

    private fun issueTokens(
        ctx: DSLContext?,
        tenantId: UUID,
        userId: UUID,
        email: String?,
        username: String?,
        permissions: List<String>
    ): TokenResponse {
        val now = OffsetDateTime.now(clock)
        val accessExpiresAt = now.plus(authProperties.accessTokenTtl)
        val refreshExpiresAt = now.plus(authProperties.refreshTokenTtl)

        val key = Keys.hmacShaKeyFor(authProperties.localSecret.toByteArray())
        val jwt =
            Jwts.builder()
                .setSubject(userId.toString())
                .setIssuedAt(Date.from(now.toInstant()))
                .setExpiration(Date.from(accessExpiresAt.toInstant()))
                .claim(authProperties.tenantClaim, tenantId.toString())
                .claim(authProperties.emailClaim, email)
                .claim(authProperties.nameClaim, username)
                .claim("realm_access", mapOf("roles" to permissions))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact()

        val refreshRaw = generateRefreshToken()
        val refreshHash = hashToken(refreshRaw)
        val writer = ctx ?: dsl
        refreshTokenRepository.insert(
            ctx = writer,
            tenantId = tenantId,
            userId = userId,
            tokenHash = refreshHash,
            expiresAt = refreshExpiresAt
        )

        return TokenResponse(
            access_token = jwt,
            refresh_token = refreshRaw,
            expires_in = authProperties.accessTokenTtl.toSeconds().toInt(),
            refresh_expires_in = authProperties.refreshTokenTtl.toSeconds().toInt(),
            token_type = "Bearer",
            scope = "openid"
        )
    }

    private fun generateRefreshToken(): String {
        val bytes = ByteArray(48)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hashToken(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun requireLocalMode() {
        if (authProperties.mode != AuthMode.LOCAL) {
            throw SecruxException(ErrorCode.UNAUTHORIZED, "Local auth endpoints are disabled")
        }
    }
}

