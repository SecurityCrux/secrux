package com.secrux.security

import com.secrux.support.LogContext
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.IOException
import java.util.UUID

@Component
class RequestMdcFilter : OncePerRequestFilter() {

    @Throws(ServletException::class, IOException::class)
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val requestId = request.getHeader(REQUEST_ID_HEADER)?.trim().takeUnless { it.isNullOrBlank() } ?: UUID.randomUUID().toString()
        response.setHeader(REQUEST_ID_HEADER, requestId)

        val authentication = SecurityContextHolder.getContext().authentication
        val principal = authentication?.principal as? SecruxPrincipal

        LogContext.with(
            LogContext.REQUEST_ID to requestId,
            LogContext.TENANT_ID to principal?.tenantId,
            LogContext.USER_ID to principal?.userId,
            LogContext.HTTP_METHOD to request.method,
            LogContext.HTTP_PATH to request.requestURI
        ) {
            filterChain.doFilter(request, response)
        }
    }

    companion object {
        const val REQUEST_ID_HEADER = "X-Request-Id"
    }
}

