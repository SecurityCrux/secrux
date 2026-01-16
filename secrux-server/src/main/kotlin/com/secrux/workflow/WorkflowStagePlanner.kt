package com.secrux.workflow

import com.secrux.domain.StageType
import com.secrux.domain.TaskType
import org.springframework.stereotype.Component

@Component
class WorkflowStagePlanner {

    fun planStages(taskType: TaskType): List<StageType> =
        when (taskType) {
            TaskType.SCA_CHECK ->
                listOf(
                    StageType.SOURCE_PREPARE,
                    StageType.SCAN_EXEC,
                    StageType.RESULT_PROCESS,
                    StageType.RESULT_REVIEW,
                )

            else ->
                listOf(
                    StageType.SOURCE_PREPARE,
                    StageType.RULES_PREPARE,
                    StageType.SCAN_EXEC,
                    StageType.RESULT_PROCESS,
                    StageType.RESULT_REVIEW
                )
        }
}
