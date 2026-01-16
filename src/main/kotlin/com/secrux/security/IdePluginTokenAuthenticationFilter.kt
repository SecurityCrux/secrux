package com.secrux.security

import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.IOException

@Component
class IdePluginTokenAuthenticationFilter(
    private val authenticationService: IdePluginTokenAuthenticationService,
) : OncePerRequestFilter() {

    @Throws(ServletException::class, IOException::class)
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val current = SecurityContextHolder.getContext().authentication
        if (current == null || current is AnonymousAuthenticationToken) {
            val token = resolveBearerToken(request)
            if (!token.isNullOrBlank() && IdePluginTokenFormat.isIdePluginToken(token)) {
                val authentication = authenticationService.authenticate(token)
                if (authentication != null) {
                    SecurityContextHolder.getContext().authentication = authentication
                }
            }
        }
        filterChain.doFilter(request, response)
    }

    private fun resolveBearerToken(request: HttpServletRequest): String? {
        val header = request.getHeader("Authorization") ?: return null
        if (!header.startsWith("Bearer ", ignoreCase = true)) return null
        return header.substringAfter("Bearer", "").trim().ifBlank { null }
    }
}
