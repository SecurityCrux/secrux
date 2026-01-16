package com.secrux.service

import com.secrux.domain.LogLevel
import com.secrux.domain.LogStream
import com.secrux.domain.Stage
import com.secrux.domain.StageType
import com.secrux.domain.TaskLogChunk
import com.secrux.repo.TaskLogRepository
import com.secrux.support.LogContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Clock
import java.time.OffsetDateTime
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class LogChunkPayload(
    val taskId: UUID,
    val sequence: Long,
    val stream: LogStream,
    val content: String,
    val isLast: Boolean,
    val stageId: UUID? = null,
    val stageType: StageType? = null,
    val level: LogLevel = LogLevel.INFO
)

@Service
class TaskLogService(
    private val taskLogRepository: TaskLogRepository,
    private val clock: Clock
) {

    private val log = LoggerFactory.getLogger(TaskLogService::class.java)
    private val emitters = ConcurrentHashMap<UUID, MutableSet<SseEmitter>>()
    private val fallbackChunkSize = 1024

    fun appendChunk(payload: LogChunkPayload) {
        val chunk = TaskLogChunk(
            chunkId = UUID.randomUUID(),
            taskId = payload.taskId,
            sequence = payload.sequence,
            stream = payload.stream,
            content = payload.content,
            isLast = payload.isLast,
            createdAt = OffsetDateTime.now(clock),
            stageId = payload.stageId,
            stageType = payload.stageType,
            level = payload.level
        )
        taskLogRepository.insert(chunk)
        broadcast(payload.taskId, chunk)
    }

    fun list(taskId: UUID, afterSequence: Long?, limit: Int): List<TaskLogChunk> =
        taskLogRepository.list(taskId, afterSequence, limit)

    fun listForStage(taskId: UUID, stage: Stage, limit: Int): List<TaskLogChunk> {
        return taskLogRepository.listForStage(stage.stageId, limit)
    }

    fun stream(taskId: UUID): SseEmitter {
        val emitter = SseEmitter(0L)
        emitter.onCompletion { removeEmitter(taskId, emitter) }
        emitter.onTimeout { removeEmitter(taskId, emitter) }
        emitter.onError { removeEmitter(taskId, emitter) }
        emitters.compute(taskId) { _, set ->
            val target = set ?: Collections.newSetFromMap(ConcurrentHashMap())
            target.add(emitter)
            target
        }
        runCatching {
            emitter.send(SseEmitter.event().comment("ready"))
        }
        return emitter
    }

    fun cleanupOlderThan(cutoff: OffsetDateTime) {
        taskLogRepository.deleteOlderThan(cutoff)
    }

    fun clearTask(taskId: UUID) {
        taskLogRepository.deleteByTask(taskId)
        emitters.remove(taskId)?.forEach { emitter ->
            runCatching { emitter.complete() }
        }
    }

    private fun broadcast(taskId: UUID, chunk: TaskLogChunk) {
        val payload = mapOf(
            "sequence" to chunk.sequence,
            "stream" to chunk.stream.name.lowercase(),
            "content" to chunk.content,
            "createdAt" to chunk.createdAt.toString(),
            "isLast" to chunk.isLast
        )
        emitters[taskId]?.let { targets ->
            val iterator = targets.iterator()
            while (iterator.hasNext()) {
                val emitter = iterator.next()
                try {
                    emitter.send(SseEmitter.event().data(payload))
                    if (chunk.isLast) {
                        emitter.complete()
                        iterator.remove()
                    }
                } catch (ex: Exception) {
                    LogContext.with(LogContext.TASK_ID to taskId) {
                        log.debug("event=task_log_emitter_dead", ex)
                    }
                    iterator.remove()
                }
            }
        }
    }

    private fun removeEmitter(taskId: UUID, emitter: SseEmitter) {
        emitters[taskId]?.remove(emitter)
    }

    fun captureResultLog(taskId: UUID, rawContent: String) {
        val content = rawContent.trim()
        if (content.isEmpty()) {
            return
        }
        if (taskLogRepository.hasLogs(taskId)) {
            LogContext.with(LogContext.TASK_ID to taskId) {
                log.debug("event=task_log_fallback_skipped reason=logs_already_exist")
            }
            return
        }
        var sequence = taskLogRepository.nextSequence(taskId)
        val chunks = content.chunked(fallbackChunkSize)
        chunks.forEachIndexed { index, chunk ->
            appendChunk(
                LogChunkPayload(
                    taskId = taskId,
                    sequence = sequence++,
                    stream = LogStream.STDOUT,
                    content = chunk,
                    isLast = index == chunks.lastIndex
                )
            )
        }
    }

    fun captureStageLog(
        taskId: UUID,
        stageId: UUID,
        stageType: StageType,
        rawContent: String,
        stream: LogStream = LogStream.STDOUT,
        level: LogLevel = LogLevel.INFO
    ) {
        if (rawContent.isBlank()) {
            return
        }
        var sequence = taskLogRepository.nextSequence(taskId)
        val chunks = rawContent.chunked(fallbackChunkSize)
        chunks.forEach { chunk ->
            appendChunk(
                LogChunkPayload(
                    taskId = taskId,
                    sequence = sequence++,
                    stream = stream,
                    content = chunk,
                    isLast = false,
                    stageId = stageId,
                    stageType = stageType,
                    level = level
                )
            )
        }
    }

    fun logStageEvent(
        taskId: UUID,
        stageId: UUID,
        stageType: StageType,
        message: String,
        level: LogLevel = LogLevel.INFO,
        stream: LogStream = LogStream.STDOUT
    ) {
        val payload =
            LogChunkPayload(
                taskId = taskId,
                sequence = taskLogRepository.nextSequence(taskId),
                stream = stream,
                content = message,
                isLast = false,
                stageId = stageId,
                stageType = stageType,
                level = level
            )
        appendChunk(payload)
    }
}
