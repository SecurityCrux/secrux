package com.secrux.service

import com.secrux.domain.Stage
import com.secrux.domain.StageMetrics
import com.secrux.domain.StageSignals
import com.secrux.domain.StageSpec
import com.secrux.domain.StageStatus
import com.secrux.domain.StageType
import com.secrux.events.PlatformEventPublisher
import com.secrux.repo.StageRepository
import com.secrux.repo.TaskRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.junit.jupiter.MockitoExtension
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class StageLifecycleTest {

    @Mock
    private lateinit var stageRepository: StageRepository

    @Mock
    private lateinit var eventPublisher: PlatformEventPublisher

    @Mock
    private lateinit var taskRepository: TaskRepository

    @Test
    fun `persist publishes stage event`() {
        val clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
        val lifecycle = StageLifecycle(stageRepository, eventPublisher, taskRepository, clock)
        val stage = Stage(
            stageId = UUID.randomUUID(),
            tenantId = UUID.randomUUID(),
            taskId = UUID.randomUUID(),
            type = StageType.SCAN_EXEC,
            spec = StageSpec(version = "v1"),
            status = StageStatus.SUCCEEDED,
            metrics = StageMetrics(),
            signals = StageSignals(),
            artifacts = listOf("sarif:/tmp/report"),
            startedAt = OffsetDateTime.now(clock),
            endedAt = OffsetDateTime.now(clock)
        )

        lifecycle.persist(stage, "corr")

        verify(stageRepository).upsert(stage)
        val eventCaptor = argumentCaptor<com.secrux.events.PlatformEvent>()
        verify(eventPublisher).publish(eventCaptor.capture())
        val event = eventCaptor.firstValue
        assertEquals("StageCompleted", event.event)
        assertEquals(stage.taskId.toString(), event.payload["task_id"])
    }

    @Test
    fun `persist failure updates task status`() {
        val clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
        val lifecycle = StageLifecycle(stageRepository, eventPublisher, taskRepository, clock)
        val stage = Stage(
            stageId = UUID.randomUUID(),
            tenantId = UUID.randomUUID(),
            taskId = UUID.randomUUID(),
            type = StageType.SOURCE_PREPARE,
            spec = StageSpec(version = "v1"),
            status = StageStatus.FAILED,
            metrics = StageMetrics(),
            signals = StageSignals(),
            artifacts = emptyList(),
            startedAt = OffsetDateTime.now(clock),
            endedAt = OffsetDateTime.now(clock)
        )

        lifecycle.persist(stage, "corr")

        verify(taskRepository).updateStatus(stage.taskId, stage.tenantId, com.secrux.domain.TaskStatus.FAILED.name)
    }
}
