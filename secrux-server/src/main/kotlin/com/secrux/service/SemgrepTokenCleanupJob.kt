package com.secrux.service

import com.secrux.repo.TaskRepository
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.OffsetDateTime

@Component
class SemgrepTokenCleanupJob(
    private val taskRepository: TaskRepository,
    private val taskLogService: TaskLogService,
    private val clock: Clock
) {
    @Scheduled(fixedDelayString = "\${secrux.semgrep.token-cleanup-interval-ms:3600000}")
    fun cleanupExpiredTokens() {
        val now = OffsetDateTime.now(clock)
        taskRepository.clearExpiredSemgrepTokens(now)
        taskLogService.cleanupOlderThan(now.minusDays(7))
    }
}

