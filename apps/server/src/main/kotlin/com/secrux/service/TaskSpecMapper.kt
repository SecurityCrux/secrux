package com.secrux.service

import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.domain.ArchiveSourceSpec
import com.secrux.domain.AiReviewSpec
import com.secrux.domain.EngineOptionsSpec
import com.secrux.domain.FailOnPolicy
import com.secrux.domain.FailStrategy
import com.secrux.domain.FilesystemSourceSpec
import com.secrux.domain.GitSourceSpec
import com.secrux.domain.ImageSourceSpec
import com.secrux.domain.PolicySpec
import com.secrux.domain.RuleSelectorSpec
import com.secrux.domain.ScanPolicy
import com.secrux.domain.ScaAiReviewSpec
import com.secrux.domain.SemgrepEngineOptionsSpec
import com.secrux.domain.SbomSourceSpec
import com.secrux.domain.SourceRefType
import com.secrux.domain.SourceSpec
import com.secrux.domain.Task
import com.secrux.domain.TaskSpec
import com.secrux.domain.TicketPolicySpec
import com.secrux.domain.UrlSourceSpec
import com.secrux.dto.AiReviewSpecPayload
import com.secrux.dto.AiReviewSpecRequest
import com.secrux.dto.ArchiveSourcePayload
import com.secrux.dto.EngineOptionsPayload
import com.secrux.dto.EngineOptionsRequest
import com.secrux.dto.FailOnPolicyPayload
import com.secrux.dto.FilesystemSourcePayload
import com.secrux.dto.GitSourcePayload
import com.secrux.dto.ImageSourcePayload
import com.secrux.dto.PolicySpecPayload
import com.secrux.dto.RuleSelectorPayload
import com.secrux.dto.ScaAiReviewSpecPayload
import com.secrux.dto.ScaAiReviewSpecRequest
import com.secrux.dto.ScanPolicyPayload
import com.secrux.dto.SemgrepEngineOptionsPayload
import com.secrux.dto.SourceSpecPayload
import com.secrux.dto.SbomSourcePayload
import com.secrux.dto.TaskSpecPayload
import com.secrux.dto.TaskSpecRequest
import com.secrux.dto.TaskSummary
import com.secrux.dto.TicketPolicyPayload
import com.secrux.dto.UrlSourcePayload
import org.springframework.stereotype.Component

@Component
class TaskSpecMapper {

    fun toDomain(spec: TaskSpecRequest): TaskSpec =
        TaskSpec(
            source =
                SourceSpec(
                    git =
                        spec.source.git?.let {
                            GitSourceSpec(
                                repo = it.repo,
                                ref = it.ref,
                                refType = it.refType ?: spec.source.gitRefType ?: SourceRefType.BRANCH,
                                auth = it.auth,
                            )
                        },
                    archive = spec.source.archive?.let { ArchiveSourceSpec(it.url, it.uploadId) },
                    filesystem = spec.source.filesystem?.let { FilesystemSourceSpec(it.path, it.uploadId) },
                    image = spec.source.image?.let { ImageSourceSpec(ref = it.ref) },
                    sbom = spec.source.sbom?.let { SbomSourceSpec(uploadId = it.uploadId, url = it.url) },
                    url = spec.source.url?.let { UrlSourceSpec(it.url) },
                    baselineFingerprints = spec.source.baselineFingerprints,
                ),
            ruleSelector =
                RuleSelectorSpec(
                    mode = spec.ruleSelector.mode,
                    profile = spec.ruleSelector.profile,
                    explicitRules = spec.ruleSelector.explicitRules,
                ),
            policy =
                spec.policy?.let {
                    PolicySpec(
                        failOn = it.failOn?.let { fail -> FailOnPolicy(fail.severity) },
                        scanPolicy =
                            it.scanPolicy?.let { policy ->
                                ScanPolicy(policy.mode, policy.parallelism, policy.failStrategy)
                            } ?: ScanPolicy(failStrategy = FailStrategy.FAIL_CLOSED),
                    )
                },
            ticket =
                spec.ticket?.let {
                    TicketPolicySpec(
                        project = it.project,
                        assigneeStrategy = it.assigneeStrategy,
                        slaDays = it.slaDays,
                        labels = it.labels,
                    )
                },
            engineOptions = spec.engineOptions.toDomain(),
            aiReview = normalizeAiReview(spec.aiReview),
            scaAiReview = normalizeScaAiReview(spec.scaAiReview),
        )

    private fun normalizeAiReview(input: AiReviewSpecRequest?): AiReviewSpec {
        input ?: return AiReviewSpec()
        val mode = input.mode?.trim()?.lowercase()
        val dataFlowMode = input.dataFlowMode?.trim()?.lowercase()
        if (input.enabled) {
            if (mode.isNullOrBlank()) {
                throw SecruxException(ErrorCode.VALIDATION_ERROR, "aiReview.mode is required when aiReview.enabled=true")
            }
            if (mode !in setOf("simple", "precise")) {
                throw SecruxException(ErrorCode.VALIDATION_ERROR, "aiReview.mode must be one of: simple, precise")
            }
            if (!dataFlowMode.isNullOrBlank() && dataFlowMode !in setOf("simple", "precise")) {
                throw SecruxException(ErrorCode.VALIDATION_ERROR, "aiReview.dataFlowMode must be one of: simple, precise")
            }
        }
        return AiReviewSpec(enabled = input.enabled, mode = mode ?: "simple", dataFlowMode = dataFlowMode?.takeIf { it.isNotBlank() })
    }

    private fun normalizeScaAiReview(input: ScaAiReviewSpecRequest?): ScaAiReviewSpec {
        input ?: return ScaAiReviewSpec()
        if (input.enabled) {
            val anyEnabled = input.critical || input.high || input.medium || input.low || input.info
            if (!anyEnabled) {
                throw SecruxException(ErrorCode.VALIDATION_ERROR, "scaAiReview: at least one severity switch must be enabled when scaAiReview.enabled=true")
            }
        }
        return ScaAiReviewSpec(
            enabled = input.enabled,
            critical = input.critical,
            high = input.high,
            medium = input.medium,
            low = input.low,
            info = input.info,
        )
    }
}

internal fun Task.toSummary(): TaskSummary =
    TaskSummary(
        taskId = taskId,
        tenantId = tenantId,
        projectId = projectId,
        repoId = repoId,
        executorId = executorId,
        status = status.name,
        type = type,
        name = name,
        correlationId = correlationId,
        owner = owner,
        sourceRefType = sourceRefType,
        sourceRef = sourceRef,
        commitSha = commitSha,
        engine = engine,
        semgrepProEnabled = semgrepProEnabled,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt?.toString(),
        spec = spec.toPayload(),
    )

internal fun TaskSpec.toPayload(): TaskSpecPayload =
    TaskSpecPayload(
        source =
            SourceSpecPayload(
                git = source.git?.let { GitSourcePayload(repo = it.repo, ref = it.ref, refType = it.refType) },
                gitRefType = source.git?.refType,
                archive = source.archive?.let { ArchiveSourcePayload(url = it.url, uploadId = it.uploadId) },
                filesystem = source.filesystem?.let { FilesystemSourcePayload(path = it.path, uploadId = it.uploadId) },
                image = source.image?.let { ImageSourcePayload(ref = it.ref) },
                sbom = source.sbom?.let { SbomSourcePayload(uploadId = it.uploadId, url = it.url) },
                url = source.url?.let { UrlSourcePayload(url = it.url) },
                baselineFingerprints = source.baselineFingerprints,
            ),
        ruleSelector =
            RuleSelectorPayload(
                mode = ruleSelector.mode,
                profile = ruleSelector.profile,
                explicitRules = ruleSelector.explicitRules,
            ),
        policy =
            policy?.let {
                PolicySpecPayload(
                    failOn = it.failOn?.let { fail -> FailOnPolicyPayload(fail.severity) },
                    scanPolicy =
                        it.scanPolicy?.let { scan ->
                            ScanPolicyPayload(
                                mode = scan.mode,
                                parallelism = scan.parallelism,
                                failStrategy = scan.failStrategy,
                            )
                        },
                )
            },
        ticket =
            ticket?.let {
                TicketPolicyPayload(
                    project = it.project,
                    assigneeStrategy = it.assigneeStrategy,
                    slaDays = it.slaDays,
                    labels = it.labels,
                )
            },
        engineOptions = engineOptions.toPayload(),
        aiReview = AiReviewSpecPayload(enabled = aiReview.enabled, mode = aiReview.mode, dataFlowMode = aiReview.dataFlowMode),
        scaAiReview =
            ScaAiReviewSpecPayload(
                enabled = scaAiReview.enabled,
                critical = scaAiReview.critical,
                high = scaAiReview.high,
                medium = scaAiReview.medium,
                low = scaAiReview.low,
                info = scaAiReview.info,
            ),
    )

internal fun EngineOptionsRequest?.toDomain(): EngineOptionsSpec? =
    this?.let {
        EngineOptionsSpec(
            semgrep = it.semgrep?.let { opt -> SemgrepEngineOptionsSpec(usePro = opt.usePro) },
        )
    }

internal fun EngineOptionsSpec?.toPayload(): EngineOptionsPayload? =
    this?.let {
        EngineOptionsPayload(
            semgrep = it.semgrep?.let { sem -> SemgrepEngineOptionsPayload(usePro = sem.usePro) },
        )
    }
