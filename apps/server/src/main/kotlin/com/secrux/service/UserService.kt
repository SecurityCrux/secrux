package com.secrux.service

import com.secrux.repo.AppUserRepository
import com.secrux.security.SecruxPrincipal
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class UserService(
    private val appUserRepository: AppUserRepository
) {

    private val log = LoggerFactory.getLogger(UserService::class.java)
    private val missingEmailLogged = ConcurrentHashMap.newKeySet<String>()

    fun syncFromPrincipal(principal: SecruxPrincipal) {
        val email = resolveEmail(principal)
        if (principal.email.isNullOrBlank()) {
            val key = "${principal.tenantId}:${principal.userId}"
            if (missingEmailLogged.add(key)) {
                log.warn(
                    "event=user_sync_email_missing fallbackEmail={}",
                    email
                )
            }
        }
        appUserRepository.upsert(
            userId = principal.userId,
            tenantId = principal.tenantId,
            email = email,
            username = principal.username,
            name = principal.username,
            roles = principal.roles.toList()
        )
    }

    private fun resolveEmail(principal: SecruxPrincipal): String {
        principal.email?.takeIf { it.isNotBlank() }?.let { return it }
        principal.username?.takeIf { it.isNotBlank() }?.let { username ->
            return "$username@${principal.tenantId}"
        }
        return "${principal.userId}@${principal.tenantId}"
    }
}
