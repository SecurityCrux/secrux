package com.secrux.workflow

import com.secrux.domain.Task
import com.secrux.domain.TaskStatus
import com.secrux.repo.TaskRepository
import com.secrux.support.LogContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class TaskWorkflowOrchestrator(
    private val workflowOrchestrator: WorkflowOrchestrator,
    private val taskRepository: TaskRepository
) {

    private val log = LoggerFactory.getLogger(TaskWorkflowOrchestrator::class.java)

    fun start(task: Task) {
        LogContext.with(
            LogContext.TENANT_ID to task.tenantId,
            LogContext.TASK_ID to task.taskId,
            LogContext.CORRELATION_ID to task.correlationId
        ) {
            log.info("event=workflow_start_requested taskStatus={}", task.status.name)
            if (task.status != TaskStatus.RUNNING) {
                taskRepository.updateStatus(task.taskId, task.tenantId, TaskStatus.RUNNING.name)
            }
            workflowOrchestrator.startWorkflow(task.tenantId, task.taskId)
        }
    }

    fun resume(stageId: String) {
        LogContext.with(LogContext.STAGE_ID to stageId) {
            log.info("event=workflow_resume_requested")
        }
    }

    fun cancel(taskId: UUID) {
        LogContext.with(LogContext.TASK_ID to taskId) {
            log.info("event=workflow_cancel_requested")
        }
        workflowOrchestrator.cancelWorkflow(taskId)
    }
}
