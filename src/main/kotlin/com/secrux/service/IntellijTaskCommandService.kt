package com.secrux.service

import com.secrux.domain.AiReviewSpec
import com.secrux.domain.RuleSelectorMode
import com.secrux.domain.RuleSelectorSpec
import com.secrux.domain.ScaAiReviewSpec
import com.secrux.domain.SourceRefType
import com.secrux.domain.SourceSpec
import com.secrux.domain.Task
import com.secrux.domain.TaskSpec
import com.secrux.domain.TaskStatus
import com.secrux.domain.TaskType
import com.secrux.dto.CreateIntellijTaskRequest
import com.secrux.dto.TaskSummary
import com.secrux.repo.TaskRepository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@Service
class IntellijTaskCommandService(
    private val taskRepository: TaskRepository,
    private val clock: Clock,
) {

    fun createTask(
        tenantId: UUID,
        owner: UUID?,
        request: CreateIntellijTaskRequest,
    ): TaskSummary {
        val createdAt = OffsetDateTime.now(clock)
        val normalizedName = request.name?.trim()?.takeIf { it.isNotBlank() }
        val normalizedBranch = request.branch?.trim()?.takeIf { it.isNotBlank() }
        val normalizedCommit = request.commitSha?.trim()?.takeIf { it.isNotBlank() }

        val defaultName =
            when {
                normalizedBranch != null && normalizedCommit != null -> "IDE Audit ${normalizedBranch}@${normalizedCommit.take(7)}"
                normalizedBranch != null -> "IDE Audit $normalizedBranch"
                else -> "IDE Audit ${createdAt.toLocalDateTime()}"
            }

        val taskId = UUID.randomUUID()
        val task =
            Task(
                taskId = taskId,
                tenantId = tenantId,
                projectId = request.projectId,
                repoId = request.repoId,
                executorId = null,
                type = TaskType.IDE_AUDIT,
                spec =
                    TaskSpec(
                        source = SourceSpec(),
                        ruleSelector = RuleSelectorSpec(mode = RuleSelectorMode.AUTO),
                        aiReview = AiReviewSpec(),
                        scaAiReview = ScaAiReviewSpec(),
                    ),
                status = TaskStatus.PENDING,
                owner = owner,
                name = normalizedName ?: defaultName,
                correlationId = taskId.toString(),
                sourceRefType = SourceRefType.BRANCH,
                sourceRef = normalizedBranch,
                commitSha = normalizedCommit,
                engine = null,
                semgrepProEnabled = false,
                semgrepTokenCipher = null,
                semgrepTokenExpiresAt = null,
                createdAt = createdAt,
                updatedAt = null,
            )
        taskRepository.insert(task)
        return task.toSummary()
    }
}

