package com.secrux.gateway

import com.fasterxml.jackson.databind.ObjectMapper
import com.secrux.domain.ExecutorStatus
import com.secrux.domain.StageType
import com.secrux.repo.TaskRepository
import com.secrux.service.ExecutorService
import com.secrux.service.ExecutorSessionRegistry
import com.secrux.service.LogChunkPayload
import com.secrux.service.TaskLogService
import com.secrux.service.TaskResultService
import com.secrux.service.ExecutorTaskResultPayload
import com.secrux.support.LogContext
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import org.slf4j.LoggerFactory
import java.util.UUID

class ExecutorGatewayHandler(
    private val objectMapper: ObjectMapper,
    private val executorService: ExecutorService,
    private val sessionRegistry: ExecutorSessionRegistry,
    private val taskRepository: TaskRepository,
    private val taskLogService: TaskLogService,
    private val taskResultService: TaskResultService
) : SimpleChannelInboundHandler<String>() {

    private val log = LoggerFactory.getLogger(ExecutorGatewayHandler::class.java)
    private var executorId: UUID? = null
    private var tenantId: UUID? = null
    private val authorizedTasks = mutableSetOf<UUID>()

    override fun channelRead0(ctx: ChannelHandlerContext, msg: String) {
        val node = objectMapper.readTree(msg)
        val type = node.get("type")?.asText()
        when (type) {
            "register" -> handleRegister(ctx, node)
            "heartbeat" -> handleHeartbeat(ctx, node)
            "log_chunk" -> handleLogChunk(node)
            "task_result" -> handleTaskResult(node)
            else -> log.warn("event=executor_message_ignored reason=unknown_type type={}", type)
        }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        executorId?.let { sessionRegistry.remove(it) }
        super.channelInactive(ctx)
    }

    private fun isAuthorizedTask(taskId: UUID): Boolean {
        val safeExecutorId = executorId
        val safeTenantId = tenantId
        if (safeExecutorId == null || safeTenantId == null) {
            log.warn("event=executor_message_rejected reason=not_registered")
            return false
        }
        if (authorizedTasks.contains(taskId)) {
            return true
        }
        val task = taskRepository.find(taskId)
        if (task == null) {
            log.warn("event=executor_message_rejected reason=task_not_found taskId={}", taskId)
            return false
        }
        if (task.tenantId != safeTenantId) {
            LogContext.with(LogContext.TASK_ID to taskId, LogContext.TENANT_ID to safeTenantId) {
                log.warn(
                    "event=executor_message_rejected reason=tenant_mismatch executorId={} taskTenantId={}",
                    safeExecutorId,
                    task.tenantId
                )
            }
            return false
        }
        val assignedExecutorId = task.executorId
        if (assignedExecutorId == null || assignedExecutorId != safeExecutorId) {
            LogContext.with(LogContext.TASK_ID to taskId, LogContext.TENANT_ID to safeTenantId) {
                log.warn(
                    "event=executor_message_rejected reason=executor_mismatch executorId={} taskExecutorId={}",
                    safeExecutorId,
                    assignedExecutorId
                )
            }
            return false
        }
        authorizedTasks.add(taskId)
        return true
    }

    private fun handleRegister(ctx: ChannelHandlerContext, node: com.fasterxml.jackson.databind.JsonNode) {
        val token = node.get("token")?.asText() ?: return
        val executor = executorService.updateHeartbeat(token, null, null)
        if (executor != null) {
            val currentExecutorId = executorId
            val currentTenantId = tenantId
            if (currentExecutorId != null && currentExecutorId != executor.executorId) {
                log.warn(
                    "event=executor_message_rejected reason=executor_switch_attempt currentExecutorId={} newExecutorId={}",
                    currentExecutorId,
                    executor.executorId
                )
                ctx.writeAndFlush(objectMapper.writeValueAsString(mapOf("type" to "error", "message" to "executor switch not allowed")))
                ctx.close()
                return
            }
            if (currentTenantId != null && currentTenantId != executor.tenantId) {
                log.warn(
                    "event=executor_message_rejected reason=tenant_switch_attempt currentTenantId={} newTenantId={}",
                    currentTenantId,
                    executor.tenantId
                )
                ctx.writeAndFlush(objectMapper.writeValueAsString(mapOf("type" to "error", "message" to "tenant switch not allowed")))
                ctx.close()
                return
            }
            executorId = executor.executorId
            tenantId = executor.tenantId
            sessionRegistry.register(executor.executorId, ctx.channel())
            executorService.updateStatus(executor.tenantId, executor.executorId, ExecutorStatus.READY)
            val response = mapOf(
                "type" to "register_ack",
                "executorId" to executor.executorId.toString(),
                "status" to executor.status.name
            )
            ctx.writeAndFlush(objectMapper.writeValueAsString(response))
        } else {
            ctx.writeAndFlush(objectMapper.writeValueAsString(mapOf("type" to "error", "message" to "invalid token")))
            ctx.close()
        }
    }

    private fun handleHeartbeat(ctx: ChannelHandlerContext, node: com.fasterxml.jackson.databind.JsonNode) {
        val token = node.get("token")?.asText() ?: return
        val cpu = node.get("cpuUsage")?.floatValue()
        val mem = node.get("memoryUsageMb")?.intValue()
        val executor = executorService.updateHeartbeat(token, cpu, mem)
        if (executor != null) {
            val currentExecutorId = executorId
            val currentTenantId = tenantId
            if (currentExecutorId != null && currentExecutorId != executor.executorId) {
                log.warn(
                    "event=executor_message_rejected reason=executor_switch_attempt currentExecutorId={} newExecutorId={}",
                    currentExecutorId,
                    executor.executorId
                )
                ctx.writeAndFlush(objectMapper.writeValueAsString(mapOf("type" to "error", "message" to "executor switch not allowed")))
                ctx.close()
                return
            }
            if (currentTenantId != null && currentTenantId != executor.tenantId) {
                log.warn(
                    "event=executor_message_rejected reason=tenant_switch_attempt currentTenantId={} newTenantId={}",
                    currentTenantId,
                    executor.tenantId
                )
                ctx.writeAndFlush(objectMapper.writeValueAsString(mapOf("type" to "error", "message" to "tenant switch not allowed")))
                ctx.close()
                return
            }
            executorId = executor.executorId
            tenantId = executor.tenantId
            sessionRegistry.register(executor.executorId, ctx.channel())
            ctx.writeAndFlush(
                objectMapper.writeValueAsString(
                    mapOf(
                        "type" to "heartbeat_ack",
                        "status" to executor.status.name
                    )
                )
            )
        } else {
            ctx.writeAndFlush(objectMapper.writeValueAsString(mapOf("type" to "error", "message" to "unknown token")))
        }
    }

    private fun handleLogChunk(node: com.fasterxml.jackson.databind.JsonNode) {
        val taskIdValue = node.get("taskId")?.asText() ?: return
        val sequence = node.get("sequence")?.asLong() ?: return
        val streamValue = node.get("stream")?.asText()?.lowercase() ?: "stdout"
        val content = node.get("content")?.asText() ?: ""
        val isLast = node.get("isLast")?.asBoolean() ?: false
        val taskId = runCatching { UUID.fromString(taskIdValue) }.getOrNull() ?: return
        if (!isAuthorizedTask(taskId)) {
            return
        }
        val stageId = node.get("stageId")?.asText()?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val stageType = node.get("stageType")?.asText()?.let { runCatching { StageType.valueOf(it) }.getOrNull() }
        val stream = when (streamValue) {
            "stderr" -> com.secrux.domain.LogStream.STDERR
            else -> com.secrux.domain.LogStream.STDOUT
        }
        runCatching {
            taskLogService.appendChunk(
                LogChunkPayload(
                    taskId = taskId,
                    sequence = sequence,
                    stream = stream,
                    content = content,
                    isLast = isLast,
                    stageId = stageId,
                    stageType = stageType
                )
            )
        }.onFailure { throwable: Throwable ->
            LogContext.with(
                LogContext.TENANT_ID to tenantId,
                LogContext.TASK_ID to taskIdValue,
                LogContext.STAGE_ID to stageId,
                LogContext.STAGE_TYPE to stageType?.name
            ) {
                log.warn("event=executor_log_chunk_ingest_failed", throwable)
            }
        }
    }

    private fun handleTaskResult(node: com.fasterxml.jackson.databind.JsonNode) {
        val taskIdValue = node.get("taskId")?.asText() ?: return
        val taskId = runCatching { UUID.fromString(taskIdValue) }.getOrNull() ?: return
        if (!isAuthorizedTask(taskId)) {
            return
        }
        val stageId = node.get("stageId")?.asText()?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val stageType =
            node.get("stageType")?.asText()?.let {
                runCatching { com.secrux.domain.StageType.valueOf(it) }.getOrNull()
            }
        LogContext.with(
            LogContext.TENANT_ID to tenantId,
            LogContext.TASK_ID to taskIdValue,
            LogContext.STAGE_ID to stageId,
            LogContext.STAGE_TYPE to stageType?.name
        ) {
            runCatching {
                val artifacts =
                    node.get("artifacts")?.takeIf { it.isObject }?.let {
                        runCatching {
                            objectMapper.convertValue(it, object : com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {})
                        }.getOrNull()
                    }
                val payload =
                    ExecutorTaskResultPayload(
                        taskId = taskId,
                        stageId = stageId,
                        stageType = stageType,
                        success = node.get("success")?.asBoolean() ?: false,
                        exitCode = node.get("exitCode")?.asInt(),
                        log = node.get("log")?.asText(),
                        result = node.get("result")?.asText(),
                        artifacts = artifacts,
                        runLog = node.get("runLog")?.asText(),
                        error = node.get("error")?.asText()
                    )
                taskResultService.handleResult(payload)
                val safeExecutorId = executorId
                val safeTenantId = tenantId
                if (safeExecutorId != null && safeTenantId != null) {
                    executorService.updateStatus(safeTenantId, safeExecutorId, ExecutorStatus.READY)
                }
                log.info(
                    "event=executor_task_result_consumed success={} exitCode={}",
                    payload.success,
                    payload.exitCode ?: -1
                )
            }.onFailure { throwable ->
                log.error("event=executor_task_result_ingest_failed", throwable)
            }
        }
    }
}
