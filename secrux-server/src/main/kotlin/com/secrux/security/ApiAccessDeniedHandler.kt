package com.secrux.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.secrux.common.ApiResponse
import com.secrux.common.ErrorCode
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component

@Component
class ApiAccessDeniedHandler(
    private val objectMapper: ObjectMapper
) : AccessDeniedHandler {

    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException
    ) {
        if (response.isCommitted) {
            return
        }
        response.status = HttpServletResponse.SC_FORBIDDEN
        response.characterEncoding = Charsets.UTF_8.name()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        val body =
            ApiResponse<Nothing>(
                success = false,
                code = ErrorCode.FORBIDDEN.name,
                message = accessDeniedException.message?.takeIf { it.isNotBlank() } ?: "Forbidden"
            )
        response.writer.write(objectMapper.writeValueAsString(body))
    }
}

