package com.secrux.support

import org.slf4j.MDC

object LogContext {
    const val REQUEST_ID = "requestId"
    const val TENANT_ID = "tenantId"
    const val USER_ID = "userId"
    const val CORRELATION_ID = "correlationId"
    const val TASK_ID = "taskId"
    const val STAGE_ID = "stageId"
    const val STAGE_TYPE = "stageType"
    const val HTTP_METHOD = "httpMethod"
    const val HTTP_PATH = "httpPath"

    inline fun <T> with(vararg values: Pair<String, Any?>, block: () -> T): T {
        val previous = HashMap<String, String?>(values.size)
        values.forEach { (key, value) ->
            previous[key] = MDC.get(key)
            if (value == null) {
                MDC.remove(key)
            } else {
                MDC.put(key, value.toString())
            }
        }
        return try {
            block()
        } finally {
            previous.forEach { (key, value) ->
                if (value == null) {
                    MDC.remove(key)
                } else {
                    MDC.put(key, value)
                }
            }
        }
    }
}

