package com.secrux.adapter.jira

import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.adapter.TicketPayload
import com.secrux.domain.TicketIssueType
import com.secrux.repo.TicketProviderConfigRepository
import com.secrux.security.SecretCrypto
import com.secrux.support.LogContext
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.nio.charset.StandardCharsets
import java.util.UUID

@Component
class JiraCloudTicketClient(
    private val configRepository: TicketProviderConfigRepository,
    private val secretCrypto: SecretCrypto,
    private val webClientBuilder: WebClient.Builder
) {

    private val log = LoggerFactory.getLogger(JiraCloudTicketClient::class.java)

    fun createTickets(tenantId: UUID, provider: String, payloads: List<TicketPayload>): List<String> =
        LogContext.with(LogContext.TENANT_ID to tenantId) {
            val config =
                configRepository.findByProvider(tenantId, provider)
                    ?: throw SecruxException(ErrorCode.VALIDATION_ERROR, "Ticket provider '$provider' is not configured")
            if (!config.enabled) {
                throw SecruxException(ErrorCode.VALIDATION_ERROR, "Ticket provider '$provider' is disabled")
            }
            val apiToken =
                runCatching { secretCrypto.decrypt(config.apiTokenCipher) }
                    .getOrElse { throw SecruxException(ErrorCode.INTERNAL_ERROR, "Failed to decrypt ticket provider credential") }
            val client =
                webClientBuilder
                    .baseUrl(config.baseUrl)
                    .build()

            payloads.map { payload ->
                val issueTypeName = resolveIssueTypeName(payload.issueType, config.issueTypeNames)
                val request =
                    JiraCreateIssueRequest(
                        fields =
                            JiraIssueFields(
                                project = JiraProjectRef(key = config.projectKey),
                                summary = payload.summary,
                                description = payload.description,
                                issuetype = JiraIssueTypeRef(name = issueTypeName),
                                labels = payload.labels.filter { it.isNotBlank() }.distinct()
                            )
                    )
                try {
                    val response =
                        client.post()
                            .uri("/rest/api/2/issue")
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .headers { it.setBasicAuth(config.email, apiToken, StandardCharsets.UTF_8) }
                            .bodyValue(request)
                            .retrieve()
                            .bodyToMono(JiraCreateIssueResponse::class.java)
                            .block()
                            ?: throw SecruxException(ErrorCode.INTERNAL_ERROR, "Jira response is empty")
                    val key = response.key?.trim()
                    if (key.isNullOrBlank()) {
                        throw SecruxException(ErrorCode.INTERNAL_ERROR, "Jira issue key is missing in response")
                    }
                    key
                } catch (ex: WebClientResponseException) {
                    val body = ex.responseBodyAsString
                    log.warn(
                        "event=jira_issue_create_failed provider={} status={} body={}",
                        provider,
                        ex.statusCode.value(),
                        body.take(2000)
                    )
                    throw SecruxException(
                        ErrorCode.INTERNAL_ERROR,
                        "Jira create issue failed (${ex.statusCode.value()}): ${body.take(2000)}"
                    )
                } catch (ex: SecruxException) {
                    throw ex
                } catch (ex: Exception) {
                    log.warn("event=jira_issue_create_failed provider={} error={}", provider, ex.message)
                    throw SecruxException(ErrorCode.INTERNAL_ERROR, "Jira create issue failed: ${ex.message}")
                }
            }
        }

    private fun resolveIssueTypeName(
        issueType: TicketIssueType,
        names: Map<String, String>
    ): String =
        names[issueType.name]
            ?: when (issueType) {
                TicketIssueType.BUG -> "Bug"
                TicketIssueType.TASK -> "Task"
                TicketIssueType.STORY -> "Story"
            }
}

private data class JiraCreateIssueRequest(
    val fields: JiraIssueFields
)

private data class JiraIssueFields(
    val project: JiraProjectRef,
    val summary: String,
    val description: String,
    val issuetype: JiraIssueTypeRef,
    val labels: List<String> = emptyList()
)

private data class JiraProjectRef(
    val key: String
)

private data class JiraIssueTypeRef(
    val name: String
)

private data class JiraCreateIssueResponse(
    val id: String? = null,
    val key: String? = null,
    val self: String? = null
)
