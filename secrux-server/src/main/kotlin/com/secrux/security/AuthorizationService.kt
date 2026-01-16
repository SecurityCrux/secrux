package com.secrux.security

import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import java.time.Duration
import org.slf4j.LoggerFactory
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient

@Service
class AuthorizationService(
    private val properties: AuthorizationProperties,
    builder: RestClient.Builder
) {

    private val log = LoggerFactory.getLogger(AuthorizationService::class.java)
    private val restClient: RestClient = builder
        .baseUrl(properties.opaUrl)
        .requestFactory(createRequestFactory(properties.timeout))
        .build()

    fun require(
        principal: SecruxPrincipal,
        action: AuthorizationAction,
        resource: AuthorizationResource,
        context: Map<String, Any?> = emptyMap()
    ) {
        if (!properties.enabled) {
            val roles = principal.roles.map { it.lowercase() }.toSet()
            if (roles.contains("secrux_admin") || roles.contains(action.value.lowercase())) {
                return
            }
            throw SecruxException(
                errorCode = ErrorCode.FORBIDDEN,
                message = "Operation not permitted for action ${action.value}"
            )
        }
        val decision = runCatching {
            restClient.post()
                .uri(properties.policyPath)
                .body(
                    OpaQuery(
                        input = OpaInput(
                            subject = SubjectInput(
                                tenantId = principal.tenantId.toString(),
                                userId = principal.userId.toString(),
                                email = principal.email,
                                username = principal.username,
                                roles = principal.roles.toList()
                            ),
                            action = action.value,
                            resource = resource,
                            context = context
                        )
                    )
                )
                .retrieve()
                .body(OpaDecision::class.java)
        }.getOrElse { ex ->
            log.warn(
                "event=authz_opa_decision_failed action={} resource={} failOpen={}",
                action.value,
                resource,
                properties.failOpen,
                ex
            )
            throw SecruxException(
                errorCode = ErrorCode.AUTHZ_SERVICE_UNAVAILABLE,
                message = "Authorization service unavailable",
                retryable = true,
                cause = ex
            )
        }
        if (decision?.result?.allow != true) {
            throw SecruxException(
                errorCode = ErrorCode.FORBIDDEN,
                message = "Operation not permitted for action ${action.value}"
            )
        }
    }

    private fun createRequestFactory(timeout: Duration): ClientHttpRequestFactory {
        val millis = timeout.toMillis().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(millis)
            setReadTimeout(millis)
        }
    }

    private data class OpaQuery(
        val input: OpaInput
    )

    private data class OpaInput(
        val subject: SubjectInput,
        val action: String,
        val resource: AuthorizationResource,
        val context: Map<String, Any?> = emptyMap()
    )

    private data class SubjectInput(
        val tenantId: String,
        val userId: String,
        val email: String?,
        val username: String?,
        val roles: List<String>
    )

    private data class OpaDecision(
        val result: OpaResult?
    )

    private data class OpaResult(
        val allow: Boolean = false,
        val redactions: List<String>? = emptyList()
    )
}
