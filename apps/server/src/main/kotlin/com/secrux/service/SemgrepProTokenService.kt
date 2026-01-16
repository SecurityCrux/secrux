package com.secrux.service

import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.domain.Task
import com.secrux.dto.SemgrepEngineOptionsRequest
import com.secrux.security.SecretCrypto
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime

data class SemgrepTokenState(
    val enabled: Boolean,
    val cipher: String?,
    val expiresAt: OffsetDateTime?
)

@Component
class SemgrepProTokenService(
    private val secretCrypto: SecretCrypto,
    private val clock: Clock
) {
    private val tokenTtl = Duration.ofDays(1)

    fun resolve(options: SemgrepEngineOptionsRequest?, existing: Task?): SemgrepTokenState {
        if (options?.usePro != true) {
            return SemgrepTokenState(enabled = false, cipher = null, expiresAt = null)
        }
        val now = OffsetDateTime.now(clock)
        val providedToken = options.token?.takeIf { it.isNotBlank() }
        if (providedToken != null) {
            val cipher = secretCrypto.encrypt(providedToken)
            val expiresAt = now.plus(tokenTtl)
            return SemgrepTokenState(enabled = true, cipher = cipher, expiresAt = expiresAt)
        }
        val existingCipher = existing?.semgrepTokenCipher
        val existingExpiry = existing?.semgrepTokenExpiresAt
        if (existing?.semgrepProEnabled == true && existingCipher != null && existingExpiry != null && existingExpiry.isAfter(now)) {
            return SemgrepTokenState(enabled = true, cipher = existingCipher, expiresAt = existingExpiry)
        }
        throw SecruxException(ErrorCode.VALIDATION_ERROR, "Semgrep Pro token is required when enabling Pro scanning")
    }
}

