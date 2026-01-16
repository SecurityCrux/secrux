package com.secrux.security

import com.secrux.service.UserService
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.IOException

@Component
class PrincipalSyncFilter(
    private val userService: UserService
) : OncePerRequestFilter() {

    @Throws(ServletException::class, IOException::class)
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val authentication = SecurityContextHolder.getContext().authentication
        val principal = authentication?.principal
        if (principal is SecruxPrincipal && request.getAttribute(SYNC_ATTRIBUTE) == null) {
            userService.syncFromPrincipal(principal)
            request.setAttribute(SYNC_ATTRIBUTE, principal.userId)
        }
        filterChain.doFilter(request, response)
    }

    companion object {
        private const val SYNC_ATTRIBUTE = "com.secrux.principal.synced"
    }
}

