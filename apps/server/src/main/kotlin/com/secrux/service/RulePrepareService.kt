package com.secrux.service

import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.domain.Rule
import com.secrux.domain.Ruleset
import com.secrux.domain.RulesetItem
import com.secrux.domain.RuleSelectorMode
import com.secrux.domain.Stage
import com.secrux.domain.StageMetrics
import com.secrux.domain.StageSignals
import com.secrux.domain.StageSpec
import com.secrux.domain.StageStatus
import com.secrux.domain.StageType
import com.secrux.dto.StageSummary
import com.secrux.repo.RuleRepository
import com.secrux.repo.RulesetRepository
import com.secrux.repo.TaskRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

@Service
class RulePrepareService(
    private val taskRepository: TaskRepository,
    private val ruleRepository: RuleRepository,
    private val rulesetRepository: RulesetRepository,
    private val stageLifecycle: StageLifecycle,
    private val taskLogService: TaskLogService,
    private val clock: Clock
) {
    private val log = LoggerFactory.getLogger(RulePrepareService::class.java)

    fun run(tenantId: UUID, taskId: UUID, stageId: UUID): StageSummary {
        val startedAt = OffsetDateTime.now(clock)
        val task = taskRepository.findById(taskId, tenantId)
            ?: throw SecruxException(ErrorCode.TASK_NOT_FOUND, "Task not found for rule prepare")
        val selector = task.spec.ruleSelector
        val (ruleset, createdNew) = when (selector.mode) {
            RuleSelectorMode.EXPLICIT -> {
                val ruleIds = selector.explicitRules?.map { UUID.fromString(it) }
                    ?: throw SecruxException(ErrorCode.VALIDATION_ERROR, "Explicit rules required")
                createAdhocRuleset(tenantId, selector.profile ?: "adhoc", ruleIds)
            }

            RuleSelectorMode.PROFILE -> {
                val profile = selector.profile ?: throw SecruxException(
                    ErrorCode.VALIDATION_ERROR,
                    "Profile name required"
                )
                val ruleset = rulesetRepository.findLatestByProfile(tenantId, profile)
                    ?: throw SecruxException(ErrorCode.VALIDATION_ERROR, "Profile $profile has no published ruleset")
                ruleset to false
            }

            RuleSelectorMode.AUTO -> resolveAutoRuleset(tenantId, selector.profile ?: "default")
        }
        val endedAt = OffsetDateTime.now(clock)
        val duration = Duration.between(startedAt, endedAt).toMillis()
        val stage = Stage(
            stageId = stageId,
            tenantId = tenantId,
            taskId = taskId,
            type = StageType.RULES_PREPARE,
            spec = StageSpec(
                version = "v1",
                inputs = mapOf("mode" to selector.mode.name),
                params = mapOf(
                    "profile" to (selector.profile ?: ""),
                    "rulesetId" to ruleset.rulesetId.toString(),
                    "createdNewRuleset" to createdNew
                )
            ),
            status = StageStatus.SUCCEEDED,
            metrics = StageMetrics(durationMs = duration),
            signals = StageSignals(needsAiReview = false),
            artifacts = listOf("ruleset:${ruleset.rulesetId}"),
            startedAt = startedAt,
            endedAt = endedAt
        )
        stageLifecycle.persist(stage, task.correlationId)
        taskLogService.logStageEvent(
            taskId = taskId,
            stageId = stageId,
            stageType = StageType.RULES_PREPARE,
            message = "Prepared ruleset ${ruleset.rulesetId} (profile=${ruleset.profile})"
        )
        return stage.toSummary()
    }

    private fun createAdhocRuleset(
        tenantId: UUID,
        profile: String,
        ruleIds: List<UUID>
    ): Pair<Ruleset, Boolean> {
        if (ruleIds.isEmpty()) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "Explicit rules empty")
        }
        val rules = ruleRepository.findByIds(tenantId, ruleIds)
        if (rules.size != ruleIds.size) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "Some explicit rules not found")
        }
        return persistRuleset(tenantId, profile, rules, source = "explicit")
    }

    private fun resolveAutoRuleset(tenantId: UUID, profile: String): Pair<Ruleset, Boolean> {
        val existing = rulesetRepository.findLatestByProfile(tenantId, profile)
        if (existing != null) {
            return existing to false
        }
        if (profile != "default") {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "No ruleset found for auto profile $profile")
        }
        val rules = ruleRepository.list(tenantId).filter { it.enabled }
        if (rules.isEmpty()) {
            log.warn("event=ruleset_auto_empty profile={}", profile)
            return persistRuleset(tenantId, profile, emptyList(), source = "auto-empty")
        }
        return persistRuleset(tenantId, profile, rules, source = "auto")
    }

    private fun persistRuleset(
        tenantId: UUID,
        profile: String,
        rules: List<Rule>,
        source: String
    ): Pair<Ruleset, Boolean> {
        val rulesetId = UUID.randomUUID()
        val version = OffsetDateTime.now(clock).toEpochSecond().toString()
        val items = rules.map {
            RulesetItem(
                id = UUID.randomUUID(),
                rulesetId = rulesetId,
                ruleId = it.ruleId,
                engine = it.engine,
                severity = it.severityDefault,
                enabled = it.enabled,
                ruleHash = it.hash
            )
        }
        val hash = digest(items)
        val ruleset = Ruleset(
            rulesetId = rulesetId,
            tenantId = tenantId,
            source = source,
            version = version,
            profile = profile,
            langs = rules.flatMap(Rule::langs).distinct(),
            hash = hash,
            signature = null,
            uri = null,
            deletedAt = null
        )
        rulesetRepository.insert(ruleset, items)
        return ruleset to true
    }

    private fun digest(items: List<RulesetItem>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val text = items.sortedBy { it.ruleId }
            .joinToString("|") { "${it.ruleId}:${it.ruleHash}:${it.severity}:${it.enabled}" }
        val bytes = digest.digest(text.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
