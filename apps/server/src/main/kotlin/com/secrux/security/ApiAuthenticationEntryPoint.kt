package com.secrux.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.secrux.common.ApiResponse
import com.secrux.common.ErrorCode
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.stereotype.Component

@Component
class ApiAuthenticationEntryPoint(
    private val objectMapper: ObjectMapper
) : AuthenticationEntryPoint {

    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException
    ) {
        if (response.isCommitted) {
            return
        }
        response.status = HttpServletResponse.SC_UNAUTHORIZED
        response.characterEncoding = Charsets.UTF_8.name()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        val body =
            ApiResponse<Nothing>(
                success = false,
                code = ErrorCode.UNAUTHORIZED.name,
                message = authException.message?.takeIf { it.isNotBlank() } ?: "Unauthorized"
            )
        response.writer.write(objectMapper.writeValueAsString(body))
    }
}

