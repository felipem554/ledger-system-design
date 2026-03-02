package com.ledger.observability

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class TenantFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val tenantId = request.getHeader("X-Tenant-Id")
        val traceId = request.getHeader("X-Trace-Id") ?: UUID.randomUUID().toString().replace("-", "").take(16)

        try {
            tenantId?.let { MDC.put("tenantId", it) }
            MDC.put("traceId", traceId)
            response.setHeader("X-Trace-Id", traceId)
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove("tenantId")
            MDC.remove("traceId")
            MDC.remove("txId")
        }
    }
}
