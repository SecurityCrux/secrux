package com.secrux.support

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.HandlerMapping

@Component
class RequestPathMdcInterceptor : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) as? Map<*, *> ?: return true
        val previous = LinkedHashMap<String, String?>()

        fun set(key: String, value: Any?) {
            previous[key] = MDC.get(key)
            if (value == null) {
                MDC.remove(key)
            } else {
                MDC.put(key, value.toString())
            }
        }

        vars["taskId"]?.toString()?.takeIf { it.isNotBlank() }?.let { set(LogContext.TASK_ID, it) }
        vars["stageId"]?.toString()?.takeIf { it.isNotBlank() }?.let { set(LogContext.STAGE_ID, it) }
        vars["correlationId"]?.toString()?.takeIf { it.isNotBlank() }?.let { set(LogContext.CORRELATION_ID, it) }
        vars["stageType"]?.toString()?.takeIf { it.isNotBlank() }?.let { set(LogContext.STAGE_TYPE, it) }

        if (previous.isNotEmpty()) {
            request.setAttribute(ATTR, PreviousMdc(previous))
        }
        return true
    }

    override fun afterCompletion(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        ex: Exception?
    ) {
        val previous = (request.getAttribute(ATTR) as? PreviousMdc)?.values ?: return
        previous.forEach { (key, value) ->
            if (value == null) {
                MDC.remove(key)
            } else {
                MDC.put(key, value)
            }
        }
        request.removeAttribute(ATTR)
    }

    companion object {
        private const val ATTR = "com.secrux.mdc.pathVars"
    }

    private data class PreviousMdc(
        val values: Map<String, String?>
    )
}
