package com.secrux.service

import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.domain.TaskType

object TaskEngineRegistry {

    private val bindings: Map<TaskType, Set<String>> = mapOf(
        TaskType.CODE_CHECK to setOf("semgrep"),
        TaskType.SCA_CHECK to setOf("trivy")
    )

    fun allowedEngines(type: TaskType): Set<String> = bindings[type] ?: emptySet()

    fun resolveEngine(type: TaskType, requested: String?): String? {
        val allowed = allowedEngines(type)
        if (allowed.isEmpty()) {
            return requested
        }
        val engine = requested ?: allowed.first()
        if (!allowed.contains(engine)) {
            throw SecruxException(
                ErrorCode.VALIDATION_ERROR,
                "Engine $engine is not permitted for task type ${type.name}"
            )
        }
        return engine
    }
}
